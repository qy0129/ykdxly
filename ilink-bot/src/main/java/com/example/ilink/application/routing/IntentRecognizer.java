package com.example.ilink.application.routing;

import com.example.ilink.application.messaging.UserRequestHandler;

import com.example.ilink.bootstrap.Config;
import com.example.ilink.application.conversation.ChatHistoryStore;
import com.example.ilink.application.conversation.UserSessionStore;
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
import java.util.List;
import java.util.Set;

/**
 * 唯一的用户意图识别入口。
 *
 * <p>把用户自然语言和会话上下文发送给路由模型，并将模型返回的 JSON
 * 转换为 {@link IntentPlan}。本类只负责识别，不执行具体业务。</p>
 */
public final class IntentRecognizer {

    private final HttpClient httpClient;
    private final Gson gson = new Gson();
    private final ChatHistoryStore history;
    private final MemoryService memoryService;
    private final UserSessionStore sessions;
    private final RoutePromptBuilder promptBuilder;
    private final RouteResponseParser responseParser = new RouteResponseParser();
    private final IntentNormalizer normalizer = new IntentNormalizer(SkillRegistry.defaults());

    /** 创建意图识别器并注入 HTTP 客户端。 */
    public IntentRecognizer(HttpClient httpClient) {
        this(httpClient, null, null, null);
    }

    public IntentRecognizer(HttpClient httpClient, ChatHistoryStore history,
                            MemoryService memoryService, UserSessionStore sessions) {
        this.httpClient = httpClient;
        this.history = history;
        this.memoryService = memoryService;
        this.sessions = sessions;
        this.promptBuilder = new RoutePromptBuilder(memoryService, sessions);
    }

    /** 调用路由模型，把一段自然语言转换为一个或多个有序动作。 */
    public IntentPlan recognize(String userId, String userMessage, IntentContext context) {
        IntentPlan localCalendarPlan = localCalendarCreatePlan(userMessage);
        if (localCalendarPlan != null) return localCalendarPlan;

        // 路由模型只输出结构化意图，业务执行由 UserRequestHandler 负责。
        try {
            String content = requestRoute(buildRequestBody(userId, userMessage, context, true, false));
            JsonObject result;
            try {
                result = parseJsonObject(content);
            } catch (IllegalArgumentException firstError) {
                System.err.println("[意图识别] 首次返回格式异常，自动重试："
                        + summarizeModelOutput(content));
                content = requestRoute(buildRequestBody(userId, userMessage, context, false, true));
                result = parseJsonObject(content);
            }
            List<IntentAction> actions = new ArrayList<>();
            if (result.has("actions") && result.get("actions").isJsonArray()) {
                JsonArray actionArray = result.getAsJsonArray("actions");
                boolean hasModelDrawAction = containsIntent(actionArray, "draw");
                boolean hasModelImageAction = containsIntent(actionArray, "image_action");
                for (int index = 0; index < actionArray.size() && index < 6; index++) {
                    JsonObject action = actionArray.get(index).getAsJsonObject();
                    String actionText = string(action, "action_text");
                    String modelIntent = string(action, "intent");
                    normalizeAction(userMessage, actionText, action, context);
                    String normalizedIntent = string(action, "intent");
                    logCorrection(modelIntent, normalizedIntent, actionText.isBlank() ? userMessage : actionText);
                    if ("generate_file".equals(modelIntent) && "draw".equals(normalizedIntent)
                            && hasModelDrawAction) {
                        continue;
                    }
                    if ("document_edit".equals(modelIntent) && "image_action".equals(normalizedIntent)
                            && hasModelImageAction) {
                        continue;
                    }
                    actions.add(new IntentAction(actionText.isBlank() ? userMessage : actionText,
                            toIntentResult(action)));
                }
            } else {
                // 兼容旧版单意图 JSON，避免路由模型偶尔未按新格式返回时中断请求。
                String modelIntent = string(result, "intent");
                normalizeAction(userMessage, userMessage, result, context);
                logCorrection(modelIntent, string(result, "intent"), userMessage);
                actions.add(new IntentAction(userMessage, toIntentResult(result)));
            }
            appendLearningResources(userMessage, actions);
            return new IntentPlan(actions);
        } catch (Exception e) {
            System.err.println("[意图识别] 识别失败：" + e.getMessage());
            return fallbackChatPlan(userMessage);
        }
    }

