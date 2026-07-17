package com.wechat.link.llm.multimodal;

import com.wechat.link.llm.config.LLMProperties;
import com.wechat.link.llm.dto.LLMResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 文档解析器
 * <p>
 * 处理用户发送的文件消息。当前策略：
 * - 将文件名信息传给 LLM，由 AI 生成合理的回复
 * - 未来可扩展：接入 OCR / PDF 解析 / 文本提取等服务
 * </p>
 *
 * @author wechat-link
 */
@Slf4j
@Component
public class DocumentModelParser implements MultiModalParser<String> {

    private final WebClient llmWebClient;
    private final LLMProperties properties;

    public DocumentModelParser(@Qualifier("llmWebClient") WebClient llmWebClient,
                               LLMProperties properties) {
        this.llmWebClient = llmWebClient;
        this.properties = properties;
    }

    @Override
    public boolean supports(String mediaType) {
        return "FILE".equalsIgnoreCase(mediaType);
    }

    /**
     * 处理文件消息
     *
     * @param mediaData 文件名或文件相关信息
     * @return LLM 回复
     */
    @Override
    public LLMResponse parse(String mediaData) {
        if (mediaData == null || mediaData.isBlank()) {
            return LLMResponse.success("已收到文件，请问需要我如何处理？");
        }

        log.info("[DocumentModelParser] 处理文件: {}", mediaData);

        try {
            // 将文件信息传给 LLM，让 AI 根据文件名给出合理回复
            Map<String, Object> requestBody = buildFilePrompt(mediaData);

            @SuppressWarnings("unchecked")
            Map<String, Object> responseBody = llmWebClient.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(properties.getTimeout()))
                    .block();

            return parseResponse(responseBody);

        } catch (Exception e) {
            log.error("[DocumentModelParser] 文件处理失败", e);
            return LLMResponse.success("已收到文件「" + mediaData + "」，当前暂不支持文件内容解析，请问有什么其他可以帮助你的？");
        }
    }

    private Map<String, Object> buildFilePrompt(String fileName) {
        Map<String, Object> systemMessage = Map.of(
                "role", "system",
                "content", "你是一个智能微信助手。用户发送了一个文件给你，请根据文件名判断文件类型，告诉用户你已收到，并给出你能提供的帮助建议。"
        );
        Map<String, Object> userMessage = Map.of(
                "role", "user",
                "content", "我发送了一个文件：" + fileName
        );
        return Map.of(
                "model", properties.getModel(),
                "messages", List.of(systemMessage, userMessage),
                "max_tokens", properties.getMaxTokens(),
                "temperature", properties.getTemperature()
        );
    }

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
            return LLMResponse.success(message.get("content"));
        } catch (Exception e) {
            log.error("[DocumentModelParser] 解析响应失败", e);
            return LLMResponse.fail("解析响应失败");
        }
    }
}
