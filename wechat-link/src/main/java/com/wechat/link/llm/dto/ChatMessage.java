package com.wechat.link.llm.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 聊天消息实体
 * <p>
 * 兼容 OpenAI Chat Completions 规范：
 * - 纯文本场景：content 为 String
 * - 多模态场景：content 为 List&lt;ContentItem&gt;（包含 text + image_url）
 * <p>
 * Jackson 序列化时，content 字段会根据实际类型自动输出为字符串或数组，
 * 无需额外的 @JsonTypeInfo 注解。
 * </p>
 */
@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatMessage {

    /** 角色：system / user / assistant */
    private String role;

    /**
     * 消息内容（多态）：
     * - String：纯文本消息
     * - List&lt;ContentItem&gt;：多模态混合消息
     */
    private Object content;

    /** 是否为文档读取消息（用于记忆衰减） */
    @JsonIgnore
    private boolean documentRead;

    /** 文档文件名（用于衰减后占位符） */
    @JsonIgnore
    private String documentFileName;

    /** 文档降级后的摘要（远期消息替换全量内容） */
    @JsonIgnore
    private String documentSummary;

    public ChatMessage(String role, Object content, boolean documentRead,
                       String documentFileName, String documentSummary) {
        this.role = role;
        this.content = content;
        this.documentRead = documentRead;
        this.documentFileName = documentFileName;
        this.documentSummary = documentSummary;
    }

    // ==================== 便捷工厂方法 ====================

    /** 创建纯文本消息 */
    public static ChatMessage of(String role, String textContent) {
        return new ChatMessage(role, textContent, false, null, null);
    }

    /** 创建多模态混合消息 */
    public static ChatMessage of(String role, List<ContentItem> contentItems) {
        return new ChatMessage(role, contentItems, false, null, null);
    }

    /** 创建包含文本+图片的用户消息 */
    public static ChatMessage ofTextAndImage(String role, String text, String imageUrl) {
        List<ContentItem> items = List.of(
                ContentItem.ofText(text),
                ContentItem.ofImageUrl(imageUrl)
        );
        return new ChatMessage(role, items, false, null, null);
    }

    // ==================== 工具方法 ====================

    /** 判断是否为多模态消息 */
    public boolean isMultimodal() {
        return content instanceof List;
    }

    /** 获取纯文本内容（多模态时提取第一个 text 项） */
    public String getTextContent() {
        if (content instanceof String s) {
            return s;
        }
        if (content instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof ContentItem ci && "text".equals(ci.getType())) {
                    return ci.getText();
                }
            }
        }
        return null;
    }
}
