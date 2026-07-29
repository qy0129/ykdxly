package com.example.ilink.application.executive;

/** 一个执行步骤的状态。 */
public enum StepStatus {
    PENDING,
    WAITING_APPROVAL,
    RUNNING,
    RETRYING,
    SUCCEEDED,
    FAILED,
    SKIPPED,
    CANCELLED;

    public boolean completed() {
        return this == SUCCEEDED || this == SKIPPED;
    }
}
