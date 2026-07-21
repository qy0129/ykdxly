package com.wechat.link.llm.client;

import com.wechat.link.llm.dto.LLMRequest;
import com.wechat.link.llm.dto.LLMResponse;

/**
 * LLM 客户端统一接口
 * <p>
 * 定义与大语言模型交互的核心契约。
 * 不同的 LLM 提供商（OpenAI、智谱、Ollama 等）各自实现此接口。
 * </p>
 *
 * @author wechat-link
 */
public interface LLMClient {

    /**
     * 发送对话请求（默认文本模型）
     *
     * @param request LLM 请求对象
     * @return LLM 统一响应
     */
    LLMResponse chat(LLMRequest request);

    /**
     * 发送对话请求（多模态模型：qwen3.7-plus 等视觉模型）
     * 用于深度思考、图像识别等需要视觉/多模态能力的场景
     *
     * @param request LLM 请求对象
     * @return LLM 统一响应
     */
    default LLMResponse multimodalChat(LLMRequest request) {
        // 默认降级为普通 chat
        return chat(request);
    }
}
