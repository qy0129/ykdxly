package com.wechat.link.llm.router;

import com.wechat.link.file.service.DocumentConvertService;
import com.wechat.link.llm.client.LLMClient;
import com.wechat.link.llm.config.LLMProperties;
import com.wechat.link.llm.dto.LLMRequest;
import com.wechat.link.llm.dto.LLMResponse;
import com.wechat.link.llm.memory.ActiveImageCacheManager;
import com.wechat.link.llm.memory.MultiModalMemoryOptimizer;
import com.wechat.link.llm.tti.TextToImageStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 自适应 LLM 路由器实现（三模智能切换 + 语音路由版）
 * <p>
 * 路由策略：
 * 1. 显式前缀优先（Rule-based Fast Pass）
 * 2. 关键词语义分析（Keyword Analysis）
 * 3. 根据意图类型自动分流：
 *    - IMAGE_EDIT → 方案 2（从激活图缓存取原图，送编辑 API）
 *    - IMAGE_CHAT / PURE_TEXT → 方案 1+3（MultiModalMemoryOptimizer 衰减优化后的历史）
 *    - TEXT_TO_IMAGE → 文生图管道
 *    - NEED_VOICE_REPLY → 文本生成后触发 TTS 管道
 * </p>
 */
@Slf4j
@Service
public class LLMRouterImpl implements LLMRouter {

    // ==================== 前缀规则 ====================

    /** 文生图显式前缀 */
    private static final Set<String> TTI_PREFIXES = Set.of("/draw ", "/imag ", "/画 ");

    /** 多模态/深度思考显式前缀 */
    private static final Set<String> MULTIMODAL_PREFIXES = Set.of("/think ", "/deep ", "/深度 ");

    /** 图片编辑显式前缀 */
    private static final Set<String> EDIT_PREFIXES = Set.of("/edit ", "/p图 ", "/修图 ");

    /** 语音回复显式前缀 */
    private static final Set<String> VOICE_REPLY_PREFIXES = Set.of("/voice ", "/语音 ", "/听 ");

    // ==================== 关键词库 ====================

    /** 文生图关键词 */
    private static final Set<String> TTI_KEYWORDS = Set.of(
            "画", "绘", "生成一张", "生成图", "画一张", "画一幅",
            "设计一个", "设计一张", "插画", "海报","生成一个",
            "draw", "generate image", "create image"
    );

    /** 图片编辑关键词 */
    private static final Set<String> EDIT_KEYWORDS = Set.of(
            "把它", "把这", "换成", "改成", "调亮", "调暗", "加上", "去掉",
            "移除", "修改", "替换", "变成", "调色", "美化", "磨皮",
            "裁剪", "翻转", "旋转", "模糊", "锐化", "P一下", "p一下",
            "edit", "change", "replace", "remove"
    );

    /** 文档生成触发关键词 */
    private static final Set<String> DOCUMENT_GENERATE_KEYWORDS = Set.of(
            "转成文档", "做成文件", "导出word", "导出pdf", "导出Word", "导出PDF",
            "生成文档", "生成文件", "生成word", "生成pdf", "生成Word", "生成PDF",
            "保存为", "保存成", "转换为文档", "转文档", "导出为",
            "转为word", "转为pdf", "转为Word", "转为PDF","转成word", "转成pdf", "转成Word", "转成PDF",
            "convert to doc", "convert to pdf", "save as", "export"
    );

    /** 语音回复触发关键词（用户要求听声音） */
    private static final Set<String> VOICE_REPLY_KEYWORDS = Set.of(
            "用语音回答", "用语音回复", "说给我听", "读给我听", "唱首歌",
            "我想听", "用声音", "语音回答", "语音回复",
            "speak", "say it", "read aloud", "sing"
    );

    // ==================== 依赖 ====================

    private final LLMProperties properties;
    private final LLMClient llmClient;
    private final TextToImageStrategy textToImageStrategy;
    private final ActiveImageCacheManager imageCacheManager;
    private final MultiModalMemoryOptimizer memoryOptimizer;
    private final DocumentConvertService documentConvertService;

