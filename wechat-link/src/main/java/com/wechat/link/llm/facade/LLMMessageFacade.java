package com.wechat.link.llm.facade;

import com.wechat.link.file.service.TikaDocumentExtractor;
import com.wechat.link.llm.client.LLMClient;
import com.wechat.link.llm.config.LLMProperties;
import com.wechat.link.llm.dto.ChatMessage;
import com.wechat.link.llm.dto.ContentItem;
import com.wechat.link.llm.dto.LLMRequest;
import com.wechat.link.llm.dto.LLMResponse;
import com.wechat.link.llm.exception.LLMException;
import com.wechat.link.llm.memory.ActiveImageCacheManager;
import com.wechat.link.llm.memory.ChatMemoryManager;
import com.wechat.link.llm.multimodal.MultiModalParser;
import com.wechat.link.llm.router.LLMRouterImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.List;

/**
 * LLM 消息调度门面
 * <p>
 * 微信消息流转到 LLM 的唯一入口，协同以下组件：
 * - ActiveImageCacheManager：收到图片时更新激活图缓存
 * - AdaptiveLLMRouterImpl：智能路由（支持语音 TTS 触发 + ASR 兜底）
 * - ChatMemoryManager：对话完成后将问答存入多模态记忆（含语音）
 * - MultiModalParser：图片/语音/文件的专精解析器
 * <p>
 * 语音流程：
 * 1. 微信 ASR 成功（voice.getText() != null）→ 正常文本路由 + 标记 NEED_VOICE_REPLY
 * 2. 微信 ASR 失败（voice.getText() == null）→ 返回特殊信号触发上游 ASR 兜底
 * </p>
 */
@Slf4j
@Service
public class LLMMessageFacade {

    private final LLMClient llmClient;
    private final LLMRouterImpl llmRouter;
    private final List<MultiModalParser<String>> multiModalParsers;
    private final ActiveImageCacheManager imageCacheManager;
    private final ChatMemoryManager chatMemoryManager;
    private final LLMProperties properties;
    private final TikaDocumentExtractor tikaDocumentExtractor;

    public LLMMessageFacade(LLMClient llmClient,
                            LLMRouterImpl llmRouter,
                            List<MultiModalParser<String>> multiModalParsers,
                            ActiveImageCacheManager imageCacheManager,
                            ChatMemoryManager chatMemoryManager,
                            LLMProperties properties,
                            TikaDocumentExtractor tikaDocumentExtractor) {
        this.llmClient = llmClient;
        this.llmRouter = llmRouter;
        this.multiModalParsers = multiModalParsers;
        this.imageCacheManager = imageCacheManager;
        this.chatMemoryManager = chatMemoryManager;
        this.properties = properties;
        this.tikaDocumentExtractor = tikaDocumentExtractor;
    }

    /**
     * 处理消息的统一入口
     */
    public LLMResponse handleMessage(LLMRequest request) {
        log.info("收到消息分发请求 - 用户: {}, 类型: {}", request.getUserId(), request.getMessageType());

        try {
            String messageType = request.getMessageType();

            // 文本消息 → 走智能路由器（自动判断：纯对话 / 文生图 / 图片编辑 / 深度思考 / 语音回复）
            if ("TEXT".equalsIgnoreCase(messageType)) {
                return handleTextMessage(request, "TEXT");
            }

            // 图片消息 → 更新激活图缓存 + 走多模态解析器
            if ("IMAGE".equalsIgnoreCase(messageType)) {
                return handleImageMessage(request);
            }

            // 语音消息
            if ("VOICE".equalsIgnoreCase(messageType)) {
                return handleVoiceMessage(request);
            }

            // 文件及其他 → 走多模态解析器
            return handleMultiModalMessage(request);

        } catch (LLMException e) {
            log.error("LLM 处理异常 - 用户: {}, 错误码: {}, 信息: {}",
                    request.getUserId(), e.getErrorCode(), e.getMessage());
            return LLMResponse.fail("抱歉，AI 处理出现问题：" + e.getMessage());
        } catch (Exception e) {
            log.error("消息处理未知异常 - 用户: {}", request.getUserId(), e);
            return LLMResponse.fail("系统繁忙，请稍后再试。");
        }
    }

