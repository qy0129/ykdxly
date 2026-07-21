package com.wechat.link.llm.tti;

import com.wechat.link.llm.config.LLMProperties;
import com.wechat.link.llm.dto.LLMResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 文生图策略实现（适配百炼 Qwen-Image 原生 API）
 * <p>
 * Qwen-Image 不支持 OpenAI 兼容模式，必须使用 DashScope 原生 Multimodal Generation API。
 * API 路径：POST /api/v1/services/aigc/multimodal-generation/generation
 * 请求体：{@code { model, input: { messages: [{ role: "user", content: [{ text: prompt }] }] }, parameters: { size, seed } }}
 * 响应解析：{@code output.choices[0].message.content[0].image}
 * </p>
 */
@Slf4j
@Component
public class TextToImageStrategyImpl implements TextToImageStrategy {

    private final LLMProperties.DalleConfig config;
    private final WebClient webClient;

    public TextToImageStrategyImpl(LLMProperties properties) {
        this.config = properties.getTti().getDalle();
        log.info("[DALL-E] 初始化，baseUrl={}, model={}", config.getBaseUrl(), config.getModel());
        this.webClient = WebClient.builder()
                .baseUrl(config.getBaseUrl())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Override
    public String engineName() {
        return "DALLE";
    }

    @Override
    public LLMResponse generateImage(String prompt) {
        log.info("[DALL-E] 开始生图, model={}, prompt={}", config.getModel(), prompt);
        long start = System.currentTimeMillis();

        try {
            // DashScope Multimodal Generation 格式（Qwen-Image 原生 API）
            Map<String, Object> requestBody = Map.of(
                    "model", config.getModel(),
                    "input", Map.of(
                            "messages", List.of(
                                    Map.of(
                                            "role", "user",
                                            "content", List.of(Map.of("text", prompt))
                                    )
                            )
                    ),
                    "parameters", Map.of(
                            "size", config.getSize()
                    )
            );

            log.debug("[DALL-E] 请求体: {}", requestBody);

            @SuppressWarnings("unchecked")
            Map<String, Object> response = webClient.post()
                    .uri("/services/aigc/multimodal-generation/generation")
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(config.getTimeout()))
                    .block();

            List<String> urls = extractImageUrls(response);
            log.info("[DALL-E] 生图完成, 耗时 {}ms, 图片数={}", System.currentTimeMillis() - start, urls.size());

            return LLMResponse.successWithImages("图片已生成：", urls);

        } catch (WebClientResponseException e) {
            log.error("[DALL-E] 生图失败, HTTP {}, 响应: {}, 耗时 {}ms",
                    e.getStatusCode(), e.getResponseBodyAsString(), System.currentTimeMillis() - start);
            return LLMResponse.fail("DALL-E 生图失败：" + e.getStatusCode() + " - " + e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("[DALL-E] 生图失败, 耗时 {}ms", System.currentTimeMillis() - start, e);
            return LLMResponse.fail("DALL-E 生图失败：" + e.getMessage());
        }
    }

    @Override
    public LLMResponse editImage(String prompt, byte[] imageBytes) {
        log.info("[DALL-E] 开始图编辑, model={}, prompt={}, 图片大小={}KB",
                config.getModel(), prompt, imageBytes.length / 1024);
        long start = System.currentTimeMillis();

        try {
            String base64Image = Base64.getEncoder().encodeToString(imageBytes);
            String dataUri = "data:image/jpeg;base64," + base64Image;

            // DashScope Multimodal Generation 格式：传入原图 + 编辑描述
            List<Map<String, Object>> content = new ArrayList<>();
            content.add(Map.of("image", dataUri));
            content.add(Map.of("text", prompt));

            Map<String, Object> requestBody = Map.of(
                    "model", config.getModel(),
                    "input", Map.of(
                            "messages", List.of(
                                    Map.of(
                                            "role", "user",
                                            "content", content
                                    )
                            )
                    ),
                    "parameters", Map.of(
                            "size", config.getSize()
                    )
            );

            log.debug("[DALL-E] 图编辑请求体: model={}, prompt={}", config.getModel(), prompt);

            @SuppressWarnings("unchecked")
            Map<String, Object> response = webClient.post()
                    .uri("/services/aigc/multimodal-generation/generation")
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(config.getTimeout()))
                    .block();

            List<String> urls = extractImageUrls(response);
            log.info("[DALL-E] 图编辑完成, 耗时 {}ms, 图片数={}",
                    System.currentTimeMillis() - start, urls.size());

            return LLMResponse.successWithImages("图片已编辑：", urls);

        } catch (WebClientResponseException e) {
            log.error("[DALL-E] 图编辑失败, HTTP {}, 响应: {}, 耗时 {}ms",
                    e.getStatusCode(), e.getResponseBodyAsString(), System.currentTimeMillis() - start);
            return LLMResponse.fail("图编辑失败：" + e.getStatusCode() + " - " + e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("[DALL-E] 图编辑失败, 耗时 {}ms", System.currentTimeMillis() - start, e);
            return LLMResponse.fail("图编辑失败：" + e.getMessage());
        }
    }

    /**
     * 从 DashScope Multimodal Generation 响应中提取图片 URL
     * <p>
     * 响应结构：{@code { output: { choices: [{ message: { content: [{ image: "https://..." }] } }] } }}
     * </p>
     */
    @SuppressWarnings("unchecked")
    private List<String> extractImageUrls(Map<String, Object> response) {
        if (response == null) {
            throw new RuntimeException("DALL-E 响应为空");
        }
        Map<String, Object> output = (Map<String, Object>) response.get("output");
        if (output == null) {
            throw new RuntimeException("DALL-E 响应缺少 output 字段");
        }
        List<Map<String, Object>> choices = (List<Map<String, Object>>) output.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("DALL-E 响应缺少 choices 数组");
        }
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        if (message == null) {
            throw new RuntimeException("DALL-E 响应缺少 message");
        }
        List<Map<String, Object>> content = (List<Map<String, Object>>) message.get("content");
        if (content == null || content.isEmpty()) {
            throw new RuntimeException("DALL-E 响应缺少 content 数组");
        }
        String url = (String) content.get(0).get("image");
        if (url == null) {
            throw new RuntimeException("DALL-E 响应中未找到 image URL");
        }
        return Collections.singletonList(url);
    }
}
