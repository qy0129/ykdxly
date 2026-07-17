package com.wechat.link.llm.exception;

/**
 * LLM 模块自定义异常
 * <p>
 * 用于封装 LLM 调用过程中的各种异常情况，包括：
 * 网络超时、API Key 无效、模型返回异常等。
 * </p>
 *
 * @author wechat-link
 */
public class LLMException extends RuntimeException {

    private final String errorCode;

    public LLMException(String message) {
        super(message);
        this.errorCode = "LLM_ERROR";
    }

    public LLMException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public LLMException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = "LLM_ERROR";
    }

    public LLMException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
