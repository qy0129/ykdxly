package com.wechat.link.llm.memory;

import com.wechat.link.llm.config.LLMProperties;
import com.wechat.link.llm.dto.ChatMessage;
import com.wechat.link.llm.dto.ContentItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 多模态记忆衰减优化器（方案 1 + 方案 3 联合落地）
 * <p>
 * 在每次向大模型发起请求前，对用户的历史记忆进行动态扫描和结构优化：
 * - 最近 N 条消息（memoryRecentWindow）：保持原样，保留图片 Base64 和语音 Base64（方案 1：全量重传）
 * - 超过 N 条的老消息：将 image_url 和 input_audio 类型的 ContentItem 降级为纯文本占位符（方案 3：降级描述）
 * <p>
 * 不修改原始 Deque 中的数据，返回的是优化后的副本列表。
 * 窗口大小 N 通过 application.yml 的 llm.memory-recent-window 配置。
 * </p>
 */
@Slf4j
@Component
public class MultiModalMemoryOptimizer {

    /** 图片降级后的纯文本占位符 */
    private static final String IMAGE_DEGRADED_PLACEHOLDER =
            "[历史图片上下文：已由模型在后续对话中转化为文字描述]";

    /** 语音降级后的纯文本占位符 */
    private static final String AUDIO_DEGRADED_PLACEHOLDER =
            "[历史语音上下文：已由模型在后续对话中转化为文字描述]";

    /** 文档衰减计数 */
    private int documentDegradedCount = 0;

    private final ChatMemoryManager chatMemoryManager;
    private final LLMProperties properties;

    public MultiModalMemoryOptimizer(ChatMemoryManager chatMemoryManager,
                                     LLMProperties properties) {
        this.chatMemoryManager = chatMemoryManager;
        this.properties = properties;
    }

    /**
     * 获取优化后的历史记忆列表
     * <p>
     * 保留最近 memoryRecentWindow 条消息的完整图片和语音数据，
     * 将更早的图片/语音 Base64 降级为纯文本占位符，大幅节省 Token。
     * </p>
     *
     * @param userId 用户 ID
     * @return 优化后的消息列表（深拷贝，不影响原始队列）
     */
    public List<ChatMessage> optimizeAndGetHistory(String userId) {
        List<ChatMessage> originalHistory = chatMemoryManager.getHistory(userId);
        if (originalHistory.isEmpty()) {
            return originalHistory;
        }

        int recentWindow = properties.getMemoryRecentWindow();
        int totalSize = originalHistory.size();
        int degradeBoundary = totalSize - recentWindow;
        int degradedImageCount = 0;
        int degradedAudioCount = 0;
        documentDegradedCount = 0;

        List<ChatMessage> optimized = new ArrayList<>(totalSize);

        for (int i = 0; i < totalSize; i++) {
            ChatMessage original = originalHistory.get(i);

            if (i < degradeBoundary) {
                // 老消息 → 检查降级类型
                if (original.isMultimodal()) {
                    // 多模态消息降级图片/语音
                    ChatMessage degraded = degradeMultimodalMessage(original);
                    optimized.add(degraded);
                    if (containsImage(original)) degradedImageCount++;
                    if (containsAudio(original)) degradedAudioCount++;
                } else if (original.isDocumentRead()) {
                    // 文档读取消息降级为摘要占位符
                    ChatMessage degraded = degradeDocumentMessage(original);
                    optimized.add(degraded);
                    documentDegradedCount++;
                } else {
                    optimized.add(original);
                }
            } else {
                // 最近的消息保持原样
                optimized.add(original);
            }
        }

        if (degradedImageCount > 0 || degradedAudioCount > 0 || documentDegradedCount > 0) {
            log.info("[Optimizer] 用户 {} 的记忆衰减：裁剪老旧图片 {} 条、老旧语音 {} 条、老旧文档 {} 条，" +
                            "保留近期全量 {} 条，当前队列总长: {}",
                    userId, degradedImageCount, degradedAudioCount, documentDegradedCount,
                    Math.min(recentWindow, totalSize), totalSize);
        }

        return optimized;
    }

    /**
     * 将多模态消息中的 image_url 和 input_audio 项降级为纯文本占位符
     * 保留原始文本内容，仅移除媒体数据
     */
    private ChatMessage degradeMultimodalMessage(ChatMessage original) {
        @SuppressWarnings("unchecked")
        List<ContentItem> originalItems = (List<ContentItem>) original.getContent();

        List<ContentItem> degradedItems = new ArrayList<>();
        for (ContentItem item : originalItems) {
            if ("image_url".equals(item.getType())) {
                // 图片项降级为文本占位符
                degradedItems.add(ContentItem.ofText(IMAGE_DEGRADED_PLACEHOLDER));
            } else if ("input_audio".equals(item.getType())) {
                // 语音项降级为文本占位符（无情抹除 Base64 巨额数据）
                degradedItems.add(ContentItem.ofText(AUDIO_DEGRADED_PLACEHOLDER));
            } else {
                // 文本项保持不变
                degradedItems.add(item);
            }
        }

        return ChatMessage.of(original.getRole(), degradedItems);
    }

    /**
     * 将文档读取消息降级为摘要占位符
     * <p>
     * 替换完整文档内容为 {@link ChatMessage#documentSummary}，
     * 保留 role 和文档标记，清除全量文本以节省 Token。
     * </p>
     */
    private ChatMessage degradeDocumentMessage(ChatMessage original) {
        String summary = original.getDocumentSummary() != null
                ? original.getDocumentSummary()
                : "[历史文档上下文：已阅读" + original.getDocumentFileName() + "]";
        ChatMessage degraded = ChatMessage.of(original.getRole(), summary);
        degraded.setDocumentRead(true);
        degraded.setDocumentFileName(original.getDocumentFileName());
        degraded.setDocumentSummary(summary);
        return degraded;
    }

    /** 判断消息是否包含图片 */
    private boolean containsImage(ChatMessage message) {
        if (!message.isMultimodal()) return false;
        @SuppressWarnings("unchecked")
        List<ContentItem> items = (List<ContentItem>) message.getContent();
        return items.stream().anyMatch(i -> "image_url".equals(i.getType()));
    }

    /** 判断消息是否包含语音 */
    private boolean containsAudio(ChatMessage message) {
        if (!message.isMultimodal()) return false;
        @SuppressWarnings("unchecked")
        List<ContentItem> items = (List<ContentItem>) message.getContent();
        return items.stream().anyMatch(i -> "input_audio".equals(i.getType()));
    }
}
