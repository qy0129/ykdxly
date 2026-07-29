package com.example.ilink.application.executive;

/** 工具结果验证接口。 */
@FunctionalInterface
public interface ResultVerifier {
    ExecutionOutcome verify(ExecutiveStep step, ExecutionOutcome outcome);
}
