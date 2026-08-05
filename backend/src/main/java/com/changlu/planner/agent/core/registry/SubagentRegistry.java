package com.changlu.planner.agent.core.registry;

import com.changlu.planner.agent.core.contract.Subagent;
import com.changlu.planner.agent.core.contract.SubagentDefinition;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class SubagentRegistry {
  private final Map<String, Subagent> subagents = new LinkedHashMap<>();

  public void register(Subagent subagent) {
    String name = subagent.definition().name();
    if (subagents.putIfAbsent(name, subagent) != null) throw new IllegalArgumentException("Subagent 重复注册：" + name);
  }

  public Subagent require(String name) {
    Subagent value = subagents.get(name);
    if (value == null) throw new IllegalArgumentException("Subagent 未注册：" + name);
    return value;
  }

  public boolean contains(String name) { return subagents.containsKey(name); }

  /** Generic fallback based only on registry metadata; no domain is hard-coded in the router. */
  public Optional<Subagent> bestMatch(String request) {
    String normalized = normalize(request);
    Subagent best = null;
    int bestLength = 0;
    for (Subagent subagent : subagents.values()) {
      for (String scenario : subagent.definition().supportedScenarios()) {
        String token = normalize(scenario);
        if (token.length() >= 2 && normalized.contains(token) && token.length() > bestLength) {
          best = subagent;
          bestLength = token.length();
        }
      }
    }
    return Optional.ofNullable(best);
  }

  private String normalize(String value) {
    return value == null ? "" : value.toLowerCase().replaceAll("[\\s，。！？、,.;:：；]", "");
  }

  public JsonArray definitions() {
    JsonArray rows = new JsonArray();
    for (Subagent subagent : subagents.values()) {
      SubagentDefinition definition = subagent.definition();
      JsonObject row = new JsonObject();
      row.addProperty("name", definition.name());
      row.addProperty("version", definition.version());
      row.addProperty("description", definition.description());
      JsonArray supported = new JsonArray();
      definition.supportedScenarios().forEach(supported::add);
      row.add("supportedScenarios", supported);
      JsonArray unsupported = new JsonArray();
      definition.unsupportedScenarios().forEach(unsupported::add);
      row.add("unsupportedScenarios", unsupported);
      row.addProperty("networkAllowed", definition.networkAllowed());
      row.addProperty("writeAllowed", definition.writeAllowed());
      rows.add(row);
    }
    return rows;
  }
}
