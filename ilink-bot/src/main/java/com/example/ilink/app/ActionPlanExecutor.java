package com.example.ilink.app;

import com.example.ilink.routing.IntentAction;
import com.example.ilink.routing.IntentPlan;

import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 统一执行一段话中的多个动作，并保存需要跨消息继续执行的剩余动作。
 *
 * <p>具体业务仍由原有工作流完成；本类只管理动作顺序、失败隔离和暂停恢复。</p>
 */
public final class ActionPlanExecutor {

    private final ConcurrentHashMap<String, Queue<IntentAction>> pendingActions = new ConcurrentHashMap<>();

    /** 使用新的动作计划替换该用户已完成的旧队列，并立即开始执行。 */
    public void start(String userId, IntentPlan plan, ActionRunner runner,
                      PendingChecker pendingChecker, ActionFailureHandler failureHandler) throws Exception {
        pendingActions.put(userId, new ConcurrentLinkedQueue<>(plan.actions()));
        drain(userId, runner, pendingChecker, failureHandler);
    }

    /** 用户完成地点、时间等补充后，从上次暂停的位置继续执行剩余动作。 */
    public void resume(String userId, ActionRunner runner,
                       PendingChecker pendingChecker, ActionFailureHandler failureHandler) throws Exception {
        if (!pendingActions.containsKey(userId)) return;
        drain(userId, runner, pendingChecker, failureHandler);
    }

    /** 依次执行动作；发现任一工作流进入等待状态时，保留队列并立即暂停。 */
    private void drain(String userId, ActionRunner runner,
                       PendingChecker pendingChecker, ActionFailureHandler failureHandler) throws Exception {
        Queue<IntentAction> actions = pendingActions.get(userId);
        if (actions == null) return;
        IntentAction action;
        while ((action = actions.poll()) != null) {
            try {
                runner.run(action);
            } catch (Exception error) {
                failureHandler.handle(action, error);
            }
            if (pendingChecker.hasPending()) return;
        }
        pendingActions.remove(userId, actions);
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
