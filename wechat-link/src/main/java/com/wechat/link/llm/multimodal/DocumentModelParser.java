package com.wechat.link.llm.multimodal;

import com.wechat.link.llm.dto.LLMResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 文档解析器 - 骨架占位
 *
 * @author wechat-link
 */
@Slf4j
@Component
public class DocumentModelParser implements MultiModalParser<String> {

    @Override
    public boolean supports(String mediaType) {
        return "FILE".equalsIgnoreCase(mediaType);
    }

    @Override
    public LLMResponse parse(String mediaUrl) {
        // TODO: 接入文档解析服务（OCR / PDF 解析 / 文本提取）
        log.info("[DocumentModelParser] 文档解析功能待实现，媒体URL: {}", mediaUrl);
        return LLMResponse.success("【文档解析功能开发中】已收到文档，暂不支持内容解析。");
    }
}
