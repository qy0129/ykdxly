package com.example.ilink.feature.calculator;

import com.example.ilink.config.Config;
import com.example.ilink.tools.core.ToolContext;
import com.example.ilink.tools.core.ToolManager;
import com.example.ilink.tools.core.ToolResult;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * 计算功能的流程服务。
 *
 * <p>负责将自然语言交给模型选择工具，再通过 {@link ToolManager} 执行工具；
 * 本类不直接实现单位换算、税费或房贷公式。</p>
 */
public final class CalculatorService {

    private final HttpClient httpClient;
    private final ToolManager toolManager;
    private final Gson gson = new Gson();

    /** 注入调用模型所需的 HTTP 客户端和统一工具管理器。 */
    public CalculatorService(HttpClient httpClient, ToolManager toolManager) {
        this.httpClient = httpClient;
        this.toolManager = toolManager;
    }

    /**
     * 根据用户原话选择并执行一个计算工具，返回工具产生的文本结果。
     * 模型没有选择工具时，保留其可读回复作为兜底结果。
     */
    public String execute(String userId, String userText) {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("model", Config.ROUTER_MODEL);
            body.addProperty("temperature", 0.1);
            body.addProperty("enable_thinking", false);

            JsonArray messages = new JsonArray();
            JsonObject system = new JsonObject();
            system.addProperty("role", "system");
            system.addProperty("content", "你是一个计算器助手。当用户提出换算、计算、转换单位、汇率、个税、房贷、BMI、进制转换、中文大写金额、亲戚称呼等问题时，选择合适的工具并填入正确的参数执行。如果用户没有明确指定目标单位，根据常识选择合适的单位。必须调用工具，不要自行计算。");
            messages.add(system);

            JsonObject user = new JsonObject();
            user.addProperty("role", "user");
            user.addProperty("content", userText);
            messages.add(user);
            body.add("messages", messages);

            JsonArray tools = toolManager.chatCompletionsDefinitions();
            body.add("tools", tools);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(Config.API_BASE_URL))
                    .timeout(Config.REQ_TIMEOUT)
                    .header("Authorization", "Bearer " + Config.API_KEY)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return "计算服务暂时不可用，请稍后再试。";
            }

            JsonObject responseJson = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonObject message = responseJson.getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message");

            if (message.has("tool_calls")) {
                JsonObject functionCall = message.getAsJsonArray("tool_calls").get(0).getAsJsonObject()
                        .getAsJsonObject("function");
                String toolName = functionCall.get("name").getAsString();
                JsonObject arguments = JsonParser.parseString(functionCall.get("arguments").getAsString()).getAsJsonObject();
                ToolResult result = toolManager.execute(toolName, new ToolContext(userId), arguments);
                return result.output();
            }

            String content = message.get("content").getAsString();
            return content != null && !content.isBlank() ? content : "无法识别计算需求，请更清晰地描述你的问题。";
        } catch (Exception e) {
            System.err.println("[CalculatorService] 执行失败: " + e.getMessage());
            return "计算服务暂时不可用，请稍后再试。";
        }
    }
}