    public LLMRouterImpl(LLMProperties properties,
                                 LLMClient llmClient,
                                 TextToImageStrategy textToImageStrategy,
                                 ActiveImageCacheManager imageCacheManager,
                                 MultiModalMemoryOptimizer memoryOptimizer,
                                 DocumentConvertService documentConvertService) {
        this.properties = properties;
        this.llmClient = llmClient;
        this.textToImageStrategy = textToImageStrategy;
        this.imageCacheManager = imageCacheManager;
        this.memoryOptimizer = memoryOptimizer;
        this.documentConvertService = documentConvertService;
    }

    @Override
    public LLMResponse route(String userId, String userText) {
        return routeWithSource(userId, userText, "TEXT");
    }

    /**
     * 路由入口（带消息来源标记）
     *
     * @param userId      用户 ID
     * @param userText    用户输入文本
     * @param sourceType  消息来源类型：TEXT / VOICE（来自语音转文字）
     * @return LLM 响应
     */
    public LLMResponse routeWithSource(String userId, String userText, String sourceType) {
        long startMs = System.currentTimeMillis();
        log.info("【路由入口】用户={}, sourceType={}, 文本=\"{}\"", userId, sourceType,
                userText.length() > 50 ? userText.substring(0, 50) + "..." : userText);

        // 检测是否需要语音回复
        boolean needVoiceReply = detectNeedVoiceReply(userText, sourceType);

        // 根据配置的路由模式决定是否强制走某条路径
        String routingMode = properties.getRoutingMode();

        if ("MULTIMODAL".equalsIgnoreCase(routingMode)) {
            log.info("[LLM Router] 路由模式=MULTIMODAL，强制走多模态路径");
            return executeMultimodal(userId, userText, startMs, needVoiceReply);
        }
        if ("SPECIALIZED".equalsIgnoreCase(routingMode)) {
            log.info("[LLM Router] 路由模式=SPECIALIZED，强制走专精管道");
            return executeSpecialized(userId, userText, analyzeIntent(userId, userText), startMs, needVoiceReply);
        }

        // AUTO 模式：前缀优先 + 关键词分析
        String lowerText = userText.toLowerCase();

        // 1. 显式前缀检测 - 语音回复
        for (String prefix : VOICE_REPLY_PREFIXES) {
            if (lowerText.startsWith(prefix)) {
                String prompt = userText.substring(prefix.length()).trim();
                needVoiceReply = true;
                log.info("【路由决策】检测到语音前缀「{}」→ 执行路径: executeWithOptimizedMemory(TTS=true), 耗时={}ms",
                        prefix.trim(), System.currentTimeMillis() - startMs);
                return executeWithOptimizedMemory(userId, prompt, startMs, true);
            }
        }

        // 2. 显式前缀检测 - 图片编辑
        for (String prefix : EDIT_PREFIXES) {
            if (lowerText.startsWith(prefix)) {
                String prompt = userText.substring(prefix.length()).trim();
                log.info("【路由决策】检测到编辑前缀「{}」→ 执行路径: executeImageEdit, 耗时={}ms",
                        prefix.trim(), System.currentTimeMillis() - startMs);
                return executeImageEdit(userId, prompt, startMs);
            }
        }

        // 3. 显式前缀检测 - 文生图
        for (String prefix : TTI_PREFIXES) {
            if (lowerText.startsWith(prefix)) {
                String prompt = userText.substring(prefix.length()).trim();
                log.info("【路由决策】检测到生图前缀「{}」→ 执行路径: executeTTI, 耗时={}ms",
                        prefix.trim(), System.currentTimeMillis() - startMs);
                return executeTTI(prompt, startMs);
            }
        }

        // 4. 显式前缀检测 - 深度思考/多模态
        for (String prefix : MULTIMODAL_PREFIXES) {
            if (lowerText.startsWith(prefix)) {
                String prompt = userText.substring(prefix.length()).trim();
                log.info("【路由决策】检测到深度思考前缀「{}」→ 执行路径: executeMultimodal, 耗时={}ms",
                        prefix.trim(), System.currentTimeMillis() - startMs);
                return executeMultimodal(userId, prompt, startMs, needVoiceReply);
            }
        }

        // 5. 关键词语义分析
        IntentType intent = analyzeIntent(userId, userText);
        log.info("【路由决策】无前缀匹配 → 语义分析 intent={}, needVoiceReply={}, 执行路径: executeSpecialized, 耗时={}ms",
                intent, needVoiceReply, System.currentTimeMillis() - startMs);

        return executeSpecialized(userId, userText, intent, startMs, needVoiceReply);
    }

