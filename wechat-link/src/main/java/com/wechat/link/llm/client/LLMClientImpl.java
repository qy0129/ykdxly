package com.wechat.link.llm.client;

import com.wechat.link.llm.config.LLMProperties;
import com.wechat.link.llm.dto.ChatMessage;
import com.wechat.link.llm.dto.LLMRequest;
import com.wechat.link.llm.dto.LLMResponse;
import com.wechat.link.llm.exception.LLMException;
import com.wechat.link.llm.memory.MultiModalMemoryOptimizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * LLM 客户端默认实现
 * <p>
 * 对接 OpenAI 兼容接口（智谱 GLM / OpenAI / DeepSeek 等均兼容此格式）。
 * 使用 WebClient 进行同步阻塞调用，适配微信消息同步回复场景。
 * <p>
 * 通过 MultiModalMemoryOptimizer 获取衰减优化后的历史记忆：
 * - 近期消息保留完整图片 Base64（方案 1：全量重传）
 * - 远期消息图片降级为文字占位符（方案 3：降级描述）
 * <p>
 * 注意：记忆写入由 LLMMessageFacade 统一管理，本类仅负责读取和发送。
 * </p>
 *
 * @author wechat-link
 */
@Slf4j
@Service
public class LLMClientImpl implements LLMClient {

    private final WebClient llmWebClient;
    private final WebClient multimodalWebClient;
    private final LLMProperties properties;
    private final MultiModalMemoryOptimizer memoryOptimizer;

    public LLMClientImpl(@Qualifier("llmWebClient") WebClient llmWebClient,
                         @Qualifier("multimodalWebClient") WebClient multimodalWebClient,
                         LLMProperties properties,
                         MultiModalMemoryOptimizer memoryOptimizer) {
        this.llmWebClient = llmWebClient;
        this.multimodalWebClient = multimodalWebClient;
        this.properties = properties;
        this.memoryOptimizer = memoryOptimizer;
    }

    @Override
    public LLMResponse chat(LLMRequest request) {
        // 校验 API Key 是否配置
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            log.error("LLM API Key 未配置，请设置环境变量 LLM_API_KEY");
            return LLMResponse.fail("LLM API Key 未配置");
        }

        try {
            log.info("【模型调用】Chat Completions - model={}, 用户={}, 内容长度={}",
                    properties.getModel(), request.getUserId(),
                    request.getContent() != null ? request.getContent().length() : 0);

            // 构建当前用户消息
            ChatMessage currentUserMessage = buildCurrentMessage(request);

            // 组装完整的 messages 列表：system + 优化后的历史记忆 + 当前消息
            List<ChatMessage> messages = assembleMessages(request.getUserId(), currentUserMessage);

            // 构建请求体（ChatMessage 的 content 为 Object，Jackson 自动序列化为 String 或数组）
            Map<String, Object> requestBody = Map.of(
                    "model", properties.getModel(),
                    "messages", messages,
                    "max_tokens", properties.getMaxTokens(),
                    "temperature", properties.getTemperature()
            );

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

    // ==================== 多模态模型调用 ====================

    @Override
    public LLMResponse multimodalChat(LLMRequest request) {
        LLMProperties.MultimodalConfig mm = properties.getMultimodal();
        String apiKey = mm.getApiKey() != null && !mm.getApiKey().isBlank()
                ? mm.getApiKey() : properties.getApiKey();

        if (apiKey == null || apiKey.isBlank()) {
            log.error("多模态 API Key 未配置");
            return LLMResponse.fail("多模态 API Key 未配置");
        }

        try {
            log.info("【模型调用】Multimodal Chat - model={}, 用户={}, 内容长度={}",
                    mm.getModel(), request.getUserId(),
                    request.getContent() != null ? request.getContent().length() : 0);

            ChatMessage currentUserMessage = buildCurrentMessage(request);
            List<ChatMessage> messages = assembleMessages(request.getUserId(), currentUserMessage);

            Map<String, Object> requestBody = Map.of(
                    "model", mm.getModel(),
                    "messages", messages,
                    "max_tokens", mm.getMaxTokens(),
                    "temperature", properties.getTemperature()
            );

            @SuppressWarnings("unchecked")
            Map<String, Object> responseBody = multimodalWebClient.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(mm.getTimeout()))
                    .block();

            return parseResponse(responseBody);

        } catch (WebClientResponseException e) {
            log.error("多模态 API 调用失败 - HTTP {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new LLMException("API_ERROR", "多模态接口调用失败: " + e.getStatusCode(), e);
        } catch (Exception e) {
            if (e.getCause() instanceof java.util.concurrent.TimeoutException) {
                log.error("多模态 API 调用超时", e);
                return LLMResponse.fail("多模态响应超时，请稍后重试");
            }
            log.error("多模态调用异常", e);
            throw new LLMException("多模态调用异常: " + e.getMessage(), e);
        }
    }

    // ==================== 消息构建 ====================

    /**
     * 根据请求构建当前用户消息
     * - 有 mediaUrl → 图文混合消息
     * - 仅文本 → 纯文本消息
     */
    private ChatMessage buildCurrentMessage(LLMRequest request) {
        if (request.getMediaUrl() != null && !request.getMediaUrl().isBlank()) {
            String text = request.getContent() != null ? request.getContent() : "请描述这张图片的内容。";
            return ChatMessage.ofTextAndImage("user", text, request.getMediaUrl());
        }
        return ChatMessage.of("user", request.getContent());
    }

    /**
     * 组装完整的 messages 列表：system + 优化后的历史记忆 + 当前消息
     * 通过 MultiModalMemoryOptimizer 获取衰减优化后的历史
     */
    private List<ChatMessage> assembleMessages(String userId, ChatMessage currentUserMessage) {
        List<ChatMessage> messages = new ArrayList<>();

        // 1. System 消息
        messages.add(ChatMessage.of("system", properties.getSystemPrompt()));

        // 2. 优化后的历史记忆（近期保留图片，远期降级为文字）
        List<ChatMessage> optimizedHistory = memoryOptimizer.optimizeAndGetHistory(userId);
        messages.addAll(optimizedHistory);

        // 3. 当前用户消息
        messages.add(currentUserMessage);

        return messages;
    }

    // ==================== 响应解析 ====================

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
