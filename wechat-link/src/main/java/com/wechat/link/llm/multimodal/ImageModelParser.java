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
 * 图片解析器 - 接入多模态视觉模型
 * <p>
 * 将 base64 编码的图片通过 OpenAI 兼容接口（支持 GPT-4o / GLM-4V / Qwen-VL 等）
 * 发送给视觉模型进行内容识别，返回图片描述文本。
 * </p>
 *
 * @author wechat-link
 */
@Slf4j
@Component
public class ImageModelParser implements MultiModalParser<String> {

    private final WebClient multimodalWebClient;
    private final LLMProperties properties;

    public ImageModelParser(@Qualifier("multimodalWebClient") WebClient multimodalWebClient,
                            LLMProperties properties) {
        this.multimodalWebClient = multimodalWebClient;
        this.properties = properties;
    }

    @Override
    public boolean supports(String mediaType) {
        return "IMAGE".equalsIgnoreCase(mediaType);
    }

    /**
     * 解析图片内容
     *
     * @param mediaData base64 data URI 格式的图片数据（如 data:image/jpeg;base64,xxx）
     * @return 图片描述的 LLM 响应
     */
    @Override
    public LLMResponse parse(String mediaData) {
        if (mediaData == null || mediaData.isBlank()) {
            return LLMResponse.fail("图片数据为空");
        }

        log.info("[ImageModelParser] 开始识别图片，数据长度: {}", mediaData.length());

        try {
            // 构建多模态请求体（OpenAI vision 格式）
            Map<String, Object> requestBody = buildVisionRequest(mediaData);
            LLMProperties.MultimodalConfig mm = properties.getMultimodal();
            String apiKey = mm.getApiKey() != null && !mm.getApiKey().isBlank()
                    ? mm.getApiKey() : properties.getApiKey();

            log.info("【模型调用】图像识别 - model={}", mm.getModel());

            // 调用多模态视觉接口
            @SuppressWarnings("unchecked")
            Map<String, Object> responseBody = multimodalWebClient.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(mm.getTimeout()))
                    .block();

            // 解析响应
            return parseVisionResponse(responseBody);

        } catch (Exception e) {
            log.error("[ImageModelParser] 图片识别失败", e);
            return LLMResponse.fail("图片识别失败：" + e.getMessage());
        }
    }

    /**
     * 构建多模态视觉请求（OpenAI vision 兼容格式）
     * 支持 GPT-4o / GLM-4V / Qwen-VL 等模型
     */
    private Map<String, Object> buildVisionRequest(String imageDataUri) {
        // 系统消息
        Map<String, Object> systemMessage = Map.of(
                "role", "system",
                "content", "你是一个图片识别助手，请用中文详细描述用户发送的图片内容。"
        );

        // 用户消息：包含文本提示 + 图片
        Map<String, Object> textPart = Map.of(
                "type", "text",
                "text", "请描述这张图片的内容。"
        );
        Map<String, Object> imagePart = Map.of(
                "type", "image_url",
                "image_url", Map.of("url", imageDataUri)
        );
        Map<String, Object> userMessage = Map.of(
                "role", "user",
                "content", List.of(textPart, imagePart)
        );

        return Map.of(
                "model", properties.getMultimodal().getModel(),
                "messages", List.of(systemMessage, userMessage),
                "max_tokens", properties.getMultimodal().getMaxTokens()
        );
    }

    /**
     * 解析视觉模型响应
     */
    @SuppressWarnings("unchecked")
    private LLMResponse parseVisionResponse(Map<String, Object> responseBody) {
        if (responseBody == null) {
            return LLMResponse.fail("视觉模型返回空响应");
        }
        try {
            List<Map<String, Object>> choices = (List<Map<String, Object>>) responseBody.get("choices");
            if (choices == null || choices.isEmpty()) {
                return LLMResponse.fail("视觉模型未返回有效内容");
            }
            Map<String, Object> firstChoice = choices.get(0);
            Map<String, String> message = (Map<String, String>) firstChoice.get("message");
            String content = message.get("content");
            log.info("[ImageModelParser] 图片识别成功，内容长度: {}", content != null ? content.length() : 0);
            return LLMResponse.success(content);
        } catch (Exception e) {
            log.error("[ImageModelParser] 解析视觉模型响应失败", e);
            return LLMResponse.fail("解析图片识别结果失败");
        }
    }
}