    // ==================== 文本消息处理 ====================

    /**
     * 处理文本消息：走路由器（带消息来源标记），完成后存入记忆
     *
     * @param request    请求
     * @param sourceType 消息来源：TEXT / VOICE
     */
    private LLMResponse handleTextMessage(LLMRequest request, String sourceType) {
        String userId = request.getUserId();
        String content = request.getContent();

        // 路由器分流（支持语音 TTS 触发 + 来源标记）
        LLMResponse response = llmRouter.routeWithSource(userId, content, sourceType);

        // 成功后存入记忆（纯文本）
        if ("SUCCESS".equals(response.getStatus()) && response.getContent() != null) {
            chatMemoryManager.saveMessage(userId, "user", content);
            chatMemoryManager.saveMessage(userId, "assistant", response.getContent());
        }

        return response;
    }

    // ==================== 图片消息处理 ====================

    /**
     * 处理图片消息：
     * 1. 更新激活图缓存（方案 2 支撑）
     * 2. 走视觉模型解析器识别图片
     * 3. 将图文混合消息存入多模态记忆
     */
    private LLMResponse handleImageMessage(LLMRequest request) {
        String userId = request.getUserId();
        String mediaUrl = request.getMediaUrl();

        updateImageCache(userId, mediaUrl);

        LLMResponse response = handleMultiModalMessage(request);

        if ("SUCCESS".equals(response.getStatus()) && response.getContent() != null) {
            String userText = request.getContent() != null ? request.getContent() : "请描述这张图片的内容。";
            List<ContentItem> userItems = List.of(
                    ContentItem.ofText(userText),
                    ContentItem.ofImageUrl(mediaUrl)
            );
            chatMemoryManager.saveMessage(userId, "user", userItems);
            chatMemoryManager.saveMessage(userId, "assistant", response.getContent());
        }

        return response;
    }

    // ==================== 语音消息处理 ====================

    /**
     * 处理语音消息：
     * - 有转文字（微信 ASR 成功）→ 走正常文本路由 + 来源标记 VOICE（触发 NEED_VOICE_REPLY）
     * - 无转文字（微信 ASR 失败）→ 直接返回提示信息，不再触发远端 ASR 兜底
     */
    private LLMResponse handleVoiceMessage(LLMRequest request) {
        String userId = request.getUserId();
        String content = request.getContent();

        if (content != null && !content.isBlank()) {
            // 微信 ASR 成功 → 走路由器，标记来源为 VOICE
            log.info("[Voice Router] 微信原生 ASR 识别成功，文本: {}",
                    content.length() > 50 ? content.substring(0, 50) + "..." : content);
            return handleTextMessage(request, "VOICE");
        }

        // 微信 ASR 失败 → 直接提示用户
        log.warn("[Voice Router] 微信 ASR 识别失败/返回为空，直接提示用户发送文字");
        return LLMResponse.fail("语音内容为空，请发送文字消息。");
    }

    /**
     * 从 base64 data URI 中提取图片字节并更新激活图缓存
     */
    private void updateImageCache(String userId, String mediaUrl) {
        if (mediaUrl == null || !mediaUrl.startsWith("data:image")) {
            return;
        }
        try {
            int commaIndex = mediaUrl.indexOf(',');
            if (commaIndex > 0) {
                String base64Data = mediaUrl.substring(commaIndex + 1);
                byte[] imageBytes = Base64.getDecoder().decode(base64Data);
                imageCacheManager.updateCache(userId, imageBytes);
            }
        } catch (Exception e) {
            log.warn("[Facade] 更新激活图缓存失败 user={}", userId, e);
        }
    }

