package com.example.ilink.application.agent;

import com.example.ilink.application.messaging.AgentContext;
import com.example.ilink.application.routing.BotSkill;
import com.example.ilink.application.routing.SkillRegistry;
import com.example.ilink.application.tooling.ToolContext;
import com.example.ilink.application.tooling.ToolManager;
import com.example.ilink.application.tooling.ToolResult;
import com.example.ilink.bootstrap.Config;
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
import java.util.Locale;
import java.util.Set;

/**
 * 工具调用循环：模型查看全部已注册工具和 Skills，获取工具结果后再决定下一步。
 * 不适合直接投递媒体、下单或发送文件的动作会回退给已有工作流，保证交互确认不被绕过。
 */
public final class AgentLoop {

    private static final int MAX_STEPS = 4;
    private static final int MAX_TOOL_OUTPUT_CHARS = 4_000;
    private static final Set<String> DELIVERY_KEYWORDS = Set.of("draw", "image", "document", "speech", "audio",
            "food", "order", "mail", "express");

    private final HttpClient httpClient;
    private final ToolManager tools;
    private final SkillRegistry skills;
    private final Gson gson = new Gson();

    public AgentLoop(HttpClient httpClient, ToolManager tools, SkillRegistry skills) {
        this.httpClient = httpClient;
        this.tools = tools;
        this.skills = skills;
    }

    /** 仅对明显可能需要能力调用的请求启动循环，普通聊天继续走原聊天链路。 */
    public boolean shouldAttempt(String text) {
        if (text == null || text.isBlank()) return false;
        String value = text.toLowerCase(Locale.ROOT);
        return value.matches(".*(天气|计算|换算|汇率|倒计时|画图|图片|文档|语音|计划|待办|日历|路线|打车|外卖|快递|搜索|新闻|邮箱|播放|整理|生成|转换).*" );
    }

    public Outcome run(AgentContext context, String request) {
        if (!shouldAttempt(request)) return Outcome.delegate("普通对话");
        try {
            JsonArray messages = new JsonArray();
            messages.add(message("system", systemPrompt()));
            messages.add(message("user", request));
            List<Step> steps = new ArrayList<>();

            for (int step = 0; step < MAX_STEPS; step++) {
                JsonObject response = call(messages, true);
                JsonObject assistant = response.getAsJsonArray("choices").get(0).getAsJsonObject()
                        .getAsJsonObject("message");
                JsonArray toolCalls = array(assistant, "tool_calls");
                if (toolCalls == null || toolCalls.isEmpty()) {
                    String content = string(assistant, "content");
                    if (isDelegateSignal(content)) return Outcome.delegate("模型回退到工作流");
                    return content.isBlank() ? Outcome.delegate("没有最终答复") : Outcome.completed(content, steps);
                }

                messages.add(assistant.deepCopy());
                for (JsonElement item : toolCalls) {
                    JsonObject call = item.getAsJsonObject();
                    JsonObject function = call.getAsJsonObject("function");
                    String name = string(function, "name");
                    ToolResult result = execute(context, name, string(function, "arguments"));
                    steps.add(new Step(name, result.success(), truncate(result.output())));
                    JsonObject toolMessage = message("tool", toolOutput(result));
                    toolMessage.addProperty("tool_call_id", string(call, "id"));
                    messages.add(toolMessage);
                }
            }
            String finalReply = synthesize(messages);
            return finalReply.isBlank() || isDelegateSignal(finalReply)
                    ? Outcome.delegate("工具执行完成但没有生成最终答复")
                    : Outcome.completed(finalReply, steps);
        } catch (Exception error) {
            System.err.println("[AgentLoop] " + error.getMessage());
            return Outcome.delegate("调用失败");
        }
    }

    private ToolResult execute(AgentContext context, String name, String rawArguments) {
        if ("select_skill".equals(name)) {
            try {
                String skillName = JsonParser.parseString(rawArguments).getAsJsonObject().get("skill").getAsString();
                return skills.names().contains(skillName)
                        ? ToolResult.success("已选择 Skill：" + skillName)
                        : ToolResult.failure("未知或已禁用 Skill：" + skillName);
            } catch (Exception error) {
                return ToolResult.failure("Skill 参数不合法");
            }
        }
        if (tools.find(name) == null) return ToolResult.failure("未注册工具：" + name);
        if (isDeliveryTool(name)) return ToolResult.failure("该工具需要现有工作流投递结果，请委托工作流处理。");
        try {
            JsonObject arguments = rawArguments == null || rawArguments.isBlank()
                    ? new JsonObject() : JsonParser.parseString(rawArguments).getAsJsonObject();
            return tools.execute(name, new ToolContext(context.principalId(), context.conversationId()), arguments);
        } catch (Exception error) {
            return ToolResult.failure("参数不合法：" + error.getMessage());
        }
    }

