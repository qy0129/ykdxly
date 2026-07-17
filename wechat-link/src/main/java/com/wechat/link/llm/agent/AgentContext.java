package com.wechat.link.llm.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent 运行上下文
 * <p>
 * 封装 Agent 执行所需的全部上下文信息，包括用户信息、意图、参数等。
 * </p>
 *
 * @author wechat-link
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentContext {

    /** 用户 ID */
    private String userId;

    /** 会话 ID */
    private String sessionId;

    /** 用户原始输入 */
    private String userInput;

    /** 识别到的意图（由 LLM 分析得出） */
    private String intent;

    /** 附加参数（JSON 格式，供 Agent 工具调用使用） */
    private String parameters;
}
