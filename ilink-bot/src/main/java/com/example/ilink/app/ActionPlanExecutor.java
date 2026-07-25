package com.example.ilink.app;

import com.example.ilink.routing.IntentAction;
import com.example.ilink.routing.IntentPlan;
import com.example.ilink.conversation.ActionPlanSessionStore;

import java.util.ArrayList;
import java.util.List;

/**
 * 统一执行一段话中的多个动作，并保存需要跨消息继续执行的剩余动作。
 *
 * <p>具体业务仍由原有工作流完成；本类只管理动作顺序、失败隔离和暂停恢复。</p>
 */
public final class ActionPlanExecutor {

    private final ActionPlanSessionStore sessions = new ActionPlanSessionStore();

    /** 使用新的动作计划替换该用户已完成的旧队列，并立即开始执行。 */
    public void start(String userId, IntentPlan plan, ActionRunner runner,
                      PendingChecker pendingChecker, ActionFailureHandler failureHandler) throws Exception {
        sessions.save(userId, plan.actions(), null);
        drain(userId, runner, pendingChecker, failureHandler);
    }

    /** 用户完成地点、时间等补充后，从上次暂停的位置继续执行剩余动作。 */
    public void resume(String userId, ActionRunner runner,
                       PendingChecker pendingChecker, ActionFailureHandler failureHandler) throws Exception {
        if (sessions.get(userId) == null) return;
        drain(userId, runner, pendingChecker, failureHandler);
    }

    /** 用户明确要求重试时，只重放失败的那一个动作，避免重复已完成的动作。 */
    public void retryFailed(String userId, ActionRunner runner, ActionFailureHandler failureHandler) throws Exception {
        ActionPlanSessionStore.ActionPlanState state = sessions.get(userId);
        if (state == null || state.failedAction() == null) return;
        IntentAction failed = state.failedAction();
        try {
            runner.run(failed);
            sessions.save(userId, state.remainingActions(), null);
        } catch (Exception error) {
            sessions.save(userId, state.remainingActions(), failed);
            failureHandler.handle(failed, error);
        }
    }

    public boolean hasFailedAction(String userId) {
        return sessions.hasFailedAction(userId);
    }

    /** 新需求或用户取消时，放弃旧请求的剩余动作。 */
    public void cancel(String userId) {
        sessions.clear(userId);
    }

    /** 依次执行动作；发现任一工作流进入等待状态时，保留队列并立即暂停。 */
    private void drain(String userId, ActionRunner runner,
                       PendingChecker pendingChecker, ActionFailureHandler failureHandler) throws Exception {
        ActionPlanSessionStore.ActionPlanState state = sessions.get(userId);
        if (state == null) return;
        List<IntentAction> actions = new ArrayList<>(state.remainingActions());
        IntentAction failedAction = state.failedAction();
        while (!actions.isEmpty()) {
            IntentAction action = actions.getFirst();
            try {
                runner.run(action);
                actions.removeFirst();
                sessions.save(userId, actions, failedAction);
            } catch (Exception error) {
                actions.removeFirst();
                failedAction = action;
                sessions.save(userId, actions, failedAction);
                failureHandler.handle(action, error);
            }
            if (pendingChecker.hasPending()) return;
        }
        sessions.save(userId, actions, failedAction);
    }

    /** 执行单个业务动作的回调。 */
    @FunctionalInterface
    public interface ActionRunner {
        void run(IntentAction action) throws Exception;
    }

    /** 判断当前动作是否正在等待用户补充信息。 */
    @FunctionalInterface
    public interface PendingChecker {
        boolean hasPending();
    }

    /** 单个动作失败时的处理回调，失败不会清空后续动作。 */
    @FunctionalInterface
    public interface ActionFailureHandler {
        void handle(IntentAction action, Exception error) throws Exception;
    }
}
