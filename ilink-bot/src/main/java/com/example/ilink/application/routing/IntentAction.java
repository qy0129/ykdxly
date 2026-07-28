package com.example.ilink.application.routing;

/**
 * 一段用户话语中可以独立执行的一个要求。
 *
 * @param requestText 从原始话语中提取的当前动作描述，供仍需自然语言输入的工具使用
 * @param route       当前动作的意图名称和结构化参数
 */
public record IntentAction(String requestText, IntentResult route) {

    /** 保证动作描述始终可安全传给下游工具。 */
    public IntentAction {
        requestText = requestText == null ? "" : requestText.trim();
    }
}