    // ==================== 语音 TTS 触发检测 ====================

    /**
     * 检测用户是否需要语音回复（TTS 触发条件）
     * 条件A：消息来源是 VOICE（用户发的是语音）
     * 条件B：文本中包含语音回复关键词
     */
    private boolean detectNeedVoiceReply(String userText, String sourceType) {
        // 条件A：用户发送语音消息且 autoVoiceReply 开启
        if ("VOICE".equalsIgnoreCase(sourceType)) {
            if (properties.getVoice().getTts().getAutoVoiceReply()) {
                log.info("[Voice Router] 用户发送语音消息 → 自动标记 NEED_VOICE_REPLY");
                return true;
            }
        }

        // 条件B：文本中包含语音回复诉求
        String lowerText = userText.toLowerCase();
        for (String keyword : VOICE_REPLY_KEYWORDS) {
            if (lowerText.contains(keyword)) {
                log.info("[Voice Router] 检测到语音回复关键词「{}」→ 触发 NEED_VOICE_REPLY", keyword);
                return true;
            }
        }

        return false;
    }

    // ==================== 意图分析 ====================

    /**
     * 关键词语义分析，判断用户意图
     * 优先级：IMAGE_EDIT > DOCUMENT_GENERATE > TEXT_TO_IMAGE > PURE_TEXT
     */
    private IntentType analyzeIntent(String userId, String userText) {
        // 先检测是否有编辑意图关键词 + 用户有激活图缓存
        if (imageCacheManager.hasCache(userId)) {
            for (String keyword : EDIT_KEYWORDS) {
                if (userText.contains(keyword)) {
                    return IntentType.IMAGE_EDIT;
                }
            }
        }

        // 检测文档生成意图
        for (String keyword : DOCUMENT_GENERATE_KEYWORDS) {
            if (userText.contains(keyword)) {
                return IntentType.DOCUMENT_GENERATE;
            }
        }

        // 检测文生图意图
        for (String keyword : TTI_KEYWORDS) {
            if (userText.contains(keyword)) {
                return IntentType.TEXT_TO_IMAGE;
            }
        }

        return IntentType.PURE_TEXT;
    }

    // ==================== 执行路径 ====================

    /**
     * 执行单功能专精管道路径
     */
    private LLMResponse executeSpecialized(String userId, String userText, IntentType intent,
                                           long startMs, boolean needVoiceReply) {
        switch (intent) {
            case IMAGE_EDIT -> {
                return executeImageEdit(userId, userText, startMs);
            }
            case TEXT_TO_IMAGE -> {
                String prompt = extractImagePrompt(userText);
                return executeTTI(prompt, startMs);
            }
            case IMAGE_CHAT -> {
                return executeWithOptimizedMemory(userId, userText, startMs, needVoiceReply);
            }
            case VOICE_ASR_FALLBACK -> {
                log.warn("[LLM Router] 收到 VOICE_ASR_FALLBACK 意图");
                return LLMResponse.fail("VOICE_ASR_FALLBACK");
            }
            case TEXT_TO_VIDEO -> {
                return LLMResponse.success("【文生视频功能开发中】该功能即将上线，敬请期待！");
            }
            case DOCUMENT_READ -> {
                log.warn("[LLM Router] DOCUMENT_READ 应由上层 WechatBotRunner.handleFile 直接处理，不应进入路由器");
                return executeWithOptimizedMemory(userId, userText, startMs, needVoiceReply);
            }
            case DOCUMENT_GENERATE -> {
                return executeDocumentGenerate(userId, userText, startMs);
            }
            case TEXT_TO_DOC -> {
                log.warn("[LLM Router] TEXT_TO_DOC 已废弃，由 DOCUMENT_GENERATE 替代");
                return executeDocumentGenerate(userId, userText, startMs);
            }
            default -> {
                // PURE_TEXT / NEED_VOICE_REPLY: 走带优化记忆的文本对话
                return executeWithOptimizedMemory(userId, userText, startMs, needVoiceReply);
            }
        }
    }

