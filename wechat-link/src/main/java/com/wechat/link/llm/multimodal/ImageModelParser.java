package com.wechat.link.llm.multimodal;

import com.wechat.link.llm.dto.LLMResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 图片解析器 - 骨架占位
 *
 * @author wechat-link
 */
@Slf4j
@Component
public class ImageModelParser implements MultiModalParser<String> {

    @Override
    public boolean supports(String mediaType) {
        return "IMAGE".equalsIgnoreCase(mediaType);
    }

    @Override
    public LLMResponse parse(String mediaUrl) {
        // TODO: 接入视觉模型（如 GLM-4V、GPT-4o）进行图片内容识别
        log.info("[ImageModelParser] 图片解析功能待实现，媒体URL: {}", mediaUrl);
        return LLMResponse.success("【图片识别功能开发中】已收到图片，暂不支持内容解析。");
    }
}
