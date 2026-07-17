package com.wechat.link.llm.agent;

import com.wechat.link.llm.dto.LLMResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 默认 Agent 执行器 - 骨架占位
 * <p>
 * 当前仅作为结构占位，不实现具体业务逻辑。
 * 未来可扩展为：工具调用 Agent、RAG Agent、多步推理 Agent 等。
 * </p>
 *
 * @author wechat-link
 */
@Slf4j
@Service
public class DefaultAgentExecutor implements AgentService {

    @Override
    public LLMResponse execute(AgentContext context) {
        log.info("[DefaultAgentExecutor] Agent module placeholder - 用户: {}, 输入: {}",
                context.getUserId(), context.getUserInput());
        // TODO: 实现 Agent 工具调用链、意图路由、多步推理等逻辑
        return LLMResponse.success("Agent 模块尚未启用，当前使用基础对话模式。");
    }
}
