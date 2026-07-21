package com.wechat.link.llm.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 多模态内容项
 * <p>
 * 兼容 OpenAI Vision / Audio 规范，content 数组中的单个元素：
 * - type="text"         → text 字段有值
 * - type="image_url"    → image_url 字段有值
 * - type="input_audio"  → input_audio 字段有值（MP3 Base64）
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ContentItem {

    /** 类型：text / image_url / input_audio */
    private String type;

    /** 文本内容（type=text 时有效） */
    private String text;

    /** 图片链接（type=image_url 时有效） */
    private ImageUrl image_url;

    /** 语音数据（type=input_audio 时有效） */
    private InputAudio input_audio;

    // ==================== 便捷工厂方法 ====================

    /** 创建纯文本内容项 */
    public static ContentItem ofText(String text) {
        return new ContentItem("text", text, null, null);
    }

    /** 创建图片内容项（Base64 Data URL 或 CDN 链接） */
    public static ContentItem ofImageUrl(String url) {
        return new ContentItem("image_url", null, new ImageUrl(url), null);
    }

    /** 创建语音内容项（MP3 Base64） */
    public static ContentItem ofAudio(String base64Mp3, String format) {
        return new ContentItem("input_audio", null, null,
                new InputAudio(base64Mp3, format != null ? format : "mp3"));
    }

    // ==================== 内部类 ====================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ImageUrl {
        /** Base64 Data URL 或 CDN 链接 */
        private String url;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class InputAudio {
        /** MP3 格式的 Base64 编码音频数据 */
        private String data;
        /** 音频格式，固定为 "mp3" */
        private String format = "mp3";
    }
}
