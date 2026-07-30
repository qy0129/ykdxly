package com.example.ilink.application.executive;

/** CapabilityExecutor 返回给执行内核的结构化结果。 */
public record ExecutionOutcome(Type type, String output, String error) {
    public enum Type { SUCCESS, RETRYABLE_FAILURE, WAITING_USER, PERMANENT_FAILURE }

    public ExecutionOutcome {
        type = type == null ? Type.PERMANENT_FAILURE : type;
        output = output == null ? "" : output;
        error = error == null ? "" : error;
    }

    public static ExecutionOutcome success(String output) {
        return new ExecutionOutcome(Type.SUCCESS, output, "");
    }

    public static ExecutionOutcome retry(String error) {
        return new ExecutionOutcome(Type.RETRYABLE_FAILURE, "", error);
    }

    public static ExecutionOutcome failure(String error) {
        return new ExecutionOutcome(Type.PERMANENT_FAILURE, "", error);
    }

    public static ExecutionOutcome waitingUser(String message) {
        return new ExecutionOutcome(Type.WAITING_USER, "", message);
    }
}
