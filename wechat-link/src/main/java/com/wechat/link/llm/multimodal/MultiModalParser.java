package com.wechat.link.llm.multimodal;

import com.wechat.link.llm.dto.LLMResponse;

/**
 * 统一多模态解析器接口
 * <p>
 * 采用策略模式，不同媒体类型有各自的解析实现。
 * 泛型 T 代表媒体数据的类型（如 URL 字符串、字节数组等）。
 * </p>
 *
 * @author wechat-link
 */
public interface MultiModalParser<T> {

    /**
     * 判断是否支持该媒体类型
     *
     * @param mediaType 媒体类型标识（IMAGE, VIDEO, FILE, VOICE）
     * @return 是否支持
     */
    boolean supports(String mediaType);

    /**
     * 解析媒体数据
     *
     * @param mediaData 媒体数据
     * @return 统一响应
     */
    LLMResponse parse(T mediaData);
}
