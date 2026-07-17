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
     * 发送对话请求
     *
     * @param request LLM 请求对象
     * @return LLM 统一响应
     */
    LLMResponse chat(LLMRequest request);
}
