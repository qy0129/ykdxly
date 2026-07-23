package com.example.ilink.conversation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 保存已展示、等待用户确认同步到日历的饮食计划。 */
public final class DietPlanSessionStore {
    private final Map<String, DietPlanDraft> drafts = new ConcurrentHashMap<>();

    public void set(String userId, DietPlanDraft draft) { drafts.put(userId, draft); }
    public DietPlanDraft get(String userId) { return drafts.get(userId); }
    public boolean has(String userId) { return drafts.containsKey(userId); }
    public void clear(String userId) { drafts.remove(userId); }

    /** 当前阶段决定下一条用户消息应作为偏好信息还是日历确认。 */
    public record DietPlanDraft(String goal, String stage) {
        public DietPlanDraft withStage(String value) { return new DietPlanDraft(goal, value); }
    }
}
