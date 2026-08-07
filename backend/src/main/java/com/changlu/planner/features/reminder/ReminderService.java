package com.changlu.planner.features.reminder;

import com.changlu.planner.agent.core.ModelClient;
import com.changlu.planner.shared.database.Database;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 提醒的唯一业务入口：把待办提醒写入 outbox，再分别投递到网页和微信。
 * outbox 的 dedup_key 保证修改后的新提醒可以重新生成，已发送的提醒不会重复发送。
 * 微信提醒支持结合长期记忆生成鼓励型个性化文案；模型不可用或生成失败时回退固定模板。
 */
public final class ReminderService {
  private static final Logger LOG = LoggerFactory.getLogger(ReminderService.class);
  private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("M月d日 HH:mm");
  private final Database database;
  private final ModelClient model;
  private final MemoryProvider memoryProvider;

  public ReminderService(Database database) { this(database, null, null); }

  public ReminderService(Database database, ModelClient model, MemoryProvider memoryProvider) {
    this.database = database;
    this.model = model;
    this.memoryProvider = memoryProvider;
  }

  /** 长期记忆读取提供者；允许抛异常，由调用方兜底。 */
  @FunctionalInterface
  public interface MemoryProvider {
    String load(Database.Context context) throws Exception;
  }

  /** 网页轮询到期提醒，并在返回前标记为已投递，避免刷新页面重复弹窗。 */
  public List<JsonObject> dueForWeb(Database.Context context) throws SQLException {
    try (Connection connection = database.connection()) {
      syncOutbox(connection, context, "web");
      List<Outbox> due = claimDue(connection, context.userId(), "web");
      List<JsonObject> result = new ArrayList<>();
      for (Outbox item : due) {
        JsonObject payload = payload(item.payload());
        payload.addProperty("id", item.id().toString());
        markSent(connection, item.id());
        result.add(payload);
      }
      return result;
    }
  }

  /** 微信登录后由后台定时调用；每条提醒只会在 outbox 中成功一次。 */
  public void dispatchWechat(Database.Context context, MessageSender sender) throws SQLException {
    try (Connection connection = database.connection()) {
      syncOutbox(connection, context, "wechat");
      for (Outbox item : claimDue(connection, context.userId(), "wechat")) {
        try {
          sender.send(personalizedMessage(context, item.payload()));
          markSent(connection, item.id());
        } catch (Exception error) {
          markRetry(connection, item.id(), error.getMessage());
        }
      }
    }
  }

  /** 会话令牌恢复后，让之前因令牌缺失而失败的提醒重新进入发送队列。 */
  public void retryMissingContext(Database.Context context) throws SQLException {
    try (Connection connection = database.connection(); PreparedStatement update = connection.prepareStatement(
        "UPDATE notification_outbox SET status='pending', attempts=0, last_error=NULL "
            + "WHERE user_id=? AND channel='wechat' AND status='failed' AND last_error LIKE 'missing latest context token%'")) {
      update.setBytes(1, Database.uuidBytes(context.userId()));
      update.executeUpdate();
    }
  }

  private void syncOutbox(Connection connection, Database.Context context, String channel) throws SQLException {
    // 先取消尚未投递的旧版本，再用当前数据源状态重新生成，处理改期、取消提醒和完成事项。
    try (PreparedStatement cancel = connection.prepareStatement(
        "UPDATE notification_outbox SET status='cancelled' WHERE user_id=? "
            + "AND notification_type IN ('todo_reminder','task_reminder','schedule_reminder') AND channel=? AND status='pending'")) {
      cancel.setBytes(1, Database.uuidBytes(context.userId()));
      cancel.setString(2, channel);
      cancel.executeUpdate();
    }

    upsertTodos(connection, context, channel);
    upsertTasks(connection, context, channel);
    upsertSchedules(connection, context, channel);
  }

  /** 待办提醒：必须显式设置了 reminder_minutes 才会生成提醒。 */
  private void upsertTodos(Connection connection, Database.Context context, String channel) throws SQLException {
    String query = "SELECT id,title,due_at,reminder_minutes FROM todos "
        + "WHERE workspace_id=? AND created_by=? AND status NOT IN ('done','cancelled') "
        + "AND deleted_at IS NULL AND due_at IS NOT NULL AND reminder_minutes IS NOT NULL";
    try (PreparedStatement select = connection.prepareStatement(query)) {
      select.setBytes(1, Database.uuidBytes(context.workspaceId()));
      select.setBytes(2, Database.uuidBytes(context.userId()));
      try (ResultSet rows = select.executeQuery()) {
        while (rows.next()) {
          UUID todoId = Database.bytesUuid(rows.getBytes("id"));
          String title = rows.getString("title");
          LocalDateTime dueTime = rows.getTimestamp("due_at").toLocalDateTime();
          int reminderMinutes = rows.getInt("reminder_minutes");
          JsonObject payload = new JsonObject();
          payload.addProperty("type", "todo_reminder");
          payload.addProperty("todoId", todoId.toString());
          payload.addProperty("title", title);
          payload.addProperty("dueAt", dueTime.toString());
          payload.addProperty("reminderMinutes", reminderMinutes);
          upsert(connection, context, channel, "todo_reminder", payload,
              dueTime.minusMinutes(reminderMinutes),
              "todo:" + todoId + ":" + dueTime + ":" + reminderMinutes + ":" + channel);
        }
      }
    }
  }

