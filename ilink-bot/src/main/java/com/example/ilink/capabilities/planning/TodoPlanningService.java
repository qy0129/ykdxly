package com.example.ilink.capabilities.planning;

import com.example.ilink.bootstrap.Config;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.Gson;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 使用独立模型请求把一条待办消息拆成结构化任务。 */
public final class TodoPlanningService {

    private static final int MAX_ITEMS = 20;
    private static final int DEFAULT_REMINDER_MINUTES = 30;
    private static final Pattern MINUTES_PATTERN = Pattern.compile(
            "(?:提前|临近前|前)\\s*(半小时|[零一二三四五六七八九十百千万两\\d]+)\\s*(小时|分钟)?");
    private static final Pattern ABSOLUTE_YEAR_PATTERN = Pattern.compile("\\d{4}[-年]\\d{1,2}[-月]\\d{1,2}");
    private static final Pattern SUPERVISION_SCHEDULE_PATTERN = Pattern.compile(
            "(?:每天|每日|每晚|天天|每\\s*周[一二三四五六日天]?|每星期[一二三四五六日天]?|每礼拜[一二三四五六日天]?)"
                    + "\\s*(?:早上|上午|中午|下午|晚上|今晚)?\\s*"
                    + "(?:[零一二三四五六七八九十两\\d]{1,3}(?:[:：点时][零一二三四五六七八九十两\\d]{0,3})?分?)?");
    private static final Pattern SUPERVISION_TIME_PATTERN = Pattern.compile(
            "(?:早上|上午|中午|下午|晚上|今晚)\\s*"
                    + "[零一二三四五六七八九十两\\d]{1,3}(?:[:：点时][零一二三四五六七八九十两\\d]{0,3})?分?");

    private final TodoPlanClient planClient;
    private final TodoBatchParser fallbackParser;
    private final Gson gson = new Gson();

    public TodoPlanningService(HttpClient httpClient) {
        this(body -> sendRequest(httpClient, body), new TodoBatchParser());
    }

    TodoPlanningService(TodoPlanClient planClient) {
        this(planClient, new TodoBatchParser());
    }

    TodoPlanningService(TodoPlanClient planClient, TodoBatchParser fallbackParser) {
        this.planClient = planClient;
        this.fallbackParser = fallbackParser;
    }

    /** 兼容单个待办请求；正式流程会同时传入原始消息和路由出的待办子需求。 */
    public TodoPlan plan(String text) {
        return plan(text, text);
    }

    /**
     * 原始消息用于提取全局提醒/监督要求，todoText 限定模型只能从待办子需求中创建任务。
     */
    public TodoPlan plan(String originalText, String todoText) {
        String original = normalize(originalText);
        String routedTodos = normalize(todoText);
        if (routedTodos.isBlank()) routedTodos = original;
        try {
            String modelContent = planClient.request(buildRequest(original, routedTodos));
            TodoPlan result;
            try {
                result = parseModelPlan(modelContent, original, routedTodos);
            } catch (IllegalArgumentException validationError) {
                if (!isRepairableModelOutput(validationError)) throw validationError;
                System.err.println("[待办规划] 模型输出结构异常，尝试修复 JSON：" + validationError.getMessage());
                String repaired = planClient.request(buildRepairRequest(original, routedTodos, modelContent));
                result = parseModelPlan(repaired, original, routedTodos);
            }
            System.out.println("[待办规划] 模型拆分完成 items=" + result.drafts().size());
            return result;
        } catch (Exception error) {
            System.err.println("[待办规划] 模型拆分失败，使用本地兜底：" + error.getMessage());
            return fallbackPlan(original, routedTodos);
        }
    }

    private JsonObject buildRequest(String originalText, String todoText) {
        JsonObject body = new JsonObject();
        body.addProperty("model", Config.MODEL);
        body.addProperty("temperature", 0.1);
        body.addProperty("enable_thinking", false);
        body.addProperty("max_tokens", Config.TODO_PLANNER_MAX_TOKENS);
        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_object");
        body.add("response_format", responseFormat);

        JsonArray messages = new JsonArray();
        messages.add(message("system", """
                你是 Personal Executive Agent 的待办规划器，只负责把待办需求转换成结构化 JSON。
                只能输出 JSON 对象，不要 Markdown、解释或额外文字。
                只从 todo_requirements 中识别任务；original_message 只用于理解共享的提醒和监督要求。
                每个独立任务必须单独放入 items，不能把多个任务合并成一个标题，也不能按标点机械切分。
                新建待办、每条任务提前提醒、后续定期检查等说明不是任务标题。
                title 只写可执行事项，不包含时间和提醒说明；time_text 必须尽量复制用户原话中的时间表达，
                不要自行计算或编造绝对日期时间。没有时间就返回空字符串。
                reminder_minutes 为提前提醒分钟数，未说明时返回 30；supervision_enabled 表示用户是否要求后续检查。
                supervision_cadence 只保留用户原话中的监督频率和时间，例如“每天晚上十点”；
                用户没有明确频率或时间时返回空字符串，不得自行编造执行时间。
                输出格式：{"reminder_minutes":30,"supervision_enabled":false,"supervision_cadence":"",
                "items":[{"title":"学习 Python 两小时","time_text":"今晚 20:00"}]}。
                """));
        JsonObject input = new JsonObject();
        input.addProperty("original_message", originalText);
        input.addProperty("todo_requirements", todoText);
        messages.add(message("user", gson.toJson(input)));
        body.add("messages", messages);
        return body;
    }

