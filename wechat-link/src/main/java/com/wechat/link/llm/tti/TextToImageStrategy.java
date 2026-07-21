package com.wechat.link.llm.tti;

import com.wechat.link.llm.dto.LLMResponse;

/**
 * 文生图策略接口
 * <p>
 * 不同的生图引擎（DALL-E 等）各自实现此接口
 * </p>
 */
public interface TextToImageStrategy {

    /**
     * 根据文本 prompt 生成图片
     *
     * @param prompt 用户描述的画面内容
     * @return 包含图片 URL 的响应
     */
    LLMResponse generateImage(String prompt);

    /**
     * 根据文本 prompt + 参考图片进行图片编辑
     * <p>
     * 默认实现返回"不支持"，由支持图编辑的引擎覆写
     *
     * @param prompt     编辑描述
     * @param imageBytes 参考图片字节
     * @return 包含图片 URL 的响应
     */
    default LLMResponse editImage(String prompt, byte[] imageBytes) {
        return LLMResponse.fail("当前引擎不支持图片编辑");
    }

    /**
     * 该策略的引擎名称
     */
    String engineName();
}