  /** 计划任务提醒：未显式设置 reminder_minutes 时默认提前 30 分钟。 */
  private void upsertTasks(Connection connection, Database.Context context, String channel) throws SQLException {
    String query = "SELECT t.id,t.title,t.due_at,COALESCE(t.reminder_minutes,30) AS reminder_minutes "
        + "FROM plan_tasks t JOIN plans p ON p.id=t.plan_id "
        + "WHERE p.workspace_id=? AND t.deleted_at IS NULL AND p.deleted_at IS NULL "
        + "AND t.status NOT IN ('done','cancelled') AND t.due_at IS NOT NULL";
    try (PreparedStatement select = connection.prepareStatement(query)) {
      select.setBytes(1, Database.uuidBytes(context.workspaceId()));
      try (ResultSet rows = select.executeQuery()) {
        while (rows.next()) {
          UUID taskId = Database.bytesUuid(rows.getBytes("id"));
          String title = rows.getString("title");
          LocalDateTime dueTime = rows.getTimestamp("due_at").toLocalDateTime();
          int reminderMinutes = rows.getInt("reminder_minutes");
          JsonObject payload = new JsonObject();
          payload.addProperty("type", "task_reminder");
          payload.addProperty("taskId", taskId.toString());
          payload.addProperty("title", title);
          payload.addProperty("dueAt", dueTime.toString());
          payload.addProperty("reminderMinutes", reminderMinutes);
          upsert(connection, context, channel, "task_reminder", payload,
              dueTime.minusMinutes(reminderMinutes),
              "task:" + taskId + ":" + dueTime + ":" + reminderMinutes + ":" + channel);
        }
      }
    }
  }

  /** 日程提醒：未显式设置 reminder_minutes 时默认到期即提醒（提前 0 分钟）。 */
  private void upsertSchedules(Connection connection, Database.Context context, String channel) throws SQLException {
    String query = "SELECT id,title,start_at,COALESCE(reminder_minutes,0) AS reminder_minutes "
        + "FROM schedule_items WHERE workspace_id=? AND deleted_at IS NULL "
        + "AND status NOT IN ('done','cancelled') AND start_at IS NOT NULL";
    try (PreparedStatement select = connection.prepareStatement(query)) {
      select.setBytes(1, Database.uuidBytes(context.workspaceId()));
      try (ResultSet rows = select.executeQuery()) {
        while (rows.next()) {
          UUID scheduleId = Database.bytesUuid(rows.getBytes("id"));
          String title = rows.getString("title");
          LocalDateTime startTime = rows.getTimestamp("start_at").toLocalDateTime();
          int reminderMinutes = rows.getInt("reminder_minutes");
          JsonObject payload = new JsonObject();
          payload.addProperty("type", "schedule_reminder");
          payload.addProperty("scheduleId", scheduleId.toString());
          payload.addProperty("title", title);
          payload.addProperty("dueAt", startTime.toString());
          payload.addProperty("reminderMinutes", reminderMinutes);
          upsert(connection, context, channel, "schedule_reminder", payload,
              startTime.minusMinutes(reminderMinutes),
              "schedule:" + scheduleId + ":" + startTime + ":" + reminderMinutes + ":" + channel);
        }
      }
    }
  }

  private void upsert(Connection connection, Database.Context context, String channel, String type,
                      JsonObject payload, LocalDateTime scheduledAt, String dedupKey) throws SQLException {
    try (PreparedStatement insert = connection.prepareStatement(
        "INSERT INTO notification_outbox "
            + "(id,user_id,notification_type,payload,channel,status,scheduled_at,dedup_key) "
            + "VALUES (?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE "
            + "payload=VALUES(payload), scheduled_at=VALUES(scheduled_at), "
            + "status=IF(notification_outbox.status='cancelled','pending',notification_outbox.status)")) {
      insert.setBytes(1, Database.uuidBytes(UUID.randomUUID()));
      insert.setBytes(2, Database.uuidBytes(context.userId()));
      insert.setString(3, type);
      insert.setString(4, payload.toString());
      insert.setString(5, channel);
      insert.setString(6, "pending");
      insert.setTimestamp(7, Timestamp.valueOf(scheduledAt));
      insert.setString(8, dedupKey);
      insert.executeUpdate();
    }
  }

