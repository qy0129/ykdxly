package com.wechat.link.llm.memory;

import com.wechat.link.llm.dto.ChatMessage;
import com.wechat.link.llm.dto.ContentItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * 内存对话记忆管理器
 * <p>
 * 基于 ConcurrentHashMap + Deque 的滑动窗口架构：
 * - 每个用户独立维护一个固定长度的消息队列
 * - 满 MAX_MEMORY_SIZE 条时从头部淘汰最老的消息
 * - 支持纯文本和多模态（图文混合）两种消息格式
 * - 无论消息类型，在队列中只占用 1 个槽位
 * </p>
 */
@Slf4j
@Component
public class ChatMemoryManager {

    /** 每用户最多保留的消息条数（用户+助理共享） */
    private static final int MAX_MEMORY_SIZE = 10;

    /** 用户对话历史：userId → 滑动窗口消息队列 */
    private final Map<String, Deque<ChatMessage>> memoryStore = new ConcurrentHashMap<>();

    // ==================== 写入方法 ====================

    /**
     * 存入纯文本消息
     *
     * @param userId 用户 ID
     * @param role   角色（user / assistant / system）
     * @param text   文本内容
     */
    public void saveMessage(String userId, String role, String text) {
        ChatMessage message = ChatMessage.of(role, text);
        addToDeque(userId, message);
        log.info("[Memory] 成功为用户 {} 存入纯文本记忆（role={}），当前队列大小: {}",
                userId, role, getQueueSize(userId));
    }

    /**
     * 存入预先构建好的消息（保留 documentRead 等标记字段）
     * <p>
     * 用于文档读取等场景，需保留 {@link ChatMessage#documentRead} /
     * {@link ChatMessage#documentFileName} / {@link ChatMessage#documentSummary} 等标记，
     * 供 {@link com.wechat.link.llm.memory.MultiModalMemoryOptimizer} 后续衰减。
     * </p>
     *
     * @param userId  用户 ID
     * @param message 已构建的消息对象
     */
    public void saveRaw(String userId, ChatMessage message) {
        addToDeque(userId, message);
        log.info("[Memory] 成功为用户 {} 存入原始消息（role={}, documentRead={}），当前队列大小: {}",
                userId, message.getRole(), message.isDocumentRead(), getQueueSize(userId));
    }

    /**
     * 存入多模态混合消息（图文混合）
     *
     * @param userId       用户 ID
     * @param role         角色（user / assistant）
     * @param contentItems 多模态内容列表（text + image_url）
     */
    public void saveMessage(String userId, String role, List<ContentItem> contentItems) {
        ChatMessage message = ChatMessage.of(role, contentItems);
        addToDeque(userId, message);
        log.info("[Memory] 成功为用户 {} 存入多模态混合记忆（role={}），当前队列大小: {}",
                userId, role, getQueueSize(userId));
    }

    // ==================== 读取方法 ====================

    /**
     * 获取用户的完整对话历史（按时间顺序）
     *
     * @param userId 用户 ID
     * @return 历史消息列表（不可变副本）
     */
    public List<ChatMessage> getHistory(String userId) {
        Deque<ChatMessage> deque = memoryStore.get(userId);
        if (deque == null || deque.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(deque);
    }

    /**
     * 清空指定用户的记忆
     */
    public void clearHistory(String userId) {
        memoryStore.remove(userId);
        log.info("[Memory] 已清空用户 {} 的对话记忆", userId);
    }

    // ==================== 私有方法 ====================

    /**
     * 将消息追加到队列尾部，超出容量时从头部淘汰
     */
    private void addToDeque(String userId, ChatMessage message) {
        Deque<ChatMessage> deque = memoryStore.computeIfAbsent(userId,
                k -> new LinkedBlockingDeque<>(MAX_MEMORY_SIZE));

        // 队列已满，从头部移除最老的消息
        while (deque.size() >= MAX_MEMORY_SIZE) {
            deque.removeFirst();
        }
        deque.addLast(message);
    }

    /**
     * 获取当前队列大小
     */
    private int getQueueSize(String userId) {
        Deque<ChatMessage> deque = memoryStore.get(userId);
        return deque != null ? deque.size() : 0;
    }
}
