package com.example.ilink.application.routing;

import com.example.ilink.application.conversation.ChatHistoryStore;
import com.example.ilink.application.conversation.UserSessionStore;
import com.example.ilink.bootstrap.Config;
import com.example.ilink.capabilities.memory.MemoryService;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 唯一的用户意图识别入口：先拆需求，再逐项分配同级能力。 */
public final class IntentRecognizer {

    private static final int MAX_REQUIREMENTS = 12;

    private final HttpClient httpClient;
    private final RouteClient routeClient;
    private final Gson gson = new Gson();
    private final CapabilityRegistry capabilities;
    private final RoutePromptBuilder promptBuilder;
    private final RouteResponseParser responseParser = new RouteResponseParser();
    private final IntentNormalizer normalizer;
    private final boolean unifiedRouting;

    public IntentRecognizer(HttpClient httpClient) {
        this(httpClient, CapabilityRegistry.defaults());
    }

    public IntentRecognizer(HttpClient httpClient, CapabilityRegistry capabilities) {
        this.httpClient = httpClient;
        this.routeClient = this::sendRoute;
        this.capabilities = capabilities;
        this.promptBuilder = new RoutePromptBuilder(capabilities);
        this.normalizer = new IntentNormalizer(capabilities);
        this.unifiedRouting = true;
    }

    IntentRecognizer(RouteClient routeClient) {
        this.httpClient = null;
        this.routeClient = routeClient;
        this.capabilities = CapabilityRegistry.defaults();
        this.promptBuilder = new RoutePromptBuilder(capabilities);
        this.normalizer = new IntentNormalizer(capabilities);
        this.unifiedRouting = false;
    }

    /** 保留旧注入签名；完整上下文现在由调用方一次性传入。 */
    public IntentRecognizer(HttpClient httpClient, ChatHistoryStore ignoredHistory,
                            MemoryService ignoredMemory, UserSessionStore ignoredSessions) {
        this(httpClient);
    }

    public IntentPlan recognize(String userId, String userMessage, IntentContext context) {
        return recognize(userId, userMessage, RoutingContext.minimal(context));
    }

    public IntentPlan recognize(String userId, String userMessage, RoutingContext context) {
        if (userMessage == null || userMessage.isBlank()) return fallbackChatPlan("");
        if (unifiedRouting) return recognizeUnified(userMessage, context);
        try {
            List<AtomicRequirement> requirements = new ArrayList<>(splitRequirements(userMessage, context));
            if (requirements.isEmpty()) requirements.add(new AtomicRequirement("r1", userMessage, List.of()));

            AssignmentBatch firstBatch = assignRequirements(userMessage, context, requirements, false);
            Map<String, IntentAction> assigned = new LinkedHashMap<>(firstBatch.actions());
            appendUncoveredRequirements(requirements, firstBatch.uncovered());
            orderRequirementsBySource(userMessage, requirements);
            List<AtomicRequirement> missing = requirements.stream()
                    .filter(requirement -> !assigned.containsKey(requirement.id())).toList();
            if (!missing.isEmpty()) {
                System.out.println("[路由覆盖校验] 首轮遗漏=" + missing.stream().map(AtomicRequirement::id).toList());
                assigned.putAll(assignRequirements(userMessage, context, missing, true).actions());
            }

            List<IntentAction> actions = new ArrayList<>();
            for (AtomicRequirement requirement : requirements) {
                IntentAction action = assigned.get(requirement.id());
                if (action == null) {
                    System.err.println("[路由覆盖校验] 补偿后仍遗漏=" + requirement.id() + "，降级为chat保留原需求");
                    action = new IntentAction(requirement.id(), requirement.text(),
                            requirement.dependsOn(), IntentResult.chat());
                }
                actions.add(action);
            }
            return new IntentPlan(actions);
        } catch (Exception error) {
            System.err.println("[意图识别] 识别失败：" + error.getMessage());
            return fallbackChatPlan(userMessage);
        }
    }

