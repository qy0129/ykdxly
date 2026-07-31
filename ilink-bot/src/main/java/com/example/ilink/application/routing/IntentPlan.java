package com.example.ilink.application.routing;

import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 一次用户请求识别出的有序动作计划；单意图请求也表示为只有一个动作的计划。 */
public record IntentPlan(List<IntentAction> actions, MessageMode messageMode) {

    public IntentPlan(List<IntentAction> actions) {
        this(actions, MessageMode.COMMAND);
    }

    /** 复制动作列表，避免执行期间被外部代码修改。 */
    public IntentPlan {
        actions = orderByDependencies(normalizeIds(actions == null ? List.of() : actions));
        messageMode = messageMode == null ? MessageMode.COMMAND : messageMode;
    }

    /** 判断路由模型是否没有返回任何可执行动作。 */
    public boolean isEmpty() {
        return actions.isEmpty();
    }

    public boolean isPassiveMessage() {
        return messageMode == MessageMode.PASSIVE_MESSAGE;
    }

    /** 稳定拓扑排序：无依赖动作保持原顺序，依赖动作只会移动到前置动作之后。 */
    private static List<IntentAction> orderByDependencies(List<IntentAction> source) {
        List<IntentAction> remaining = new ArrayList<>(source);
        List<IntentAction> ordered = new ArrayList<>(source.size());
        Set<String> knownIds = source.stream().map(IntentAction::requirementId)
                .collect(java.util.stream.Collectors.toSet());
        for (IntentAction action : source) {
            for (String dependency : action.dependsOn()) {
                if (dependency.equals(action.requirementId())) {
                    throw new IllegalArgumentException("动作不能依赖自身：" + action.requirementId());
                }
                if (!knownIds.contains(dependency)) {
                    throw new IllegalArgumentException("动作依赖不存在：" + dependency);
                }
            }
        }
        Set<String> completed = new HashSet<>();
        while (!remaining.isEmpty()) {
            int runnable = -1;
            for (int index = 0; index < remaining.size(); index++) {
                IntentAction action = remaining.get(index);
                if (action.dependsOn().stream().allMatch(completed::contains)) {
                    runnable = index;
                    break;
                }
            }
            if (runnable < 0) {
                throw new IllegalArgumentException("动作计划存在循环依赖");
            }
            IntentAction action = remaining.remove(runnable);
            ordered.add(action);
            if (!action.requirementId().isBlank()) completed.add(action.requirementId());
        }
        return List.copyOf(ordered);
    }

    private static List<IntentAction> normalizeIds(List<IntentAction> source) {
        Map<String, IntentAction> indexed = new LinkedHashMap<>();
        List<IntentAction> normalized = new ArrayList<>(source.size());
        int sequence = 1;
        for (IntentAction action : source) {
            if (action == null || action.route() == null) {
                throw new IllegalArgumentException("动作计划包含空动作");
            }
            String id = action.requirementId();
            if (id.isBlank()) {
                do {
                    id = "r" + sequence++;
                } while (indexed.containsKey(id));
                action = new IntentAction(id, action.requestText(), action.dependsOn(), action.route());
            }
            if (indexed.putIfAbsent(id, action) != null) {
                throw new IllegalArgumentException("动作ID重复：" + id);
            }
            normalized.add(action);
        }
        return List.copyOf(normalized);
    }
}