  private List<Outbox> claimDue(Connection connection, UUID userId, String channel) throws SQLException {
    List<Outbox> due = new ArrayList<>();
    try (PreparedStatement select = connection.prepareStatement(
        "SELECT id,payload FROM notification_outbox WHERE user_id=? "
            + "AND notification_type IN ('todo_reminder','task_reminder','schedule_reminder') "
            + "AND channel=? AND status='pending' AND scheduled_at <= NOW() AND attempts < 5 "
            + "ORDER BY scheduled_at LIMIT 20")) {
      select.setBytes(1, Database.uuidBytes(userId));
      select.setString(2, channel);
      try (ResultSet rows = select.executeQuery()) {
        while (rows.next()) {
          UUID id = Database.bytesUuid(rows.getBytes("id"));
          try (PreparedStatement claim = connection.prepareStatement(
              "UPDATE notification_outbox SET status='sending', attempts=attempts+1 WHERE id=? AND status='pending'")) {
            claim.setBytes(1, Database.uuidBytes(id));
            if (claim.executeUpdate() == 1) due.add(new Outbox(id, rows.getString("payload")));
          }
        }
      }
    }
    return due;
  }

  private void markSent(Connection connection, UUID id) throws SQLException {
    try (PreparedStatement update = connection.prepareStatement(
        "UPDATE notification_outbox SET status='sent', sent_at=NOW(), last_error=NULL WHERE id=? AND status='sending'")) {
      update.setBytes(1, Database.uuidBytes(id));
      update.executeUpdate();
    }
  }

  private void markRetry(Connection connection, UUID id, String message) throws SQLException {
    try (PreparedStatement update = connection.prepareStatement(
        "UPDATE notification_outbox SET status=IF(attempts >= 5,'failed','pending'), last_error=? WHERE id=? AND status='sending'")) {
      update.setString(1, message == null ? "微信发送失败" : message);
      update.setBytes(2, Database.uuidBytes(id));
      update.executeUpdate();
    }
  }

  private JsonObject payload(String value) {
    return JsonParser.parseString(value).getAsJsonObject();
  }

  private String formatMessage(String value) {
    JsonObject item = payload(value);
    String dueAt = item.get("dueAt").getAsString();
    String display = dueAt;
    try { display = LocalDateTime.parse(dueAt).format(DISPLAY_TIME); } catch (RuntimeException ignored) { }
    return "提醒：" + item.get("title").getAsString() + "\n截止时间：" + display + "\n请打开长路计划完成它。";
  }

  /** 鼓励型个性化提醒：结合长期记忆让模型写一句有温度的话；模型不可用或生成失败时回退固定模板。 */
  private String personalizedMessage(Database.Context context, String value) {
    JsonObject item = payload(value);
    String title = item.get("title").getAsString();
    String display = item.get("dueAt").getAsString();
    try { display = LocalDateTime.parse(item.get("dueAt").getAsString()).format(DISPLAY_TIME); } catch (RuntimeException ignored) { }
    String fallback = formatMessage(value);
    if (model == null || !model.configured()) return fallback;
    try {
      String memory = memoryProvider == null ? "" : safeMemory(memoryProvider, context);
      JsonArray messages = new JsonArray();
      messages.add(ModelClient.message("system", """
          你是长路计划的好友式提醒助手。根据用户的长期记忆和待办信息，生成一句简短、真诚、鼓励型的提醒。
          要求：
          - 自然地包含待办标题和截止时间，让用户知道要做什么、什么时候截止。
          - 语气像关心你的朋友，真诚温暖、有鼓励感；不油腻、不肉麻、不夸张浪漫。
          - 只使用用户长期记忆里明确存在的信息；不知道的事（年龄、称呼等）不要虚构。
          - 不超过 60 字，只输出提醒正文，不要加标题或引号，至多一个轻量表情符号。
          """));
      messages.add(ModelClient.message("user", "用户长期记忆：\n" + (memory.isBlank() ? "（暂无）" : memory)
          + "\n\n待办标题：" + title + "\n截止时间：" + display));
      String text = model.completeText("reminder-encouraging", messages, 0.8, 200, 20, 1).trim();
      if (text.isBlank()) return fallback;
      if (text.length() > 120) text = text.substring(0, 117) + "...";
      return text;
    } catch (Exception error) {
      LOG.warn("[提醒文案生成失败，回退固定模板] 原因={}", error.getMessage());
      return fallback;
    }
  }

  private String safeMemory(MemoryProvider provider, Database.Context context) {
    try { return provider.load(context); } catch (Exception ignored) { return ""; }
  }

  private record Outbox(UUID id, String payload) {}

  @FunctionalInterface
  public interface MessageSender {
    void send(String message) throws Exception;
  }
}