    /** 生产快路径：一次模型调用同时完成需求拆分、能力分配和参数提取。 */
    private IntentPlan recognizeUnified(String userMessage, RoutingContext context) {
        try {
            JsonObject result = requestJson(promptBuilder.buildUnifiedPrompt(context), userMessage);
            JsonArray array = result.has("actions") && result.get("actions").isJsonArray()
                    ? result.getAsJsonArray("actions") : new JsonArray();
            List<IntentAction> actions = new ArrayList<>();
            Set<String> ids = new LinkedHashSet<>();
            for (JsonElement element : array) {
                if (!element.isJsonObject() || actions.size() >= MAX_REQUIREMENTS) break;
                JsonObject action = element.getAsJsonObject();
                String requestText = string(action, "action_text");
                if (requestText.isBlank()) requestText = string(action, "request_text");
                if (requestText.isBlank()) continue;
                String id = string(action, "requirement_id");
                if (id.isBlank()) id = string(action, "id");
                if (id.isBlank() || !ids.add(id)) {
                    id = "r" + (actions.size() + 1);
                    while (!ids.add(id)) id += "x";
                }
                normalizeAction(requestText, requestText, action, context.mediaContext());
                actions.add(new IntentAction(id, requestText,
                        stringList(action, "depends_on"), toIntentResult(action)));
            }
            return actions.isEmpty() ? fallbackChatPlan(userMessage) : new IntentPlan(actions);
        } catch (Exception error) {
            System.err.println("[统一意图识别] 识别失败：" + error.getMessage());
            return fallbackChatPlan(userMessage);
        }
    }

