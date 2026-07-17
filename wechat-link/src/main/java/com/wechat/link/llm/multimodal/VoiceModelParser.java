package com.wechat.link.llm.multimodal;

import com.wechat.link.llm.dto.LLMResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 语音解析器
 * <p>
 * 处理语音消息。当前策略：
 * - 如果微信已提供转文字结果，在 WechatBotRunner 层直接走文本对话（不会到这里）
 * - 如果没有转文字（mediaData 为空），返回提示信息
 * - 未来可扩展：接入 ASR（自动语音识别）服务对原始音频做转写
 * </p>
 *
 * @author wechat-link
 */
@Slf4j
@Component
public class VoiceModelParser implements MultiModalParser<String> {

    @Override
    public boolean supports(String mediaType) {
        return "VOICE".equalsIgnoreCase(mediaType);
    }

    /**
     * 处理语音消息
     *
     * @param mediaData 语音相关数据（当前为转文字文本或 null）
     * @return 提示响应
     */
    @Override
    public LLMResponse parse(String mediaData) {
        log.info("[VoiceModelParser] 收到语音，转文字内容: {}", mediaData);

        if (mediaData != null && !mediaData.isBlank()) {
            // 有转文字内容（正常不会走到这里，因为 WechatBotRunner 已经直接走文本对话了）
            return LLMResponse.success(mediaData);
        }

        // 无转文字内容
        return LLMResponse.success("收到语音消息，但未识别到文字内容。请尝试发送文字或开启微信语音转文字功能。");
    }
}