    private JsonObject buildRepairRequest(String originalText, String todoText, String modelContent) {
        JsonObject body = new JsonObject();
        body.addProperty("model", Config.MODEL);
        body.addProperty("temperature", 0);
        body.addProperty("enable_thinking", false);
        body.addProperty("max_tokens", Config.TODO_PLANNER_MAX_TOKENS);
        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_object");
        body.add("response_format", responseFormat);
        JsonArray messages = new JsonArray();
        messages.add(message("system", "你是 JSON 修复器。只能输出合法 JSON 对象，必须包含 items 数组；不要解释、不要 Markdown。items 中每个元素必须有 title 和 time_text。"));
        JsonObject input = new JsonObject();
        input.addProperty("original_message", originalText);
        input.addProperty("todo_requirements", todoText);
        String invalid = modelContent == null ? "" : modelContent;
        input.addProperty("invalid_model_output", invalid.substring(0, Math.min(4000, invalid.length())));
        messages.add(message("user", gson.toJson(input)));
        body.add("messages", messages);
        return body;
    }

    private boolean isRepairableModelOutput(IllegalArgumentException error) {
        String message = error.getMessage();
        if (message == null) return false;
        return message.contains("JSON") || message.contains("合法的待办列表")
                || message.contains("待办条目不是对象") || message.contains("任务只有说明文字");
    }

    private TodoPlan parseModelPlan(String content, String originalText, String todoText) {
        JsonObject root = parseJsonObject(content);
        JsonArray items = root.has("items") && root.get("items").isJsonArray()
                ? root.getAsJsonArray("items") : null;
        if (items == null || items.isEmpty() || items.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("模型没有返回合法的待办列表");
        }

        LocalDateTime reference = LocalDateTime.now();
        List<TodoDraft> drafts = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            JsonElement element = items.get(index);
            if (!element.isJsonObject()) throw new IllegalArgumentException("待办条目不是对象");
            JsonObject item = element.getAsJsonObject();
            String title = string(item, "title").trim();
            if (title.isBlank() || isInstructionOnly(title)) continue;
            if (title.length() > 200) throw new IllegalArgumentException("待办标题过长");

            String timeText = string(item, "time_text").trim();
            LocalDateTime dueAt = null;
            if (!timeText.isBlank()) {
                if (hasInventedAbsoluteDate(timeText, originalText + "\n" + todoText)) {
                    throw new IllegalArgumentException("模型编造了用户未提供的绝对日期");
                }
                dueAt = DateTimeParser.parse(timeText, reference);
                dueAt = DateTimeParser.applyPeriodDefault(timeText, dueAt);
                if (dueAt == null) throw new IllegalArgumentException("无法解析待办时间：" + timeText);
            }
            drafts.add(new TodoDraft("todo_" + (drafts.size() + 1),
                    (timeText + " " + title).trim(), title, dueAt));
        }
        if (drafts.isEmpty()) throw new IllegalArgumentException("模型返回的任务只有说明文字");

