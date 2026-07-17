package com.wechat.link.llm.agent;

import com.wechat.link.llm.dto.LLMResponse;

/**
 * Agent 智能代理服务接口
 * <p>
 * 定义 Agent 的执行契约，未来可扩展为多 Agent 协同、工具调用等场景。
 * </p>
 *
 * @author wechat-link
 */
public interface AgentService {

    /**
     * 执行 Agent 任务
     *
     * @param context Agent 运行上下文
     * @return 执行结果
     */
    LLMResponse execute(AgentContext context);
}