    // ==================== 文档读取处理（DOCUMENT_READ） ====================

    /**
     * 处理文档读取请求
     * <p>
     * Tika 提取全量文本 → 构建 Prompt → 调 DeepSeek 分析 → 全量+摘要双轨记忆：
     * - 全量内容存入 ChatMessage（documentRead 标记），近期对话保留完整供 LLM 引用
     * - 远期自动经 MultiModalMemoryOptimizer 降级为摘要占位符（防 Token 爆炸）
     *
     * @param fileName   文件名
     * @param fileBytes  文件字节（Tika 需要完整数据）
     * @return LLM 分析回复
     */
    public LLMResponse handleDocumentRead(String userId, String fileName, byte[] fileBytes) {
        log.info("[Facade] 文档读取 [{}] 文件名={}, 大小={}KB", userId, fileName, fileBytes.length / 1024);

        try {
            // 1. Tika 提取文本
            String extractedText = tikaDocumentExtractor.extractText(fileBytes, fileName);

            if (extractedText.isBlank()) {
                return LLMResponse.success("已收到文件「" + fileName + "」，但未能提取到有效文本内容。"
                        + "请确认文件不是纯图片扫描件（暂不支持 OCR）或加密文档。");
            }

            // 2. 构建 Prompt 发给 DeepSeek
            String prompt = "用户发送了一个文件「" + fileName + "」，以下是文件内容：\n\n"
                    + extractedText + "\n\n请根据文件内容给用户回复，简要概括文档内容并回答用户可能关心的问题。";

            LLMRequest request = LLMRequest.builder()
                    .userId(userId)
                    .sessionId(userId)
                    .content(prompt)
                    .messageType("TEXT")
                    .build();
            LLMResponse response = llmClient.chat(request);

            // 3. 双轨记忆：全量内容 + 降级摘要（供远期衰减使用）
            int summaryLen = properties.getDocument().getMemoryPlaceholderLength();
            String summary = "用户上传了文档「" + fileName + "」";
            if (extractedText.length() > summaryLen) {
                summary += "（内容已阅读，共" + extractedText.length() + "字符）";
            } else {
                summary += "：\n" + extractedText.substring(0, Math.min(extractedText.length(), summaryLen));
            }

            // 全量用户消息（含文档原文），标记 documentRead 以支持远期衰减
            ChatMessage userMsg = ChatMessage.of("user", prompt);
            userMsg.setDocumentRead(true);
            userMsg.setDocumentFileName(fileName);
            userMsg.setDocumentSummary(summary);
            chatMemoryManager.saveRaw(userId, userMsg);

            if ("SUCCESS".equals(response.getStatus()) && response.getContent() != null) {
                chatMemoryManager.saveMessage(userId, "assistant", response.getContent());
            }

            return response;

        } catch (TikaDocumentExtractor.TikaExtractException e) {
            log.error("[Facade] 文档提取失败 [{}]", fileName, e);
            return LLMResponse.success("已收到文件「" + fileName + "」，但解析时出现异常（" + e.getMessage()
                    + "）。请尝试发送未加密的常见文档格式（.docx / .pdf / .txt）。");
        }
    }

    // ==================== 多模态消息处理 ====================

    private LLMResponse handleMultiModalMessage(LLMRequest request) {
        String messageType = request.getMessageType();
        String mediaData = request.getMediaUrl() != null ? request.getMediaUrl() : request.getContent();

        for (MultiModalParser<String> parser : multiModalParsers) {
            if (parser.supports(messageType)) {
                log.info("找到匹配的解析器: {} -> {}", messageType, parser.getClass().getSimpleName());
                return parser.parse(mediaData);
            }
        }

        log.warn("未找到媒体类型 [{}] 的解析器", messageType);
        return LLMResponse.success("已收到你的消息，该类型暂不支持智能解析。");
    }
}
