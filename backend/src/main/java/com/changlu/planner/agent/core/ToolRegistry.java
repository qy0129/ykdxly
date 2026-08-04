package com.changlu.planner.agent.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Tool 元数据的唯一注册入口，运行时和模型看到的是同一份定义。 */
public final class ToolRegistry {
  private final Map<String, ToolDefinition> tools = new LinkedHashMap<>();

  public void register(ToolDefinition definition) {
    if (tools.putIfAbsent(definition.name(), definition) != null) {
      throw new IllegalArgumentException("工具重复注册：" + definition.name());
    }
  }

  public ToolDefinition require(String name) {
    ToolDefinition definition = tools.get(name);
    if (definition == null) throw new IllegalArgumentException("工具未注册：" + name);
    return definition;
  }

  public boolean contains(String name) { return tools.containsKey(name); }
  public Collection<ToolDefinition> all() { return tools.values(); }

  public JsonArray definitions() {
    JsonArray result = new JsonArray();
    for (ToolDefinition definition : tools.values()) {
      JsonObject item = new JsonObject();
      item.addProperty("name", definition.name());
      item.addProperty("description", definition.description());
      item.addProperty("executorType", definition.executorType());
      item.addProperty("requiresConfirmation", definition.requiresConfirmation());
      result.add(item);
    }
    return result;
  }
}