    private List<AtomicRequirement> splitRequirements(String userMessage, RoutingContext context) throws Exception {
        JsonObject result = requestJson(promptBuilder.buildRequirementPrompt(context), userMessage);
        JsonArray array = result.has("requirements") && result.get("requirements").isJsonArray()
                ? result.getAsJsonArray("requirements") : new JsonArray();
        List<AtomicRequirement> requirements = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject() || requirements.size() >= MAX_REQUIREMENTS) break;
            JsonObject item = element.getAsJsonObject();
            String text = string(item, "text");
            if (text.isBlank()) continue;
            String id = string(item, "id");
            if (id.isBlank() || !ids.add(id)) {
                id = "r" + (requirements.size() + 1);
                while (!ids.add(id)) id = id + "x";
            }
            requirements.add(new AtomicRequirement(id, text, stringList(item, "depends_on")));
        }
        Set<String> validIds = requirements.stream().map(AtomicRequirement::id)
                .collect(java.util.stream.Collectors.toSet());
        return requirements.stream()
                .map(item -> new AtomicRequirement(item.id(), item.text(), item.dependsOn().stream()
                        .filter(validIds::contains).filter(id -> !id.equals(item.id())).toList()))
                .toList();
    }

    private AssignmentBatch assignRequirements(String originalMessage, RoutingContext context,
                                                List<AtomicRequirement> requirements,
                                                boolean missingOnly) throws Exception {
        String prompt = missingOnly
                ? promptBuilder.buildMissingAssignmentPrompt(context, originalMessage, requirements)
                : promptBuilder.buildAssignmentPrompt(context, originalMessage, requirements);
        JsonObject payload = new JsonObject();
        payload.addProperty("original_request", originalMessage);
        payload.add("requirements", gson.toJsonTree(requirements));
        JsonObject result = requestJson(prompt, gson.toJson(payload));
        JsonArray array = result.has("actions") && result.get("actions").isJsonArray()
                ? result.getAsJsonArray("actions") : new JsonArray();
        Map<String, AtomicRequirement> expected = new LinkedHashMap<>();
        for (AtomicRequirement requirement : requirements) expected.put(requirement.id(), requirement);
        Map<String, IntentAction> actions = new LinkedHashMap<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) continue;
            JsonObject action = element.getAsJsonObject();
            String requirementId = string(action, "requirement_id");
            AtomicRequirement requirement = expected.get(requirementId);
            if (requirement == null || actions.containsKey(requirementId)) continue;

            String modelIntent = string(action, "intent");
            normalizeAction(requirement.text(), requirement.text(), action, context.mediaContext());
            String normalizedIntent = string(action, "intent");
            if (!modelIntent.equals(normalizedIntent)) {
                System.out.println("[意图校验] " + modelIntent + " -> " + normalizedIntent
                        + "，需求=" + requirement.text());
            }
            actions.put(requirementId, new IntentAction(requirementId, requirement.text(),
                    requirement.dependsOn(), toIntentResult(action)));
        }
        List<AtomicRequirement> uncovered = missingOnly
                ? List.of() : parseRequirements(result, "missing_requirements", Set.of());
        return new AssignmentBatch(actions, uncovered);
    }

    private void appendUncoveredRequirements(List<AtomicRequirement> requirements,
                                             List<AtomicRequirement> uncovered) {
        Set<String> ids = requirements.stream().map(AtomicRequirement::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (AtomicRequirement item : uncovered) {
            if (requirements.size() >= MAX_REQUIREMENTS || item.text().isBlank()) break;
            String id = item.id();
            if (id.isBlank() || ids.contains(id)) id = "r" + (requirements.size() + 1);
            while (!ids.add(id)) id = id + "x";
            requirements.add(new AtomicRequirement(id, item.text(), item.dependsOn()));
        }
    }

    private void orderRequirementsBySource(String originalMessage, List<AtomicRequirement> requirements) {
        requirements.sort(java.util.Comparator.comparingInt(item -> {
            int index = originalMessage.indexOf(item.text());
            return index < 0 ? Integer.MAX_VALUE : index;
        }));
    }

    private List<AtomicRequirement> parseRequirements(JsonObject source, String field, Set<String> reservedIds) {
        if (!source.has(field) || !source.get(field).isJsonArray()) return List.of();
        List<AtomicRequirement> values = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>(reservedIds);
        for (JsonElement element : source.getAsJsonArray(field)) {
            if (!element.isJsonObject() || values.size() >= MAX_REQUIREMENTS) break;
            JsonObject item = element.getAsJsonObject();
            String text = string(item, "text");
            if (text.isBlank()) continue;
            String id = string(item, "id");
            if (id.isBlank() || !ids.add(id)) id = "missing" + (values.size() + 1);
            values.add(new AtomicRequirement(id, text, stringList(item, "depends_on")));
        }
        return values;
    }

    private JsonObject requestJson(String systemPrompt, String userContent) throws Exception {
        String content = requestRoute(buildRequestBody(systemPrompt, userContent, false));
        try {
            return parseJsonObject(content);
        } catch (IllegalArgumentException firstError) {
            System.err.println("[意图识别] JSON格式异常，自动重试：" + summarizeModelOutput(content));
            return parseJsonObject(requestRoute(buildRequestBody(systemPrompt, userContent, true)));
        }
    }

    private JsonObject buildRequestBody(String systemPrompt, String userContent, boolean retry) {
        JsonObject body = new JsonObject();
        body.addProperty("model", Config.ROUTER_MODEL);
        body.addProperty("temperature", 0.1);
        body.addProperty("enable_thinking", false);
        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_object");
        body.add("response_format", responseFormat);
        JsonArray messages = new JsonArray();
        messages.add(message("system", systemPrompt));
        messages.add(message("user", retry
                ? "重新处理以下输入。只输出JSON对象，不要输出解释、Markdown或思考过程：\n" + userContent
                : userContent));
        body.add("messages", messages);
        return body;
    }

    /** 兼容既有测试的请求体入口。 */
    private JsonObject buildRequestBody(String userId, String userMessage, IntentContext context,
                                        boolean includeHistory, boolean retry) {
        return buildRequestBody(promptBuilder.buildRequirementPrompt(RoutingContext.minimal(context)),
                userMessage, retry);
    }

    private String requestRoute(JsonObject body) throws Exception {
        return routeClient.request(body);
    }

    private String sendRoute(JsonObject body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(Config.API_BASE_URL))
                .timeout(Config.ROUTER_REQ_TIMEOUT)
                .header("Authorization", "Bearer " + Config.API_KEY)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("请求失败，HTTP " + response.statusCode() + "：" + response.body());
        }
        JsonObject responseJson = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonElement content = responseJson.getAsJsonArray("choices").get(0).getAsJsonObject()
                .getAsJsonObject("message").get("content");
        return content == null || content.isJsonNull() ? "" : content.getAsString();
    }

    @FunctionalInterface
    interface RouteClient {
        String request(JsonObject body) throws Exception;
    }

    private record AssignmentBatch(Map<String, IntentAction> actions,
                                   List<AtomicRequirement> uncovered) {
    }

    private JsonObject parseJsonObject(String content) {
        return responseParser.parseObject(content);
    }

    private IntentPlan fallbackChatPlan(String userMessage) {
        return new IntentPlan(List.of(new IntentAction("r1", userMessage, List.of(), IntentResult.chat())));
    }

    /** 规则层只做参数和安全约束校验，不负责在能力之间抢路由。 */
    private void normalizeAction(String userMessage, String actionText,
                                 JsonObject action, IntentContext context) {
        String modelIntent = string(action, "intent");
        String intent = normalizer.normalizeIntent(modelIntent);
        if (!intent.equals(modelIntent)) {
            action.addProperty("intent", "chat");
            clearOutputFields(action);
            return;
        }

        boolean imageCreation = IntentPolicy.isExplicitImageCreation(userMessage);
        boolean imageEdit = IntentPolicy.isExplicitImageEdit(userMessage);
        boolean fileRequest = IntentPolicy.hasExplicitFileRequest(userMessage);
        if ("generate_file".equals(intent) && !fileRequest) {
            if (imageCreation) {
                action.addProperty("intent", "draw");
                action.addProperty("en_prompt", defaultPrompt(action, actionText, userMessage));
                action.addProperty("cn_description", actionText);
            } else {
                action.addProperty("intent", "chat");
            }
            action.addProperty("output_file_type", "none");
            return;
        }
        if ("document_edit".equals(intent)) {
            if (imageEdit && (context.pendingImage() || context.hasLastImage())) {
                action.addProperty("intent", "image_action");
                action.addProperty("image_action", "edit");
                action.addProperty("image_prompt", actionText);
                action.addProperty("output_file_type", "none");
                return;
            }
            if (!context.hasDocument() || !IntentPolicy.isExplicitDocumentEdit(userMessage)) {
                action.addProperty("intent", "chat");
                action.addProperty("output_file_type", "none");
                return;
            }
        }
        if ("generate_file".equals(intent)) {
            action.addProperty("output_file_type", IntentPolicy.explicitOutputFileType(userMessage));
        } else if (!"task_plan".equals(intent)) {
            action.addProperty("output_file_type", "none");
        }
        if ("draw".equals(string(action, "intent"))) {
            if (string(action, "en_prompt").isBlank()) {
                action.addProperty("en_prompt", defaultPrompt(action, actionText, userMessage));
            }
            if (string(action, "cn_description").isBlank()) action.addProperty("cn_description", actionText);
            if (!Set.of("1024x1024", "768x1024", "1024x576").contains(string(action, "image_size"))) {
                action.addProperty("image_size", "none");
            }
        }
        if ("nearby_food".equals(intent) && !isNearbyDiningRequest(userMessage)
                && !IntentPolicy.isExplicitLocationRememberRequest(userMessage)) {
            action.addProperty("intent", "chat");
            action.addProperty("nearby_location", "");
            action.addProperty("nearby_action", "search");
            action.addProperty("meal_keyword", "");
            return;
        }
        if ("food_order".equals(intent) && isNearbyDiningRequest(userMessage)) {
            action.addProperty("intent", "nearby_food");
            action.addProperty("nearby_action", "search");
            String restaurant = string(action, "food_order_restaurants");
            action.addProperty("meal_keyword", restaurant.isBlank()
                    ? inferNearbyFoodKeyword(actionText) : restaurant);
            intent = "nearby_food";
        }
        if ("nearby_food".equals(intent)) {
            String keyword = string(action, "meal_keyword");
            if (isGenericNearbyFoodQuery(actionText) || isGenericNearbyFoodKeyword(keyword)) {
                keyword = "";
            } else if (keyword.isBlank()) {
                keyword = inferNearbyFoodKeyword(actionText);
            }
            action.addProperty("meal_keyword", keyword);
            action.addProperty("nearby_action", "search");
        }
    }

    private IntentResult toIntentResult(JsonObject result) {
        return new IntentResult(
                string(result, "intent"), string(result, "en_prompt"), string(result, "cn_description"),
                defaultString(result, "image_size", "none"), defaultString(result, "reply_mode", "keep"),
                defaultString(result, "voice_style", "default"), string(result, "persona"),
                defaultString(result, "image_action", "none"), string(result, "image_prompt"),
                defaultString(result, "audio_source", "any"), integer(result, "audio_index", 1),
                defaultString(result, "document_action", "none"),
                defaultString(result, "output_file_type", "none"), string(result, "weather_location"),
                defaultString(result, "weather_day", "today"), string(result, "plan_goal"),
                string(result, "plan_deadline"), string(result, "plan_available_time"),
                string(result, "calculation_operation"), string(result, "calculation_left"),
                string(result, "calculation_right"), string(result, "calculation_quantity"),
                string(result, "calculation_unit_price"), string(result, "calculation_discount_percent"),
                string(result, "travel_origin"), string(result, "travel_destination"),
                stringList(result, "travel_stops"), string(result, "origin_city"),
                string(result, "destination_city"), string(result, "travel_departure_time"),
                integer(result, "time_budget_minutes", 0), string(result, "meal_keyword"),
                string(result, "diet_goal"), string(result, "nearby_location"),
                defaultString(result, "nearby_action", "search"),
                defaultString(result, "calendar_action", "create"), string(result, "calendar_title"),
                string(result, "calendar_time"), defaultString(result, "calendar_recurrence", "none"),
                integer(result, "calendar_reminder_minutes", 0),
                defaultString(result, "calendar_time_type", "auto"),
                longInteger(result, "calendar_time_amount", 0), string(result, "calendar_time_unit"),
                integer(result, "calendar_lead_time_seconds", 0), string(result, "bilibili_query"),
                defaultString(result, "bilibili_category", "video"), string(result, "media_query"),
                defaultString(result, "media_category", "music"),
                defaultString(result, "email_action", "unread"), string(result, "email_keyword"),
                string(result, "food_order_restaurants"));
    }

    static boolean isLearningPlanRequest(String text) {
        return text != null && text.matches(".*(学习|学一下|学会|备考|课程).*")
                && text.matches(".*(计划|规划|安排|预计|天|周|月).*");
    }

    static boolean isBilibiliMediaRequest(String text) {
        if (text == null || text.isBlank()) return false;
        return text.matches(".*(哔哩哔哩|B站|b站).*(搜|找|看|听|播放|课程|视频).*")
                || text.matches(".*(想|要|帮我|给我).*(看|追).*(剧|电影|番|动漫|视频).*")
                || text.matches(".*(想|要|帮我|给我).*(听|播放).*(歌|音乐|歌曲).*");
    }

    static boolean isMediaKnowledgeRequest(String text) {
        if (text == null || text.isBlank()) return false;
        return text.matches(".*(查|查询|介绍|资料|了解).*(动漫|动画|番剧|漫画|歌手|歌曲|专辑|歌词).*")
                || text.matches(".*(歌词|歌手资料|专辑资料|动漫资料|番剧资料).*");
    }

    static boolean isEmailRequest(String text) {
        return text != null && text.matches(".*(邮箱|邮件|未读邮件|重要邮件).*")
                && text.matches(".*(查|查询|看看|有什么|总结|搜索|找|未读|重要).*");
    }

    static String inferMediaCategory(String text) {
        if (text != null && text.contains("歌词")) return "lyrics";
        if (text != null && text.matches(".*(动漫|动画|番剧|漫画).*")) return "anime";
        return "music";
    }

    static String inferMediaQuery(String text) {
        if (text == null) return "";
        return text.replaceFirst("^(请)?(帮我)?(查|查询|搜索|找|介绍|了解)(一下)?", "")
                .replaceAll("(的)?(资料|信息|歌词|歌手|歌曲|专辑|动漫|动画|番剧|漫画)", " ")
                .replaceAll("[《》‘’“”\"，,。？?]", " ").replaceAll("\\s+", " ").trim();
    }

    static String inferEmailAction(String text) {
        if (text != null && text.matches(".*(重要|需要回复|紧急).*")) return "important";
        if (text != null && text.matches(".*(搜索|查找|找一下|谁发的|发来的).*")) return "search";
        return "unread";
    }

    static String inferBilibiliCategory(String text) {
        if (text != null && text.matches(".*(歌|音乐|歌曲|听|播放).*")) return "music";
        if (text != null && text.matches(".*(剧|电影|番|动漫|追剧).*")) return "series";
        if (text != null && text.matches(".*(学习|课程|教程|备考|知识).*")) return "study";
        return "video";
    }

    static String inferBilibiliQuery(String text, String category) {
        String query = text == null ? "" : text.trim();
        query = query.replaceAll("^(请)?(帮我|给我)?(在)?(哔哩哔哩|B站|b站)?", "")
                .replaceAll("(帮我完成|制定|生成|做)(一份|一个)?(学习)?计划", "")
                .replaceAll("预计.{0,12}(天|周|月)(左右)?", "")
                .replaceAll("[，,。？?]", " ").replaceAll("\\s+", " ").trim();
        return switch (category) {
            case "music" -> {
                String value = query.replaceAll("^(我)?(想|要)?(听|播放)(一下)?", "")
                        .replaceAll("的?(歌|音乐|歌曲)$", "").trim();
                yield value.isBlank() ? "热门音乐" : value + " 歌曲";
            }
            case "series" -> {
                String value = query.replaceAll("^(我)?(想|要)?(看|追)(一下)?", "").trim();
                yield value.isBlank() || "剧".equals(value) ? "热门电视剧" : value;
            }
            case "study" -> {
                String value = query.replaceAll("^(我)?(想|要)?(学习|学一下)", "")
                        .replaceAll("(学习)?计划$", "").trim();
                yield value.isBlank() ? "系统学习课程" : value + " 系统课程";
            }
            default -> query.isBlank() ? "热门视频" : query;
        };
    }

    static boolean isNearbyDiningRequest(String text) {
        return IntentPolicy.isNearbyDiningRequest(text);
    }

    static String inferNearbyFoodKeyword(String text) {
        if (text == null || text.isBlank() || isGenericNearbyFoodQuery(text)) return "";
        if (!text.matches(".*(想吃|想喝|找|有没有|附近有).*")) return "";
        String value = text.replaceFirst("^.*?(想吃|想喝|找|有没有|附近有)", "")
                .replaceFirst("^(附近的?|周边的?)", "")
                .replaceAll("(了|呢|吗|呀|啊|附近的?|附近有吗)$", "")
                .replaceAll("[，,。？?！!]", " ").replaceAll("\\s+", " ").trim();
        return value.length() > 30 ? value.substring(0, 30).trim() : value;
    }

    static boolean isGenericNearbyFoodQuery(String text) {
        if (text == null || text.isBlank()) return false;
        String normalized = text.replaceAll("[，,。？?！!\\s]", "");
        return normalized.matches(".*(?:附近|周边).*(?:有什么好吃的?|有啥好吃的?|吃什么|"
                + "推荐(?:点|些)?(?:好吃的|餐厅|美食|吃的)).*");
    }

    static boolean isGenericNearbyFoodKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) return false;
        String normalized = keyword.replaceAll("[，,。？?！!\\s]", "");
        return normalized.matches("(?:什么好吃的?|有啥好吃的?|好吃的?|吃什么|"
                + "附近美食|附近餐厅|美食|餐厅|饭店|推荐)");
    }

    private void clearOutputFields(JsonObject action) {
        action.addProperty("output_file_type", "none");
        action.addProperty("image_action", "none");
        action.addProperty("image_size", "none");
    }

    private String defaultPrompt(JsonObject action, String actionText, String userMessage) {
        String prompt = string(action, "en_prompt");
        return !prompt.isBlank() ? prompt : (actionText == null || actionText.isBlank() ? userMessage : actionText);
    }

    private static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content == null ? "" : content);
        return message;
    }

    private String summarizeModelOutput(String content) {
        String summary = content == null ? "<null>" : content.replace('\n', ' ').replace('\r', ' ').trim();
        return summary.length() <= 300 ? summary : summary.substring(0, 300) + "...";
    }

    private String string(JsonObject object, String name) {
        return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : "";
    }

    private String defaultString(JsonObject object, String name, String defaultValue) {
        String value = string(object, name);
        return value.isBlank() ? defaultValue : value;
    }

    private List<String> stringList(JsonObject object, String name) {
        if (!object.has(name) || !object.get(name).isJsonArray()) return List.of();
        List<String> values = new ArrayList<>();
        for (JsonElement element : object.getAsJsonArray(name)) {
            String value = element.getAsString().trim();
            if (!value.isBlank()) values.add(value);
        }
        return List.copyOf(values);
    }

    private int integer(JsonObject object, String name, int defaultValue) {
        try {
            return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsInt() : defaultValue;
        } catch (RuntimeException ignored) {
            return defaultValue;
        }
    }

    private long longInteger(JsonObject object, String name, long defaultValue) {
        try {
            return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsLong() : defaultValue;
        } catch (RuntimeException ignored) {
            return defaultValue;
        }
    }
}
