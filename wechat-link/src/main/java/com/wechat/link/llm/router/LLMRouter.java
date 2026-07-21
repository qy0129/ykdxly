package com.wechat.link.llm.router;

import com.wechat.link.llm.dto.LLMResponse;

/**
 * LLM 智能路由器接口
 * <p>
 * 分析用户文本意图，决定执行路径，并调度到对应模型完成处理
 * </p>
 */
public interface LLMRouter {

    /**
     * 路由并执行
     *
     * @param userId   用户 ID
     * @param userText 用户输入的原始文本
     * @return 统一响应（文本或图片）
     */
    LLMResponse route(String userId, String userText);
}