    private JsonObject buildRequestBody(String userId, String userMessage, IntentContext context,
                                        boolean includeHistory, boolean retry) {
        JsonObject body = new JsonObject();
        body.addProperty("model", Config.ROUTER_MODEL);
        body.addProperty("temperature", 0.1);
        body.addProperty("enable_thinking", false);
        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_object");
        body.add("response_format", responseFormat);

        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", promptBuilder.build(userId, context));
        messages.add(system);
        if (includeHistory && history != null) {
            history.addHistoryMessages(messages, userId, userMessage);
        }
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", retry
                ? "重新识别以下请求。只输出一个JSON对象，不要输出解释、Markdown或思考过程：\n" + userMessage
                : userMessage);
        messages.add(user);
        body.add("messages", messages);
        return body;
    }

    private String requestRoute(JsonObject body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(Config.API_BASE_URL))
                .timeout(Config.REQ_TIMEOUT)
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

    private String summarizeModelOutput(String content) {
        String summary = content == null ? "<null>" : content.replace('\n', ' ').replace('\r', ' ').trim();
        return summary.length() <= 300 ? summary : summary.substring(0, 300) + "...";
    }

    private IntentPlan fallbackChatPlan(String userMessage) {
        JsonObject action = new JsonObject();
        action.addProperty("intent", "chat");
        return new IntentPlan(List.of(new IntentAction(userMessage, toIntentResult(action))));
    }

    /** 明确的提醒创建由本地规则直达日历，避免模型偶发的非 JSON 输出中断提醒。 */
    private IntentPlan localCalendarCreatePlan(String message) {
        if (!isExplicitCalendarCreateRequest(message)) return null;
        JsonObject action = new JsonObject();
        action.addProperty("intent", "calendar_event");
        action.addProperty("calendar_action", "create");
        action.addProperty("calendar_title", calendarTitle(message));
        action.addProperty("calendar_time", message.trim());
        action.addProperty("calendar_recurrence", calendarRecurrence(message));
        action.addProperty("calendar_time_type", "auto");
        return new IntentPlan(List.of(new IntentAction(message, toIntentResult(action))));
    }

    private static boolean isExplicitCalendarCreateRequest(String message) {
        if (message == null || message.isBlank() || !message.contains("提醒")) return false;
        return !message.matches(".*(取消|删除|完成|延后|稍后|查询|查看|列表|有什么|哪些).*" );
    }

    private static String calendarTitle(String message) {
        String title = message.trim();
        int reminder = title.lastIndexOf("提醒");
        if (reminder >= 0) title = title.substring(reminder + "提醒".length());
        title = title.replaceFirst("^(我(?:该|要|记得)?|一下(?:我)?)", "")
                .replaceFirst("^(今天|明天|后天|每天|每日|每周|每月|每年)?\\s*(上午|中午|下午|晚上|今晚|早上)?\\s*\\d{1,2}(?::\\d{2})?\\s*(点|时)?(?:半|\\d{1,2}分?)?\\s*", "")
                .replaceAll("[了吧呀啊。！!？?]+$", "")
                .trim();
        return title.isBlank() ? "日历提醒" : title;
    }

    private static String calendarRecurrence(String message) {
        if (message.contains("每天") || message.contains("每日") || message.contains("天天")) return "daily";
        if (message.contains("每周") || message.contains("每星期") || message.contains("每礼拜")) return "weekly";
        if (message.contains("每月")) return "monthly";
        if (message.contains("每年")) return "yearly";
        return "none";
    }

    /** 记录被规则层纠正的模型意图，方便复盘误判而不输出完整用户上下文。 */
    private void logCorrection(String modelIntent, String normalizedIntent, String actionText) {
        if (!modelIntent.equals(normalizedIntent)) {
            System.out.println("[意图校验] " + modelIntent + " -> " + normalizedIntent
                    + "，动作=" + actionText);
        }
    }

    /** 判断模型动作数组中是否已经包含指定意图，用于去掉纠正后的重复动作。 */
    private boolean containsIntent(JsonArray actions, String intent) {
        for (var element : actions) {
            if (element.isJsonObject() && intent.equals(string(element.getAsJsonObject(), "intent"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 模型只负责提出候选动作；文件发送等高风险行为必须再次依据用户原话校验。
     */
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

        String requestText = actionText == null || actionText.isBlank() ? userMessage : actionText;
        if ("chat".equals(intent) && isEmailRequest(requestText)) {
            action.addProperty("intent", "email_query");
            action.addProperty("email_action", inferEmailAction(requestText));
            action.addProperty("email_keyword", inferEmailKeyword(requestText));
            intent = "email_query";
        } else if ("chat".equals(intent) && isMediaKnowledgeRequest(requestText)) {
            action.addProperty("intent", "media_lookup");
            action.addProperty("media_category", inferMediaCategory(requestText));
            action.addProperty("media_query", inferMediaQuery(requestText));
            intent = "media_lookup";
        } else if ("chat".equals(intent) && isBilibiliMediaRequest(requestText)) {
            action.addProperty("intent", "bilibili_search");
            action.addProperty("bilibili_category", inferBilibiliCategory(requestText));
            action.addProperty("bilibili_query", inferBilibiliQuery(requestText,
                    string(action, "bilibili_category")));
            intent = "bilibili_search";
        } else if ("chat".equals(intent) && isNearbyDiningRequest(requestText)) {
            action.addProperty("intent", "nearby_food");
            action.addProperty("nearby_action", "search");
            action.addProperty("meal_keyword", inferNearbyFoodKeyword(requestText));
            intent = "nearby_food";
        }

        if ("generate_file".equals(intent) && !fileRequest) {
            if (imageCreation) {
                action.addProperty("intent", "draw");
                action.addProperty("en_prompt", defaultPrompt(action, actionText, userMessage));
                action.addProperty("cn_description", actionText.isBlank() ? userMessage : actionText);
            } else if (imageEdit && (context.pendingImage() || context.hasLastImage())) {
                action.addProperty("intent", "image_action");
                action.addProperty("image_action", "edit");
                action.addProperty("image_prompt", actionText.isBlank() ? userMessage : actionText);
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
                action.addProperty("image_prompt", actionText.isBlank() ? userMessage : actionText);
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
            // 文件类型只能来自用户原话，不能采用模型自行补出的 PDF/DOCX。
            action.addProperty("output_file_type", IntentPolicy.explicitOutputFileType(userMessage));
        } else if ("task_plan".equals(intent)) {
            action.addProperty("output_file_type", fileRequest
                    ? IntentPolicy.explicitOutputFileType(userMessage) : "none");
        } else {
            action.addProperty("output_file_type", "none");
        }

        if ("draw".equals(string(action, "intent"))) {
            if (string(action, "en_prompt").isBlank()) {
                action.addProperty("en_prompt", defaultPrompt(action, actionText, userMessage));
            }
            if (string(action, "cn_description").isBlank()) {
                action.addProperty("cn_description", actionText.isBlank() ? userMessage : actionText);
            }
            String imageSize = string(action, "image_size");
            if (!Set.of("1024x1024", "768x1024", "1024x576").contains(imageSize)) {
                action.addProperty("image_size", "none");
            }
        }

        if ("bilibili_search".equals(string(action, "intent"))) {
            String category = string(action, "bilibili_category");
            if (!Set.of("study", "music", "series", "video").contains(category)) {
                category = inferBilibiliCategory(requestText);
                action.addProperty("bilibili_category", category);
            }
            if (string(action, "bilibili_query").isBlank()) {
                action.addProperty("bilibili_query", inferBilibiliQuery(requestText, category));
            }
        }

        if ("media_lookup".equals(string(action, "intent"))) {
            String category = string(action, "media_category");
            if (!Set.of("anime", "music", "lyrics").contains(category)) {
                category = inferMediaCategory(requestText);
                action.addProperty("media_category", category);
            }
            if (string(action, "media_query").isBlank()) {
                action.addProperty("media_query", inferMediaQuery(requestText));
            }
        }

        if ("email_query".equals(string(action, "intent"))) {
            String emailAction = string(action, "email_action");
            if (!Set.of("unread", "important", "search").contains(emailAction)) {
                action.addProperty("email_action", inferEmailAction(requestText));
            }
            if (string(action, "email_keyword").isBlank()) {
                action.addProperty("email_keyword", inferEmailKeyword(requestText));
            }
        }

        String resolvedIntent = string(action, "intent");
        if ("food_order".equals(resolvedIntent) && isNearbyDiningRequest(requestText)) {
            action.addProperty("intent", "nearby_food");
            action.addProperty("nearby_action", "search");
            String restaurant = string(action, "food_order_restaurants");
            action.addProperty("meal_keyword", restaurant.isBlank()
                    ? inferNearbyFoodKeyword(requestText) : restaurant);
            resolvedIntent = "nearby_food";
        }
        if ("nearby_food".equals(resolvedIntent)) {
            String keyword = string(action, "meal_keyword");
            if (isGenericNearbyFoodQuery(requestText) || isGenericNearbyFoodKeyword(keyword)) {
                keyword = "";
            } else if (keyword.isBlank()) {
                keyword = inferNearbyFoodKeyword(requestText);
            }
            action.addProperty("meal_keyword", keyword);
            if (requestText.matches(".*(想吃|想喝|找|有没有|附近有|有什么好吃|吃什么|推荐).*")) {
                action.addProperty("nearby_action", "search");
            }
        }
    }

    /** 学习计划固定追加课程资源动作，避免路由模型漏掉用户没有明说的学习入口。 */
    private void appendLearningResources(String userMessage, List<IntentAction> actions) {
        if (actions.size() >= 6 || !isLearningPlanRequest(userMessage)
                || actions.stream().noneMatch(action -> "task_plan".equals(action.route().intent()))
                || actions.stream().anyMatch(action -> "bilibili_search".equals(action.route().intent()))) {
            return;
        }
        String goal = actions.stream()
                .filter(action -> "task_plan".equals(action.route().intent()))
                .map(action -> action.route().planGoal())
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(userMessage);
        JsonObject resourceAction = new JsonObject();
        resourceAction.addProperty("intent", "bilibili_search");
        resourceAction.addProperty("bilibili_category", "study");
        resourceAction.addProperty("bilibili_query", inferBilibiliQuery(goal, "study"));
        actions.add(new IntentAction("查找与学习计划配套的哔哩哔哩课程", toIntentResult(resourceAction)));
    }

    static boolean isLearningPlanRequest(String text) {
        return text != null
                && text.matches(".*(学习|学一下|学会|备考|课程).*" )
                && text.matches(".*(计划|规划|安排|预计|天|周|月).*" );
    }

    static boolean isBilibiliMediaRequest(String text) {
        if (text == null || text.isBlank()) return false;
        return text.matches(".*(哔哩哔哩|B站|b站).*(搜|找|看|听|播放|课程|视频).*" )
                || text.matches(".*(想|要|帮我|给我).*(看|追).*(剧|电影|番|动漫|视频).*" )
                || text.matches(".*(想|要|帮我|给我).*(听|播放).*(歌|音乐|歌曲).*" );
    }

    static boolean isMediaKnowledgeRequest(String text) {
        if (text == null || text.isBlank()) return false;
        return text.matches(".*(查|查询|介绍|资料|了解).*(动漫|动画|番剧|漫画|歌手|歌曲|专辑|歌词).*" )
                || text.matches(".*(歌词|歌手资料|专辑资料|动漫资料|番剧资料).*" );
    }

    static boolean isEmailRequest(String text) {
        return text != null && text.matches(".*(邮箱|邮件|未读邮件|重要邮件).*" )
                && text.matches(".*(查|查询|看看|有什么|总结|搜索|找|未读|重要).*" );
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
                .replaceAll("[《》‘’“”\"，,。？?]", " ")
                .replaceAll("\\s+", " ").trim();
    }

    static String inferEmailAction(String text) {
        if (text != null && text.matches(".*(重要|需要回复|紧急).*")) return "important";
        if (text != null && text.matches(".*(搜索|查找|找一下|谁发的|发来的).*")) return "search";
        return "unread";
    }

    static String inferEmailKeyword(String text) {
        if (text == null) return "";
        return text.replaceFirst("^(请)?(帮我)?(查|查询|搜索|查找|找|看看|总结)(一下)?", "")
                .replaceAll("(我的)?(QQ)?邮箱", "")
                .replaceAll("(最近|今天|近期)?(的)?(未读|重要|新)?邮件", "")
                .replaceAll("[，,。？?]", " ").replaceAll("\\s+", " ").trim();
    }

    static boolean isNearbyDiningRequest(String text) {
        if (text == null || text.isBlank()) return false;
        boolean hasLocation = text.matches(".*(我现在在|我在|当前位置|这附近|附近).*" );
        boolean explicitOrder = text.matches(".*(点外卖|外卖下单|下单|点餐|美团|饿了么|外卖链接).*" );
        return hasLocation && !explicitOrder
                && text.matches(".*(想吃|想喝|找|有没有|哪里有|附近有|有什么好吃|有啥好吃|吃什么|推荐.*(?:餐厅|美食|吃的)).*" );
    }

    static String inferNearbyFoodKeyword(String text) {
        if (text == null || text.isBlank()) return "";
        if (isGenericNearbyFoodQuery(text)) return "";
        if (!text.matches(".*(想吃|想喝|找|有没有|附近有).*")) return "";
        String value = text.replaceFirst("^.*?(想吃|想喝|找|有没有|附近有)", "")
                .replaceFirst("^(附近的?|周边的?)", "")
                .replaceAll("(了|呢|吗|呀|啊|附近的?|附近有吗)$", "")
                .replaceAll("[，,。？?！!]", " ")
                .replaceAll("\\s+", " ").trim();
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
                .replaceAll("[，,。？?]", " ")
                .replaceAll("\\s+", " ").trim();
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

    /** 清除不再适用于普通聊天的输出字段。 */
    private void clearOutputFields(JsonObject action) {
        action.addProperty("output_file_type", "none");
        action.addProperty("image_action", "none");
        action.addProperty("image_size", "none");
    }

    /** 绘图模型可直接理解中文，缺少英文提示词时使用用户原始要求兜底。 */
    private String defaultPrompt(JsonObject action, String actionText, String userMessage) {
        String prompt = string(action, "en_prompt");
        if (!prompt.isBlank()) return prompt;
        return actionText == null || actionText.isBlank() ? userMessage : actionText;
    }

    /** 把单个动作 JSON 转换为现有业务层能够直接使用的结构化参数。 */
    private IntentResult toIntentResult(JsonObject result) {
        return new IntentResult(
                string(result, "intent"),
                string(result, "en_prompt"),
                string(result, "cn_description"),
                defaultString(result, "image_size", "none"),
                defaultString(result, "reply_mode", "keep"),
                defaultString(result, "voice_style", "default"),
                string(result, "persona"),
                defaultString(result, "image_action", "none"),
                string(result, "image_prompt"),
                defaultString(result, "audio_source", "any"),
                integer(result, "audio_index", 1),
                defaultString(result, "document_action", "none"),
                defaultString(result, "output_file_type", "none"),
                string(result, "weather_location"),
                defaultString(result, "weather_day", "today"),
                string(result, "plan_goal"),
                string(result, "plan_deadline"),
                string(result, "plan_available_time"),
                string(result, "calculation_operation"),
                string(result, "calculation_left"),
                string(result, "calculation_right"),
                string(result, "calculation_quantity"),
                string(result, "calculation_unit_price"),
                string(result, "calculation_discount_percent"),
                string(result, "travel_origin"),
                string(result, "travel_destination"),
                stringList(result, "travel_stops"),
                string(result, "origin_city"),
                string(result, "destination_city"),
                string(result, "travel_departure_time"),
                integer(result, "time_budget_minutes", 0),
                string(result, "meal_keyword"),
                string(result, "diet_goal"),
                string(result, "nearby_location"),
                defaultString(result, "nearby_action", "search"),
                defaultString(result, "calendar_action", "create"),
                string(result, "calendar_title"),
                string(result, "calendar_time"),
                defaultString(result, "calendar_recurrence", "none"),
                integer(result, "calendar_reminder_minutes", 0),
                defaultString(result, "calendar_time_type", "auto"),
                longInteger(result, "calendar_time_amount", 0),
                string(result, "calendar_time_unit"),
                integer(result, "calendar_lead_time_seconds", 0),
                string(result, "bilibili_query"),
                defaultString(result, "bilibili_category", "video"),
                string(result, "media_query"),
                defaultString(result, "media_category", "music"),
                defaultString(result, "email_action", "unread"),
                string(result, "email_keyword"),
                string(result, "food_order_restaurants"));
    }

    /** 构造路由模型的系统提示词和当前会话状态说明。 */
    /** 从模型文本中提取 JSON 对象，处理代码块和多余说明文字。 */
    private JsonObject parseJsonObject(String content) {
        return responseParser.parseObject(content);
    }

    /** 读取可选字符串字段，模型省略时返回空字符串。 */
    private String string(JsonObject object, String name) {
        return object.has(name) && !object.get(name).isJsonNull()
                ? object.get(name).getAsString() : "";
    }

    /** 读取可选字符串字段，字段为空时也使用调用方给出的默认值。 */
    private String defaultString(JsonObject object, String name, String defaultValue) {
        String value = string(object, name);
        return value.isBlank() ? defaultValue : value;
    }

    /** 读取字符串数组字段，并忽略模型返回的空白元素。 */
    private List<String> stringList(JsonObject object, String name) {
        if (!object.has(name) || !object.get(name).isJsonArray()) return List.of();
        List<String> values = new ArrayList<>();
        for (var element : object.getAsJsonArray(name)) {
            String value = element.getAsString().trim();
            if (!value.isBlank()) values.add(value);
        }
        return List.copyOf(values);
    }

    /** 读取可选整数，模型遗漏或格式异常时使用默认值，避免路由失败影响普通聊天。 */
    private int integer(JsonObject object, String name, int defaultValue) {
        try {
            return object.has(name) && !object.get(name).isJsonNull()
                    ? object.get(name).getAsInt() : defaultValue;
        } catch (RuntimeException ignored) {
            return defaultValue;
        }
    }

    private long longInteger(JsonObject object, String name, long defaultValue) {
        try {
            return object.has(name) && !object.get(name).isJsonNull()
                    ? object.get(name).getAsLong() : defaultValue;
        } catch (RuntimeException ignored) {
            return defaultValue;
        }
    }


}