    /**
     * 图片编辑路径 — 使用 qwen-image-2.0 生成编辑后的图片
     */
    private LLMResponse executeImageEdit(String userId, String editPrompt, long startMs) {
        byte[] cachedImage = imageCacheManager.getCache(userId);
        if (cachedImage == null) {
            log.warn("[LLM Router] 用户 {} 请求图片编辑但无激活图缓存，降级为纯文本", userId);
            return executeWithOptimizedMemory(userId, editPrompt, startMs, false);
        }

        log.info("[LLM Router] 用户 {} 执行图片编辑，缓存图大小: {}KB", userId, cachedImage.length / 1024);

        try {
            LLMResponse response = textToImageStrategy.editImage(editPrompt, cachedImage);
            log.info("[LLM Router] 图片编辑完成，总耗时 {}ms", System.currentTimeMillis() - startMs);
            return response;

        } catch (Exception e) {
            log.error("[LLM Router] 图片编辑失败，降级为纯文本对话", e);
            return executeWithOptimizedMemory(userId, editPrompt, startMs, false);
        }
    }

    /**
     * 带优化记忆的对话路径（方案 1+3：近期全量 + 远期降级）
     * 用于 PURE_TEXT、IMAGE_CHAT、NEED_VOICE_REPLY 等场景
     */
    private LLMResponse executeWithOptimizedMemory(String userId, String text,
                                                   long startMs, boolean needVoiceReply) {
        try {
            LLMRequest request = LLMRequest.builder()
                    .userId(userId)
                    .sessionId(userId)
                    .content(text)
                    .messageType("TEXT")
                    .build();
            LLMResponse response = llmClient.chat(request);
            log.info("[LLM Router] 优化记忆对话完成，总耗时 {}ms", System.currentTimeMillis() - startMs);

            // 如果需要语音回复，在响应中标记
            if (needVoiceReply && "SUCCESS".equals(response.getStatus())) {
                response.setNeedVoiceReply(true);
                log.info("[Voice Router] LLM 响应已标记 NEED_VOICE_REPLY，准备触发 TTS");
            }

            return response;
        } catch (Exception e) {
            log.error("[LLM Router] 对话异常", e);
            return LLMResponse.fail("系统繁忙，请稍后再试。");
        }
    }

    /**
     * 多模态/深度思考路径 — 使用 qwen3.7-plus 等视觉模型
     * 支持文字、图片、视频、文档理解
     */
    private LLMResponse executeMultimodal(String userId, String text,
                                          long startMs, boolean needVoiceReply) {
        try {
            LLMRequest request = LLMRequest.builder()
                    .userId(userId)
                    .sessionId(userId)
                    .content(text)
                    .messageType("TEXT")
                    .build();
            LLMResponse response = llmClient.multimodalChat(request);
            log.info("[LLM Router] 多模态对话完成，总耗时 {}ms", System.currentTimeMillis() - startMs);

            if (needVoiceReply && "SUCCESS".equals(response.getStatus())) {
                response.setNeedVoiceReply(true);
                log.info("[Voice Router] 多模态响应已标记 NEED_VOICE_REPLY");
            }

            return response;
        } catch (Exception e) {
            log.error("[LLM Router] 多模态对话异常", e);
            return LLMResponse.fail("系统繁忙，请稍后再试。");
        }
    }

    /**
     * 执行文生图
     */
    private LLMResponse executeTTI(String prompt, long startMs) {
        try {
            LLMResponse response = textToImageStrategy.generateImage(prompt);
            log.info("[LLM Router] 文生图完成，总耗时 {}ms", System.currentTimeMillis() - startMs);
            return response;
        } catch (Exception e) {
            log.error("[LLM Router] 文生图失败", e);
            return LLMResponse.fail("图片生成失败：" + e.getMessage());
        }
    }

