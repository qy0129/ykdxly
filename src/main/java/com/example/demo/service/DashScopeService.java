package com.example.demo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Service
public class DashScopeService {

    private static final Logger log = LoggerFactory.getLogger(DashScopeService.class);

    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");

    private static final Pattern WEATHER_PATTERN = Pattern.compile(
            ".*(天气|气温|温度|下雨|下雪|刮风|冷不冷|热不热|多少度).*");

    private final OkHttpClient httpClient;
    private final String apiKey;
    private final String apiUrl;
    private final String modelName;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired(required = false)
    private WeatherService weatherService;

    public DashScopeService(
            @Value("${dashscope.api.key}") String apiKey,
            @Value("${dashscope.api.url}") String apiUrl,
            @Value("${dashscope.model.name}") String modelName) {
        this.apiKey = apiKey;
        this.apiUrl = apiUrl;
        this.modelName = modelName;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    public String chat(String userMessage, List<String[]> history) {
        String weatherInfo = null;
        if (weatherService != null && WEATHER_PATTERN.matcher(userMessage).matches()) {
            weatherInfo = weatherService.detectAndGetWeather(userMessage);
        }

        ObjectNode body = mapper.createObjectNode();
        body.put("model", modelName);

        ArrayNode messages = mapper.createArrayNode();

        ObjectNode systemMsg = mapper.createObjectNode();
        String systemPrompt = "你是一个智能助手，请用中文友好地回答问题。结合对话历史理解上下文，回答简洁明了，一般不超过200字。不要使用表情符号，回复内容保持自然。如果你不知道答案，不要编造，直接说不知道。";
        if (weatherInfo != null) {
            systemPrompt += "\n\n当前实时天气数据：" + weatherInfo + "\n用户询问天气时，请基于以上真实数据回答。";
        }
        systemMsg.put("role", "system");
        systemMsg.put("content", systemPrompt);
        messages.add(systemMsg);

        if (history != null) {
            for (String[] turn : history) {
                ObjectNode userTurn = mapper.createObjectNode();
                userTurn.put("role", "user");
                userTurn.put("content", turn[0]);
                messages.add(userTurn);

                ObjectNode assistantTurn = mapper.createObjectNode();
                assistantTurn.put("role", "assistant");
                assistantTurn.put("content", turn[1]);
                messages.add(assistantTurn);
            }
        }

        ObjectNode userMsg = mapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        messages.add(userMsg);

        ObjectNode input = mapper.createObjectNode();
        input.set("messages", messages);
        body.set("input", input);

        ObjectNode params = mapper.createObjectNode();
        params.put("result_format", "text");
        body.set("parameters", params);

        String requestBody = body.toString();
        log.debug("DashScope 请求: {}", requestBody);

        Request request = new Request.Builder()
                .url(apiUrl)
                .header("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(requestBody, JSON_MEDIA))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String respBody = response.body() != null ? response.body().string() : "";
            log.debug("DashScope 响应: status={}, body={}", response.code(), respBody);

            if (!response.isSuccessful()) {
                log.error("DashScope API 请求失败: status={}, body={}", response.code(), respBody);
                return "抱歉，AI 服务暂时不可用，请稍后再试。";
            }
            return parseReply(respBody);
        } catch (IOException e) {
            log.error("DashScope API 调用异常: {}", e.getMessage());
            return "抱歉，网络异常，请稍后再试。";
        }
    }

    private String parseReply(String respBody) {
        try {
            JsonNode root = mapper.readTree(respBody);

            JsonNode output = root.get("output");
            if (output == null) {
                log.error("响应中没有 output 字段: {}", respBody);
                return "抱歉，我没能理解您的意思，请换个问法试试。";
            }

            JsonNode text = output.get("text");
            if (text != null && !text.asText().isBlank()) return text.asText();

            JsonNode choices = output.get("choices");
            if (choices != null && choices.isArray() && choices.size() > 0) {
                JsonNode firstChoice = choices.get(0);
                JsonNode message = firstChoice.get("message");
                if (message != null) {
                    JsonNode content = message.get("content");
                    if (content != null && !content.asText().isBlank()) return content.asText();
                }
            }

            log.error("无法从响应中提取回复内容: {}", respBody);
        } catch (Exception e) {
            log.error("解析 DashScope 响应失败: {}", e.getMessage());
        }
        return "抱歉，我没能理解您的意思，请换个问法试试。";
    }
}
