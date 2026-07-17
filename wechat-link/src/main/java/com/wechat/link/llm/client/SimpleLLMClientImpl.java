package com.wechat.link.llm.client;

import com.wechat.link.llm.config.LLMProperties;
import com.wechat.link.llm.dto.LLMRequest;
import com.wechat.link.llm.dto.LLMResponse;
import com.wechat.link.llm.exception.LLMException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * LLM 客户端默认实现
 * <p>
 * 对接 OpenAI 兼容接口（智谱 GLM / OpenAI / DeepSeek 等均兼容此格式）。
 * 使用 WebClient 进行同步阻塞调用，适配微信消息同步回复场景。
 * </p>
 *
 * @author wechat-link
 */
@Slf4j
@Service
public class SimpleLLMClientImpl implements LLMClient {

    private final WebClient llmWebClient;
    private final LLMProperties properties;

    public SimpleLLMClientImpl(@Qualifier("llmWebClient") WebClient llmWebClient,
                               LLMProperties properties) {
        this.llmWebClient = llmWebClient;
        this.properties = properties;
    }

    @Override
    public LLMResponse chat(LLMRequest request) {
        // 校验 API Key 是否配置
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            log.error("LLM API Key 未配置，请设置环境变量 LLM_API_KEY");
            return LLMResponse.fail("LLM API Key 未配置");
        }

        try {
            log.info("发送 LLM 请求 - 用户: {}, 内容长度: {}", request.getUserId(),
                    request.getContent() != null ? request.getContent().length() : 0);

            // 构建 OpenAI 兼容的请求体
            Map<String, Object> requestBody = buildRequestBody(request);

            // 调用 LLM API
            @SuppressWarnings("unchecked")
            Map<String, Object> responseBody = llmWebClient.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(properties.getTimeout()))
                    .block();

            // 解析响应
            return parseResponse(responseBody);

        } catch (WebClientResponseException e) {
            log.error("LLM API 调用失败 - HTTP {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new LLMException("API_ERROR", "LLM 接口调用失败: " + e.getStatusCode(), e);
        } catch (Exception e) {
            if (e.getCause() instanceof java.util.concurrent.TimeoutException) {
                log.error("LLM API 调用超时", e);
                return LLMResponse.fail("LLM 响应超时，请稍后重试");
            }
            log.error("LLM 调用异常", e);
            throw new LLMException("LLM 调用异常: " + e.getMessage(), e);
        }
    }

    /**
     * 构建 OpenAI 兼容格式的请求体
     */
    private Map<String, Object> buildRequestBody(LLMRequest request) {
        // 系统消息
        Map<String, String> systemMessage = Map.of(
                "role", "system",
                "content", properties.getSystemPrompt()
        );

        // 用户消息
        Map<String, String> userMessage = Map.of(
                "role", "user",
                "content", request.getContent()
        );

        return Map.of(
                "model", properties.getModel(),
                "messages", List.of(systemMessage, userMessage),
                "max_tokens", properties.getMaxTokens(),
                "temperature", properties.getTemperature()
        );
    }

    /**
     * 解析 LLM 响应（OpenAI 兼容格式）
     */
    @SuppressWarnings("unchecked")
    private LLMResponse parseResponse(Map<String, Object> responseBody) {
        if (responseBody == null) {
            return LLMResponse.fail("LLM 返回空响应");
        }

        try {
            List<Map<String, Object>> choices = (List<Map<String, Object>>) responseBody.get("choices");
            if (choices == null || choices.isEmpty()) {
                return LLMResponse.fail("LLM 未返回有效内容");
            }

            Map<String, Object> firstChoice = choices.get(0);
            Map<String, String> message = (Map<String, String>) firstChoice.get("message");
            String content = message.get("content");

            log.info("LLM 响应成功，内容长度: {}", content != null ? content.length() : 0);
            return LLMResponse.success(content);

        } catch (Exception e) {
            log.error("解析 LLM 响应失败: {}", responseBody, e);
            return LLMResponse.fail("解析 LLM 响应失败");
        }
    }
}
