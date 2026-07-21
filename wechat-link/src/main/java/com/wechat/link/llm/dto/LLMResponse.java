package com.wechat.link.llm.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * LLM 统一响应 DTO
 * <p>
 * 既能承载文本回复（content），也能承载生成的图片 URL 列表（imageUrls），
 * 以及语音回复标记（needVoiceReply + voiceReplyData）。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LLMResponse {

    /** 响应状态：SUCCESS / FAIL */
    private String status;

    /** 响应内容（LLM 生成的文本） */
    private String content;

    /** 生成的图片 URL 列表（文生图场景） */
    private List<String> imageUrls;

    /** 错误信息（仅失败时有值） */
    private String errorMsg;

    /** 是否需要 TTS 语音回复 */
    private Boolean needVoiceReply;

    /** 语音回复的 MP3 字节（TTS 生成后填充） */
    private byte[] voiceReplyData;

    /** 生成的文件字节（文档生成场景） */
    private byte[] fileBytes;

    /** 生成的文件名（文档生成场景） */
    private String fileName;

    /** 快捷构造成功响应（纯文本） */
    public static LLMResponse success(String content) {
        return LLMResponse.builder()
                .status("SUCCESS")
                .content(content)
                .build();
    }

    /** 快捷构造成功响应（图片列表） */
    public static LLMResponse successWithImages(List<String> imageUrls) {
        return LLMResponse.builder()
                .status("SUCCESS")
                .imageUrls(imageUrls)
                .build();
    }

    /** 快捷构造成功响应（文本 + 图片） */
    public static LLMResponse successWithImages(String content, List<String> imageUrls) {
        return LLMResponse.builder()
                .status("SUCCESS")
                .content(content)
                .imageUrls(imageUrls)
                .build();
    }

    /** 快捷构造失败响应 */
    public static LLMResponse fail(String errorMsg) {
        return LLMResponse.builder()
                .status("FAIL")
                .errorMsg(errorMsg)
                .build();
    }

    /** 是否包含图片 */
    public boolean hasImages() {
        return imageUrls != null && !imageUrls.isEmpty();
    }

    /** 是否需要语音回复 */
    public boolean isNeedVoiceReply() {
        return needVoiceReply != null && needVoiceReply;
    }
}
