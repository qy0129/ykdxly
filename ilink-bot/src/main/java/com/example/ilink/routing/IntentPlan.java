package com.example.ilink.routing;

import java.util.List;

/** 一次用户请求识别出的有序动作计划；单意图请求也表示为只有一个动作的计划。 */
public record IntentPlan(List<IntentAction> actions) {

    /** 复制动作列表，避免执行期间被外部代码修改。 */
    public IntentPlan {
        actions = actions == null ? List.of() : List.copyOf(actions);
    }

    /** 判断路由模型是否没有返回任何可执行动作。 */
    public boolean isEmpty() {
        return actions.isEmpty();
    }
}