    /** 工具轮次结束后只允许模型总结，避免把内部执行步骤直接当成用户答复。 */
    private String synthesize(JsonArray messages) throws Exception {
        messages.add(message("system", "工具调用已结束。不要再调用工具，只根据上面的工具结果直接回答用户。"
                + "必须给出具体结果；如果某个工具失败，要明确说明失败原因并告诉用户下一步需要补充什么。"));
        JsonObject response = call(messages, false);
        JsonObject assistant = response.getAsJsonArray("choices").get(0).getAsJsonObject()
                .getAsJsonObject("message");
        return string(assistant, "content").trim();
    }

    private JsonObject call(JsonArray messages, boolean allowTools) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("model", Config.MODEL);
        body.addProperty("temperature", 0.1);
        body.add("messages", messages);
        if (allowTools) {
            body.add("tools", toolDefinitions());
            body.addProperty("tool_choice", "auto");
        } else {
            body.addProperty("tool_choice", "none");
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(Config.API_BASE_URL))
                .timeout(Config.REQ_TIMEOUT)
                .header("Authorization", "Bearer " + Config.API_KEY)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode());
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private String systemPrompt() {
        StringBuilder prompt = new StringBuilder("你是工具调用调度器。每轮先检查上一轮工具结果是否满足用户请求；"
                + "不满足时选择下一个工具或修正参数，满足时给出简洁最终答复。"
                + "全部已注册工具均可被考虑。图片、音频、文件、下单、外发消息等需要交付或确认的操作不要直接调用，"
                + "只输出 DELEGATE 交回现有工作流。没有合适工具时也只输出 DELEGATE。"
                + "调用业务工具前，先调用 select_skill 选择最匹配的 Skill。\n可用 Skills：\n");
        for (BotSkill skill : skills.enabledSkills()) {
            prompt.append("- ").append(skill.name()).append(": ").append(skill.description()).append('\n');
        }
        return prompt.toString();
    }

    private boolean isDeliveryTool(String name) {
        String value = name.toLowerCase(Locale.ROOT);
        return DELIVERY_KEYWORDS.stream().anyMatch(value::contains);
    }

    private JsonArray toolDefinitions() {
        JsonArray definitions = tools.chatCompletionsDefinitions();
        JsonObject properties = new JsonObject();
        JsonObject skill = new JsonObject();
        skill.addProperty("type", "string");
        JsonArray names = new JsonArray();
        skills.names().forEach(names::add);
        skill.add("enum", names);
        properties.add("skill", skill);
        JsonObject parameters = new JsonObject();
        parameters.addProperty("type", "object");
        parameters.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("skill");
        parameters.add("required", required);
        parameters.addProperty("additionalProperties", false);
        JsonObject function = new JsonObject();
        function.addProperty("name", "select_skill");
        function.addProperty("description", "选择本轮任务使用的能力模块。");
        function.add("parameters", parameters);
        JsonObject definition = new JsonObject();
        definition.addProperty("type", "function");
        definition.add("function", function);
        definitions.add(definition);
        return definitions;
    }

    private static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content == null ? "" : content);
        return message;
    }

    private static JsonArray array(JsonObject source, String name) {
        return source.has(name) && source.get(name).isJsonArray() ? source.getAsJsonArray(name) : null;
    }

    private static String string(JsonObject source, String name) {
        return source != null && source.has(name) && !source.get(name).isJsonNull()
                ? source.get(name).getAsString() : "";
    }

    private static boolean isDelegateSignal(String content) {
        return content != null && "DELEGATE".equalsIgnoreCase(content.trim());
    }

    private static String toolOutput(ToolResult result) {
        return "{\"success\":" + result.success() + ",\"kind\":\"" + result.kind()
                + "\",\"output\":" + gsonQuote(truncate(result.output())) + "}";
    }

    private static String gsonQuote(String value) {
        return new Gson().toJson(value == null ? "" : value);
    }

    private static String truncate(String value) {
        if (value == null) return "";
        return value.length() <= MAX_TOOL_OUTPUT_CHARS ? value : value.substring(0, MAX_TOOL_OUTPUT_CHARS) + "...";
    }

    public record Step(String toolName, boolean success, String output) { }

    public record Outcome(boolean handled, String reply, List<Step> steps, String reason) {
        static Outcome completed(String reply, List<Step> steps) {
            return new Outcome(true, reply, List.copyOf(steps), "");
        }

        static Outcome delegate(String reason) {
            return new Outcome(false, "", List.of(), reason);
        }
    }
}
