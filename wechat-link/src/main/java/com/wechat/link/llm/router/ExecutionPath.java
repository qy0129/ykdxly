package com.wechat.link.llm.router;

/**
 * 执行路径枚举
 * <p>
 * MULTIMODAL_PATH - 原生多模态大模型（慢、贵、能力全面）
 * SPECIALIZED_PIPELINE_PATH - 单功能组合管道（快、便宜、专精）
 * </p>
 */
public enum ExecutionPath {
    /** 多模态路径：交给 GPT-4o / Claude 等全能模型 */
    MULTIMODAL_PATH,
    /** 单功能管道路径：由路由器分配给专精模型组合 */
    SPECIALIZED_PIPELINE_PATH
}
