package com.wechat.link.llm.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * LLM 请求 DTO
 * <p>
 * 封装用户发送给 LLM 的请求信息。
 * </p>
 *
 * @author wechat-link
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LLMRequest {

    /** 用户唯一标识（微信用户 ID） */
    private String userId;

    /** 会话 ID，用于多轮对话上下文管理 */
    private String sessionId;

    /** 消息内容（文本） */
    private String content;

    /** 消息类型：TEXT, IMAGE, VOICE, VIDEO, FILE */
    private String messageType;

    /** 媒体资源 URL（图片/语音/视频/文件的链接） */
    private String mediaUrl;
}