        int reminderMinutes = integer(root, "reminder_minutes", DEFAULT_REMINDER_MINUTES);
        if (reminderMinutes < 0 || reminderMinutes > 10080) {
            throw new IllegalArgumentException("模型返回的提醒时间超出范围");
        }
        boolean supervisionEnabled = !declinesSupervision(originalText)
                && (bool(root, "supervision_enabled", false) || asksForSupervision(originalText));
        String cadence = supervisionEnabled ? supervisionCadence(originalText) : "";
        return new TodoPlan(drafts, reminderMinutes, supervisionEnabled, cadence, true);
    }

    private TodoPlan fallbackPlan(String originalText, String todoText) {
        List<TodoDraft> drafts = fallbackParser.parse(todoText);
        if (drafts.isEmpty() && !originalText.equals(todoText)) drafts = fallbackParser.parse(originalText);
        boolean supervisionEnabled = asksForSupervision(originalText);
        return new TodoPlan(drafts, fallbackReminderMinutes(originalText),
                supervisionEnabled, supervisionEnabled ? supervisionCadence(originalText) : "", false);
    }

    private int fallbackReminderMinutes(String text) {
        if (text.matches(".*(不提醒|无需提醒|不用提醒).*")) return 0;
        Matcher matcher = MINUTES_PATTERN.matcher(text);
        if (!matcher.find()) return DEFAULT_REMINDER_MINUTES;
        if ("半小时".equals(matcher.group(1))) return 30;
        int amount = DateTimeParser.parseChineseNumber(matcher.group(1));
        if (amount < 0) return DEFAULT_REMINDER_MINUTES;
        return "小时".equals(matcher.group(2)) ? Math.min(10080, amount * 60) : Math.min(10080, amount);
    }

    private boolean asksForSupervision(String text) {
        if (declinesSupervision(text)) return false;
        return text.matches("(?s).*(监督|复盘|定期检查|定期跟进|检查我.*完成|跟进我.*完成"
                + "|(?:每天|每日|每晚|每周|每星期|每礼拜).*(?:检查|跟进)"
                + "|(?:检查|跟进).*(?:完成情况|完成进度)).*" );
    }

    private boolean declinesSupervision(String text) {
        return text != null && text.matches("(?s).*(不用|不要|无需|不需要)"
                + "(?:再|后续|定期|每天|每日|每晚)?(?:监督|复盘|检查|跟进).*" );
    }

    private String supervisionCadence(String text) {
        String value = text == null ? "" : text;
        for (String segment : value.split("[\\n，,。；;]")) {
            if (!segment.matches(".*(监督|复盘|检查|跟进).*")) continue;
            Matcher schedule = SUPERVISION_SCHEDULE_PATTERN.matcher(segment);
            if (schedule.find()) return schedule.group().trim();
            Matcher time = SUPERVISION_TIME_PATTERN.matcher(segment);
            if (time.find()) return time.group().trim();
        }
        return "";
    }

    private boolean isInstructionOnly(String title) {
        String value = title.replaceAll("[\\s，,。；;：:]", "");
        return value.matches("^(新建|设置|安排|添加|新增|创建)(以下)?待办(事项)?$")
                || value.matches("^(每条任务|后续|之后|每天|每日|每晚|每周|每星期|每礼拜)"
                        + ".*(提醒|推送|检查|完成|跟进|复盘).*$");
    }

    private boolean hasInventedAbsoluteDate(String timeText, String sourceText) {
        Matcher matcher = ABSOLUTE_YEAR_PATTERN.matcher(timeText.replaceAll("\\s+", ""));
        String source = sourceText.replaceAll("\\s+", "");
        while (matcher.find()) {
            if (!source.contains(matcher.group())) return true;
        }
        return false;
    }

    private JsonObject parseJsonObject(String content) {
        String json = content == null ? "" : content.trim();
        if (json.startsWith("```")) {
            int firstLineEnd = json.indexOf('\n');
            int closingFence = json.lastIndexOf("```");
            if (firstLineEnd >= 0 && closingFence > firstLineEnd) {
                json = json.substring(firstLineEnd + 1, closingFence).trim();
            }
        }
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start >= 0 && end > start) json = json.substring(start, end + 1);
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private String string(JsonObject object, String name) {
        return object.has(name) && !object.get(name).isJsonNull()
                ? object.get(name).getAsString() : "";
    }

    private int integer(JsonObject object, String name, int fallback) {
        try {
            return object.has(name) && !object.get(name).isJsonNull()
                    ? object.get(name).getAsInt() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private boolean bool(JsonObject object, String name, boolean fallback) {
        try {
            return object.has(name) && !object.get(name).isJsonNull()
                    ? object.get(name).getAsBoolean() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String sendRequest(HttpClient httpClient, JsonObject body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(Config.API_BASE_URL))
                .timeout(Config.TODO_PLANNER_REQ_TIMEOUT)
                .header("Authorization", "Bearer " + Config.API_KEY)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(new Gson().toJson(body)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode() + "：" + response.body());
        }
        JsonObject responseJson = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonElement content = responseJson.getAsJsonArray("choices").get(0).getAsJsonObject()
                .getAsJsonObject("message").get("content");
        if (content == null || content.isJsonNull()) throw new IllegalStateException("模型没有返回内容");
        if (content instanceof JsonPrimitive) return content.getAsString();
        return content.toString();
    }

    @FunctionalInterface
    interface TodoPlanClient {
        String request(JsonObject body) throws Exception;
    }
}
