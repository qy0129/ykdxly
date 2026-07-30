package com.example.ilink.application.executive;

import java.util.List;

/** Executive Core 调用业务能力的唯一边界。 */
@FunctionalInterface
public interface CapabilityExecutor {
    ExecutionOutcome execute(ExecutiveTask task, ExecutiveStep step,
                             List<ExecutiveStep> allSteps) throws Exception;
}
