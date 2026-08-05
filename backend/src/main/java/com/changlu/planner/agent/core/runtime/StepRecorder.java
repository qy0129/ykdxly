package com.changlu.planner.agent.core.runtime;

import com.changlu.planner.shared.database.Database;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/** 记录 agent_run_steps：主循环步骤 + subagent 内部工具子步骤，供前端时间线展示。 */
public final class StepRecorder {
  private final Database database;
  private final ThreadLocal<UUID> currentStep = new ThreadLocal<>();

  public StepRecorder(Database database) { this.database = database; }

  public UUID start(UUID runId, UUID parentStepId, String level, String executorType, String executorName,
                    String label, String toolCallId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "INSERT INTO agent_run_steps (id,run_id,seq,parent_step_id,step_level,executor_type,executor_name,"
            + "label,status,tool_call_id,started_at) VALUES (?,?,?,?,?,?,?,?,'RUNNING',?,NOW())")) {
      p.setBytes(1, Database.uuidBytes(id));
      p.setBytes(2, Database.uuidBytes(runId));
      p.setInt(3, nextSeq(runId, c));
      p.setBytes(4, parentStepId == null ? null : Database.uuidBytes(parentStepId));
      p.setString(5, level);
      p.setString(6, executorType);
      p.setString(7, executorName);
      p.setString(8, label);
      p.setString(9, toolCallId);
      p.executeUpdate();
    }
    return id;
  }

  public void finish(UUID stepId, String status, JsonObject result, String message, long durationMs)
      throws SQLException {
    if (stepId == null) return;
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "UPDATE agent_run_steps SET status=?,result=?,message=?,duration_ms=?,completed_at=NOW() WHERE id=?")) {
      p.setString(1, status);
      p.setString(2, result == null ? null : result.toString());
      p.setString(3, truncate(message));
      p.setLong(4, durationMs);
      p.setBytes(5, Database.uuidBytes(stepId));
      p.executeUpdate();
    }
  }

  /** 主循环执行 subagent 期间标记"当前步骤"，其内部工具调用会以子步骤挂在它下面。 */
  public void setCurrentStepId(UUID stepId) {
    if (stepId == null) currentStep.remove(); else currentStep.set(stepId);
  }

  public UUID currentStepId() { return currentStep.get(); }

  public JsonArray steps(UUID runId) throws SQLException {
    JsonArray rows = new JsonArray();
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "SELECT seq,parent_step_id,step_level,executor_type,executor_name,label,message,status,result,"
            + "tool_call_id,duration_ms,started_at,completed_at FROM agent_run_steps WHERE run_id=? ORDER BY seq")) {
      p.setBytes(1, Database.uuidBytes(runId));
      try (ResultSet rs = p.executeQuery()) {
        while (rs.next()) {
          JsonObject item = new JsonObject();
          item.addProperty("seq", rs.getInt("seq"));
          byte[] parent = rs.getBytes("parent_step_id");
          item.addProperty("parentStepId", parent == null ? null : Database.bytesUuid(parent).toString());
          item.addProperty("stepLevel", rs.getString("step_level"));
          item.addProperty("executorType", rs.getString("executor_type"));
          item.addProperty("executorName", rs.getString("executor_name"));
          item.addProperty("label", rs.getString("label"));
          item.addProperty("message", rs.getString("message"));
          item.addProperty("status", rs.getString("status"));
          String result = rs.getString("result");
          if (result != null) item.add("result", JsonParser.parseString(result));
          item.addProperty("toolCallId", rs.getString("tool_call_id"));
          item.addProperty("durationMs", rs.getLong("duration_ms"));
          item.addProperty("startedAt", String.valueOf(rs.getTimestamp("started_at")));
          java.sql.Timestamp completedAt = rs.getTimestamp("completed_at");
          item.addProperty("completedAt", completedAt == null ? null : String.valueOf(completedAt));
          rows.add(item);
        }
      }
    }
    return rows;
  }

  private int nextSeq(UUID runId, Connection c) throws SQLException {
    try (PreparedStatement p = c.prepareStatement(
        "SELECT COALESCE(MAX(seq),0)+1 FROM agent_run_steps WHERE run_id=?")) {
      p.setBytes(1, Database.uuidBytes(runId));
      try (ResultSet rs = p.executeQuery()) {
        return rs.next() ? rs.getInt(1) : 1;
      }
    }
  }

  private String truncate(String value) {
    if (value == null) return null;
    return value.length() <= 1000 ? value : value.substring(0, 1000);
  }
}
