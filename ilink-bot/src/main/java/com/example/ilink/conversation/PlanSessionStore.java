package com.example.ilink.conversation;

import com.example.ilink.model.TaskPlan;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 保存每位用户当前正在执行的任务计划。 */
public final class PlanSessionStore {

    private final Map<String, TaskPlan> plans = new ConcurrentHashMap<>();
    private final Map<String, PendingPlanRequest> pendingRequests = new ConcurrentHashMap<>();

    /** 保存或替换用户当前计划。 */
    public void set(String userId, TaskPlan plan) {
        plans.put(userId, plan);
    }

    /** 获取用户当前计划；没有计划时返回 null。 */
    public TaskPlan get(String userId) {
        return plans.get(userId);
    }

    /** 判断用户是否已经创建过计划。 */
    public boolean hasPlan(String userId) {
        return plans.containsKey(userId);
    }

    /** 清除用户当前计划。 */
    public void clear(String userId) {
        plans.remove(userId);
    }

    /** 保存等待用户补充截止时间的规划请求。 */
    public void setPending(String userId, PendingPlanRequest request) {
        pendingRequests.put(userId, request);
    }

    /** 获取等待补充截止时间的规划请求。 */
    public PendingPlanRequest getPending(String userId) {
        return pendingRequests.get(userId);
    }

    /** 判断用户是否正在补充规划信息。 */
    public boolean hasPending(String userId) {
        return pendingRequests.containsKey(userId);
    }

    /** 清除等待补充信息的规划请求。 */
    public void clearPending(String userId) {
        pendingRequests.remove(userId);
    }

    /** 尚未完成的规划请求及其回复选项。 */
    public record PendingPlanRequest(
            String goal,
            String availableTime,
            String replyMode,
            String voiceStyle,
            String outputFileType) {
    }
}
