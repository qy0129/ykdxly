package com.example.ilink.application.executive;

/** Personal Executive Agent 的通用任务状态。 */
public enum TaskStatus {
    CREATED,
    PLANNING,
    WAITING_USER,
    WAITING_APPROVAL,
    READY,
    RUNNING,
    VERIFYING,
    RETRYING,
    COMPLETED,
    FAILED,
    CANCELLED,
    EXPIRED;

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED || this == EXPIRED;
    }
}
