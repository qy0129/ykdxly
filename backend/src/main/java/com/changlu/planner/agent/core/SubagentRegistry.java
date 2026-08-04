package com.changlu.planner.agent.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;

/** Subagent 只暴露专业能力，主 Agent 负责选择和调度。 */
public final class SubagentRegistry {
  private final Map<String, Subagent> subagents = new LinkedHashMap<>();

  public void register(Subagent subagent) {
    if (subagents.putIfAbsent(subagent.name(), subagent) != null) {
      throw new IllegalArgumentException("Subagent 重复注册：" + subagent.name());
    }
  }

  public Subagent require(String name) {
    Subagent subagent = subagents.get(name);
    if (subagent == null) throw new IllegalArgumentException("Subagent 未注册：" + name);
    return subagent;
  }

  public JsonArray definitions() {
    JsonArray result = new JsonArray();
    for (Subagent subagent : subagents.values()) {
      JsonObject item = new JsonObject();
      item.addProperty("name", subagent.name());
      item.addProperty("description", subagent.description());
      result.add(item);
    }
    return result;
  }
}
