package com.wechat.link.llm.multimodal;

import com.wechat.link.llm.dto.LLMResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 视频解析器 - 骨架占位
 *
 * @author wechat-link
 */
@Slf4j
@Component
public class VideoModelParser implements MultiModalParser<String> {

    @Override
    public boolean supports(String mediaType) {
        return "VIDEO".equalsIgnoreCase(mediaType);
    }

    @Override
    public LLMResponse parse(String mediaUrl) {
        // TODO: 接入视频理解模型进行视频内容分析
        log.info("[VideoModelParser] 视频解析功能待实现，媒体URL: {}", mediaUrl);
        return LLMResponse.success("【视频识别功能开发中】已收到视频，暂不支持内容解析。");
    }
}
