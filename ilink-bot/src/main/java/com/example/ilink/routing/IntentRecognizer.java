package com.example.ilink.routing;

import com.example.ilink.config.Config;
import com.example.ilink.conversation.ChatHistoryStore;
import com.example.ilink.conversation.UserSessionStore;
import com.example.ilink.feature.memory.MemoryService;
import com.example.ilink.feature.persona.Personas;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;

import java.io.StringReader;
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

    private static final Set<String> ALLOWED_INTENTS = Set.of(
            "chat", "draw", "persona_switch", "audio_transcribe", "image_action", "draw_size",
            "document_summary", "document_question", "generate_file", "document_edit", "weather",
            "task_plan", "plan_adjust", "plan_progress", "calculator", "expense_split",
            "deadline_countdown", "travel_plan", "taxi_trip", "diet_plan", "nearby_food", "calendar_event",
            "planning_capabilities", "bilibili_search", "media_lookup", "email_query", "food_order");

    private final HttpClient httpClient;
    private final Gson gson = new Gson();
    private final ChatHistoryStore history;
    private final MemoryService memoryService;
    private final UserSessionStore sessions;

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
        system.addProperty("content", buildSystemPrompt(userId, context));
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
        String intent = string(action, "intent");
        if (!ALLOWED_INTENTS.contains(intent)) {
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
    private String buildSystemPrompt(String userId, IntentContext context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你只负责把用户请求拆成可执行动作，不回答用户问题。必须严格依据语义判断，禁止仅凭单个词触发功能。只输出一行JSON。\n");
        prompt.append("当前状态：pending_image=").append(context.pendingImage())
                .append(", has_last_image=").append(context.hasLastImage())
                .append(", pending_draw_size=").append(context.pendingDraw())
                .append(", has_document=").append(context.hasDocument())
                .append(", pending_calendar=").append(context.pendingCalendar()).append("。\n");
        if (sessions != null) {
            String currentLocation = sessions.getCurrentLocation(userId);
            if (currentLocation != null && !currentLocation.isBlank()) {
                prompt.append("用户最近确认的当前位置：").append(currentLocation).append("。\n");
            }
        }
        if (memoryService != null) {
            String memory = memoryService.prompt(userId);
            if (!memory.isBlank()) prompt.append(memory).append('\n');
        }
        prompt.append("可选人设名称：").append(String.join("、", Personas.getAll().keySet())).append("。\n\n");

        prompt.append("多动作拆分规则：\n");
        prompt.append("- 输出actions数组。一段话有几个相互独立、需要调用不同功能完成的明确要求，就输出几个动作；最多6个。\n");
        prompt.append("- 每个动作的action_text只保留该动作对应的原始要求，不能把整段话重复填给每个动作。\n");
        prompt.append("- 保持用户表达的逻辑顺序；需要前一步结果的动作放在后面。可能要求补充地点或时间的动作也可暂停，用户确认后会自动继续。\n");
        prompt.append("- 不要把同一任务的参数错误拆开。例如‘从A到B途中吃面’是一个travel_plan动作，meal_keyword=面；"
                + "‘查天气并从A到B途中吃面’则拆成weather和travel_plan两个动作。\n");
        prompt.append("- 例如‘查杭州今天下午天气，计算100加20，再从西湖去杭州西站途中吃面’必须输出三个动作："
                + "weather、calculator、travel_plan。\n");
        prompt.append("- 只有一个要求时actions数组只放一个动作，不能为了凑数量重复拆分。\n\n");

        prompt.append("意图规则：\n");
        prompt.append("1. chat：问答、聊天、讲笑话、写作、翻译、总结、建议以及所有不属于下述功能的请求。"
                + "语音输出和音色要求只是回复形式，不会把 chat 变成 draw。"
                + "例如‘用小男孩的音色给我讲个笑话’必须是 chat、reply_mode=voice、voice_style=boy。\n");
        prompt.append("2. draw：用户明确要求生成、绘制一张新图片时使用。"
                + "必须存在创建视觉内容的明确语义；仅出现‘画面感’、‘讲故事’、‘声音’等词不能判为 draw。"
                + "将英文绘图提示写入 en_prompt，中文画面说明写入 cn_description。\n");
        prompt.append("3. persona_switch：用户明确要求切换机器人长期说话人设时使用，persona 必须从可选人设名称中原样选择。"
                + "人格切换只改变后续对话风格和默认音色，不代表本次要发送语音；因此 reply_mode 必须为 keep，voice_style 必须为 default。"
                + "若用户指定的人格不在可选列表中，仍使用 persona_switch 并将用户原话填入 persona，由程序返回可用人格列表，禁止臆造或替换为其他人格。"
                + "音色、男声、女声、温柔声音不属于人设切换。\n");
        prompt.append("4. audio_transcribe：用户明确要求获取某条历史语音的文字、转写或内容时使用。"
                + "audio_source 表示机器人、用户或任意来源；audio_index 从最新一条开始计数。\n");
        prompt.append("5. image_action：用户明确要求分析、解题或修改已发送图片时使用。"
                + "只有 pending_image=true，或用户明确指向上一张图片且 has_last_image=true 时才可使用。"
                + "image_action 选择 analyze、solve、edit 或 clarify，完整要求写入 image_prompt。\n");
        prompt.append("6. draw_size：仅当 pending_draw_size=true 且用户正在回答图片尺寸时使用。"
                + "方形对应1024x1024，竖屏对应768x1024，横屏对应1024x576。"
                + "如果用户转而提出无关请求，应按新请求判断，不要强制 draw_size。\n\n");
        prompt.append("7. document_summary：当前有文件且用户要求总结文件时使用。\n");
        prompt.append("8. document_question：当前有文件且用户根据文件内容提问时使用。\n");
        prompt.append("9. generate_file：用户明确要求把文件总结或回答整理成 PDF 或 DOCX 时使用。\n");
        prompt.append("document_action 只能是 none|summary|question；没有文件时必须为 none。"
                + "生成文件时 output_file_type 为 docx 或 pdf，否则为 none。\n\n");
        prompt.append("10. weather：用户明确查询某个城市、区县、乡镇的天气、温度、降雨或风力时使用。"
                + "weather_location 必须填写可供 Open-Meteo 检索的英文地点名，例如北京填 Beijing，上海填 Shanghai，"
                + "和平镇填 Heping；用户提供了省、市、县时也要保留这些英文行政区信息。"
                + "全天或未说明时段使用today或tomorrow；上午、下午、晚上分别使用today_morning、"
                + "today_afternoon、today_evening或对应的tomorrow前缀。"
                + "用户未说明地点时 weather_location 为空。\n\n");
        prompt.append("11. task_plan：用户要求制定学习、项目、工作或生活任务计划时使用。"
                + "plan_goal 填写最终目标，plan_deadline 只填写真正的截止日期或时刻，例如后天或3天后，"
                + "plan_available_time 填写每天或各时间段可用时间。若用户说‘我有一小时写作业’，"
                + "这不是截止时间，time_budget_minutes=60，plan_deadline 为空。"
                + "用户同时要求生成Word或PDF时仍然使用task_plan，并设置output_file_type。\n");
        prompt.append("12. plan_adjust：用户要求调整、延期、重新安排当前计划，或说明某项任务已经完成时使用。\n");
        prompt.append("13. plan_progress：用户询问当前计划完成情况、下一项任务或还剩什么时使用。\n\n");
        prompt.append("14. calculator：用户要求四则运算、百分比、折扣、总价、单位换算、汇率、进制、BMI、个税、房贷、"
                + "中文大写金额或亲戚称呼计算时使用。自然语言参数由 CalculatorService 再调用专用工具处理；"
                + "基础四则运算仍可直接使用 calculation_operation、calculation_left、calculation_right 等字段。\n\n");
        prompt.append("15. expense_split：用户要求多人 AA、平分消费、根据不同已付款金额结算或计算谁该转给谁时使用。"
                + "例如“我们三个人吃饭300元，我付了200，张三付了100，怎么算”。\n");
        prompt.append("16. deadline_countdown：用户询问距离某个截止日期或时间还有多久、剩余几天几小时、是否超时时使用。"
                + "将明确的时间表达写入 plan_deadline，例如明天下午六点、2026-07-25 18:00。\n\n");
        prompt.append("17. travel_plan：用户给出起点、终点并要求路线、导航、出行安排或中途停留时使用。"
                + "travel_origin填写最初起点，travel_destination填写最终终点。"
                + "origin_city和destination_city只填写用户明确说出或能从明确地标确定的城市，无法确定时留空，禁止猜测。"
                + "例如‘从杭州西湖去上海外滩’填写origin_city=杭州、destination_city=上海。"
                + "用户说‘先去A、再去B、最后去C’时，A和B等中间地点按顺序写入travel_stops数组，不能忽略。"
                + "没有途经点时travel_stops必须为空数组；‘一个小时’等可用时长填入time_budget_minutes。"
                + "中途想吃面、咖啡等填入meal_keyword；明确出发时间填travel_departure_time。"
                + "即使用户说‘帮我规划’，只要核心是从A到B出行，必须是travel_plan，绝不能是task_plan。\n");
        prompt.append("17a. taxi_trip：用户明确要求打车、叫车、查询打车订单、取消打车订单或询问司机位置时使用。"
                + "新叫车填写travel_origin、travel_destination、origin_city、destination_city；城市从明确地标可确定时填写。"
                + "用户要求叫车时只负责进入报价和确认流程，绝不能自动确认下单。\n");
        prompt.append("18. diet_plan：用户要求饮食规划、外卖推荐、减脂餐、增肌餐或控糖餐时使用。"
                + "diet_goal 填减脂、增肌、控糖、维持体重或空字符串；不要把附近餐厅搜索判为diet_plan。\n");
        prompt.append("19. nearby_food：用户说自己在某位置、询问附近有什么好吃的、附近餐厅或附近外卖时使用。"
                + "nearby_location 填用户明确说出的地点，未重复时可留空；nearby_action 只能是remember或search；"
                + "用户指定麦当劳、肯德基、咖啡、面馆等品牌或餐品时，必须原样写入meal_keyword；"
                + "‘附近有什么好吃的’‘附近吃什么’等泛化查询的meal_keyword必须为空字符串，"
                + "禁止填写‘什么好吃的’‘吃什么’。"
                + "例如‘我现在在阿里园区，我想吃麦当劳’必须是nearby_food，meal_keyword=麦当劳，不是food_order。\n");
        prompt.append("20. calendar_event：用户创建、查询、完成、取消或延后提醒/日程时使用。"
                + "calendar_action 为create|list|complete|cancel|snooze；创建时填写calendar_title、calendar_time、"
                + "calendar_recurrence(none|daily|weekly|monthly|yearly)。"
                + "calendar_time_type为auto|relative|absolute。相对时间还要填写calendar_time_amount和calendar_time_unit(second|minute|hour|day)。"
                + "提前提醒统一换算为calendar_lead_time_seconds。‘30秒后提醒我’表示relative/30/second，绝不能填成提前30分钟；"
                + "只有‘明天8点开会，提前30分钟提醒’才把calendar_lead_time_seconds填为1800。"
                + "pending_calendar=true时，用户是在补充上一轮日历时间，仍输出calendar_event/create并只填写本轮提供的时间字段。\n");
        prompt.append("21. planning_capabilities：用户询问‘你可以帮我做什么规划’、规划功能有哪些时使用。\n\n");
        prompt.append("22. bilibili_search：用户想看剧、看电影、看视频、听歌、听音乐，或明确要求从哔哩哔哩寻找内容时使用。"
                + "bilibili_query填写适合搜索的关键词，bilibili_category只能是study、music、series或video。"
                + "例如‘我想听周杰伦的歌’填写周杰伦 歌曲/music；‘我想看剧’填写热门电视剧/series。"
                + "用户要求制定学习计划时，必须先输出task_plan，再输出bilibili_search，学习资源关键词使用课程主题加‘系统课程’。"
                + "例如‘我想学习线性代数，预计三十天，帮我完成一份计划’必须输出task_plan和bilibili_search两个动作。\n\n");
        prompt.append("23. media_lookup：用户要求查询动漫、番剧、歌手、歌曲、专辑或歌词的资料时使用。"
                + "media_query填写作品、歌手或歌曲关键词；media_category只能是anime、music或lyrics。"
                + "查询完成后程序会自动追加哔哩哔哩入口，不要再输出重复的bilibili_search动作。"
                + "例如‘查一下海贼王动漫资料’使用海贼王/anime；‘查周杰伦的专辑’使用周杰伦/music；"
                + "‘找晴天的歌词’使用晴天/lyrics。单纯‘我想听周杰伦的歌’仍使用bilibili_search。\n");
        prompt.append("24. email_query：用户查询QQ邮箱未读、重要邮件或按关键词搜索邮件时使用。"
                + "email_action只能是unread、important或search；email_keyword只在搜索指定发件人、主题或内容时填写。"
                + "例如‘我有什么未读邮件’使用unread；‘有没有重要邮件’使用important；"
                + "‘查腾讯发来的邮件’使用search并填写腾讯。\n");
        prompt.append("25. food_order：用户明确指定餐厅，并要求点外卖、点餐或获取外卖平台入口时使用。"
                + "food_order_restaurants填写餐厅名称，多个名称用逗号分隔。"
                + "用户同时提供当前位置或收货地点时，将地点写入nearby_location。"
                + "只表达‘我在某地，想吃某品牌/餐品’但没有要求下单时使用nearby_food；"
                + "只问附近有什么餐厅时仍使用nearby_food；要求营养或减脂外卖建议时仍使用diet_plan。\n\n");

        prompt.append("Document rules: when has_document=true, use document_summary for summarizing, document_question for questions, document_edit when the user asks to modify, rewrite, delete, add, or correct the current document, and generate_file when the user asks for a PDF or DOCX output. document_action must be none, summary, question, or edit. output_file_type must be none, docx, or pdf.\n");
        prompt.append("输出规则：\n");
        prompt.append("- 用户明确只要语音时 reply_mode=voice；明确同时要文字和语音时为both；要求关闭语音时为text；否则为keep。\n");
        prompt.append("- voice_style：小男孩=boy，小女孩=girl，成年男声=male，成年女声=female，温柔柔和=warm，活泼元气=lively，无要求=default。\n");
        prompt.append("- 未使用的字符串字段填空字符串，image_size填none，image_action填none，audio_source填any，audio_index填1，weather_day填today。\n");
        prompt.append("最高优先级校验示例：用户输入‘用小男孩的音色给我讲个笑话’时，"
                + "actions只能有一个chat动作，reply_mode必须为voice，voice_style必须为boy，"
                + "en_prompt和cn_description必须为空，image_size必须为none。\n");
        prompt.append("提交结果前逐项检查：音色要求是否正确写入voice_style；语音要求是否正确写入reply_mode；"
                + "没有明确生成图片要求时intent绝不能为draw。");
        prompt.append("\n输出必须是以下结构，且每个动作包含全部字段："
                + "{\"actions\":[{\"action_text\":\"当前动作对应的用户原始要求\","
                + "\"intent\":\"chat|draw|persona_switch|audio_transcribe|image_action|draw_size|document_summary|document_question|generate_file|document_edit|weather|task_plan|plan_adjust|plan_progress|calculator|expense_split|deadline_countdown|travel_plan|taxi_trip|diet_plan|nearby_food|calendar_event|planning_capabilities|bilibili_search|media_lookup|email_query|food_order\","
                + "\"en_prompt\":\"\",\"cn_description\":\"\","
                + "\"image_size\":\"none|1024x1024|768x1024|1024x576\","
                + "\"reply_mode\":\"keep|text|voice|both\","
                + "\"voice_style\":\"default|boy|girl|male|female|warm|lively\","
                + "\"persona\":\"\",\"image_action\":\"none|analyze|solve|edit|clarify\","
                + "\"image_prompt\":\"\",\"audio_source\":\"any|bot|user\",\"audio_index\":1,"
                + "\"document_action\":\"none|summary|question\",\"output_file_type\":\"none|docx|pdf\","
                + "\"weather_location\":\"\",\"weather_day\":\"today|tomorrow|today_morning|today_afternoon|today_evening|tomorrow_morning|tomorrow_afternoon|tomorrow_evening\","
                + "\"plan_goal\":\"\",\"plan_deadline\":\"\",\"plan_available_time\":\"\","
                + "\"calculation_operation\":\"add|subtract|multiply|divide|percentage|total_price\","
                + "\"calculation_left\":\"0\",\"calculation_right\":\"0\","
                + "\"calculation_quantity\":\"1\",\"calculation_unit_price\":\"0\","
                + "\"calculation_discount_percent\":\"0\","
                + "\"travel_origin\":\"\",\"travel_destination\":\"\",\"travel_stops\":[],"
                + "\"origin_city\":\"\",\"destination_city\":\"\",\"travel_departure_time\":\"\","
                + "\"time_budget_minutes\":0,\"meal_keyword\":\"\",\"diet_goal\":\"\","
                + "\"nearby_location\":\"\",\"nearby_action\":\"remember|search\","
                + "\"calendar_action\":\"create|list|complete|cancel|snooze\",\"calendar_title\":\"\","
                + "\"calendar_time\":\"\",\"calendar_recurrence\":\"none|daily|weekly|monthly|yearly\","
                + "\"calendar_reminder_minutes\":0,\"calendar_time_type\":\"auto|relative|absolute\","
                + "\"calendar_time_amount\":0,\"calendar_time_unit\":\"second|minute|hour|day\","
                + "\"calendar_lead_time_seconds\":0,\"bilibili_query\":\"\","
                + "\"bilibili_category\":\"study|music|series|video\","
                + "\"media_query\":\"\",\"media_category\":\"anime|music|lyrics\","
                + "\"email_action\":\"unread|important|search\",\"email_keyword\":\"\","
                + "\"food_order_restaurants\":\"\"}]}。");
        return prompt.toString();
    }

    /** 从模型文本中提取 JSON 对象，处理代码块和多余说明文字。 */
    private JsonObject parseJsonObject(String content) {
        String json = content.trim();
        if (json.startsWith("```")) {
            int firstLineEnd = json.indexOf('\n');
            int closingFence = json.lastIndexOf("```");
            if (firstLineEnd >= 0 && closingFence > firstLineEnd) {
                json = json.substring(firstLineEnd + 1, closingFence).trim();
            }
        }
        int firstObject = json.indexOf('{');
        int lastObject = json.lastIndexOf('}');
        if (firstObject < 0 || lastObject < firstObject) {
            throw new IllegalArgumentException("路由模型未返回 JSON 对象");
        }
        json = json.substring(firstObject, lastObject + 1);
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.LENIENT);
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) throw new IllegalArgumentException("路由模型返回的不是 JSON 对象");
            return parsed.getAsJsonObject();
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("路由模型 JSON 读取失败", error);
        }
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