    /**
     * 执行文档生成路径（支持图→文档 + 文→文档）
     * <p>
     * - 有激活图缓存 → 图→文档：Vision 模型识别图片内容后输出 Markdown
     * - 无激活图缓存 → 文→文档：qwen3.7-plus 纯文本 Markdown 排版
     * 1. 调用多模态模型生成严格 Markdown
     * 2. 调用 DocumentConvertService 转为 docx/pdf
     * 3. 响应中包含文件字节供上层发送
     *
     * @param userText 用户输入（可能包含图片描述 + 生成指令）
     * @param startMs  开始时间戳
     */
    private LLMResponse executeDocumentGenerate(String userId, String userText, long startMs) {
        String targetFormat = detectTargetFormat(userText);
        log.info("[LLM Router] 执行文档生成 [{}]，目标格式: {}", userId, targetFormat);

        try {
            // 检测是否有激活图缓存（图→文档场景）
            byte[] cachedImage = imageCacheManager.getCache(userId);
            String mediaUrl = null;
            String content;

            if (cachedImage != null) {
                String base64 = Base64.getEncoder().encodeToString(cachedImage);
                mediaUrl = "data:image/jpeg;base64," + base64;
                content = buildDocumentPrompt(userText, targetFormat);
                log.info("[LLM Router] 图→文档 [{}]：检测到激活图缓存，大小 {}KB", userId, cachedImage.length / 1024);
            } else {
                content = buildDocumentPrompt(userText, targetFormat);
                log.info("[LLM Router] 文→文档 [{}]：无图缓存，纯文本生成", userId);
            }

            // 调用多模态模型（qwen3.7-plus）生成严格 Markdown
            // 有图时传 mediaUrl → buildCurrentMessage 组装图文混合消息
            LLMRequest request = LLMRequest.builder()
                    .userId(userId)
                    .sessionId(userId)
                    .content(content)
                    .messageType("TEXT")
                    .mediaUrl(mediaUrl)
                    .build();
            LLMResponse llmResp = llmClient.multimodalChat(request);

            if (!"SUCCESS".equals(llmResp.getStatus()) || llmResp.getContent() == null) {
                log.error("[LLM Router] 文档内容生成失败");
                return LLMResponse.fail("文档内容生成失败，请稍后重试。");
            }

            String markdown = llmResp.getContent();
            log.info("[LLM Router] Markdown 内容已生成，长度: {}字符", markdown.length());

            // 转换为目标格式文件
            byte[] fileBytes = documentConvertService.convertMarkdownToFile(markdown, targetFormat);

            // 构建返回：文件名 + 文件字节
            String fileName = "document_" + System.currentTimeMillis() + "." + targetFormat;
            LLMResponse response = LLMResponse.success("文档已生成：" + fileName);
            response.setFileBytes(fileBytes);
            response.setFileName(fileName);

            long elapsed = System.currentTimeMillis() - startMs;
            log.info("[LLM Router] 文档生成完成，耗时 {}ms，文件: {} ({}KB)",
                    elapsed, fileName, fileBytes.length / 1024);
            return response;

        } catch (Exception e) {
            log.error("[LLM Router] 文档生成失败", e);
            return LLMResponse.fail("文档生成失败：" + e.getMessage());
        }
    }

    /**
     * 从用户文本中检测目标文档格式
     */
    private String detectTargetFormat(String userText) {
        String lower = userText.toLowerCase();
        if (lower.contains("pdf")) return "pdf";
        return "docx";
    }

    /**
     * 构建文档生成 Prompt，强约束输出纯 Markdown
     *
     * @param userText     用户输入
     * @param targetFormat 目标文档格式 docx/pdf
     */
    private String buildDocumentPrompt(String userText, String targetFormat) {
        return "你是一个文档格式专家。请将以下内容整理为完美的 Markdown 格式，"
                + "不要包含任何前导或后置的解释性文字，直接输出 Markdown 内容。"
                + "如果用户要求生成" + targetFormat.toUpperCase()
                + "文件，请确保 Markdown 包含完整的排版（标题、列表、表格等）。\n\n"
                + "用户内容：\n" + userText;
    }

    // ==================== 工具方法 ====================

    private String extractImagePrompt(String userText) {
        String[] prefixWords = {"帮我画", "画一张", "画一幅", "生成一张", "生成图片", "设计一张", "设计一个", "画"};
        for (String prefix : prefixWords) {
            if (userText.startsWith(prefix)) {
                return userText.substring(prefix.length()).trim();
            }
        }
        return userText;
    }
}
