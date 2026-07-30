package com.example.ilink.application.routing;

import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/** 一次用户请求识别出的有序动作计划；单意图请求也表示为只有一个动作的计划。 */
public record IntentPlan(List<IntentAction> actions) {

    /** 复制动作列表，避免执行期间被外部代码修改。 */
    public IntentPlan {
        actions = orderByDependencies(actions == null ? List.of() : actions);
    }

    /** 判断路由模型是否没有返回任何可执行动作。 */
    public boolean isEmpty() {
        return actions.isEmpty();
    }

    /** 稳定拓扑排序：无依赖动作保持原顺序，依赖动作只会移动到前置动作之后。 */
    private static List<IntentAction> orderByDependencies(List<IntentAction> source) {
        List<IntentAction> remaining = new ArrayList<>(source);
        List<IntentAction> ordered = new ArrayList<>(source.size());
        Set<String> knownIds = source.stream().map(IntentAction::requirementId)
                .filter(id -> !id.isBlank()).collect(java.util.stream.Collectors.toSet());
        Set<String> completed = new HashSet<>();
        while (!remaining.isEmpty()) {
            int runnable = -1;
            for (int index = 0; index < remaining.size(); index++) {
                IntentAction action = remaining.get(index);
                if (action.dependsOn().stream().allMatch(id -> !knownIds.contains(id) || completed.contains(id))) {
                    runnable = index;
                    break;
                }
            }
            if (runnable < 0) {
                ordered.addAll(remaining);
                break;
            }
            IntentAction action = remaining.remove(runnable);
            ordered.add(action);
            if (!action.requirementId().isBlank()) completed.add(action.requirementId());
        }
        return List.copyOf(ordered);
    }
}
