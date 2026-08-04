package com.changlu.planner.features.reminder;

import com.changlu.planner.shared.database.Database;
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

/**
 * 提醒的唯一业务入口：把待办提醒写入 outbox，再分别投递到网页和微信。
 * outbox 的 dedup_key 保证修改后的新提醒可以重新生成，已发送的提醒不会重复发送。
 */
public final class ReminderService {
  private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("M月d日 HH:mm");
  private final Database database;

  public ReminderService(Database database) { this.database = database; }

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
          sender.send(formatMessage(item.payload()));
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
    // 先取消尚未投递的旧版本，再用当前待办状态重新生成，处理改期、取消提醒和完成待办。
    try (PreparedStatement cancel = connection.prepareStatement(
        "UPDATE notification_outbox SET status='cancelled' WHERE user_id=? AND notification_type='todo_reminder' AND channel=? AND status='pending'")) {
      cancel.setBytes(1, Database.uuidBytes(context.userId()));
      cancel.setString(2, channel);
      cancel.executeUpdate();
    }

    String query = "SELECT id,title,due_at,reminder_minutes FROM todos "
        + "WHERE workspace_id=? AND created_by=? AND status NOT IN ('done','cancelled') "
        + "AND deleted_at IS NULL AND due_at IS NOT NULL AND reminder_minutes IS NOT NULL";
    try (PreparedStatement select = connection.prepareStatement(query);
         PreparedStatement insert = connection.prepareStatement(
             "INSERT INTO notification_outbox "
                 + "(id,user_id,notification_type,payload,channel,status,scheduled_at,dedup_key) "
                 + "VALUES (?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE "
                 + "payload=VALUES(payload), scheduled_at=VALUES(scheduled_at), "
                 + "status=IF(notification_outbox.status='cancelled','pending',notification_outbox.status)")) {
      select.setBytes(1, Database.uuidBytes(context.workspaceId()));
      select.setBytes(2, Database.uuidBytes(context.userId()));
      try (ResultSet rows = select.executeQuery()) {
        while (rows.next()) {
          UUID todoId = Database.bytesUuid(rows.getBytes("id"));
          String title = rows.getString("title");
          Timestamp dueAt = rows.getTimestamp("due_at");
          int reminderMinutes = rows.getInt("reminder_minutes");
          LocalDateTime dueTime = dueAt.toLocalDateTime();
          LocalDateTime remindAt = dueTime.minusMinutes(reminderMinutes);
          JsonObject payload = new JsonObject();
          payload.addProperty("todoId", todoId.toString());
          payload.addProperty("title", title);
          payload.addProperty("dueAt", dueTime.toString());
          payload.addProperty("reminderMinutes", reminderMinutes);
          String dedupKey = "todo:" + todoId + ":" + dueTime + ":" + reminderMinutes + ":" + channel;
          insert.setBytes(1, Database.uuidBytes(UUID.randomUUID()));
          insert.setBytes(2, Database.uuidBytes(context.userId()));
          insert.setString(3, "todo_reminder");
          insert.setString(4, payload.toString());
          insert.setString(5, channel);
          insert.setString(6, "pending");
          insert.setTimestamp(7, Timestamp.valueOf(remindAt));
          insert.setString(8, dedupKey);
          insert.executeUpdate();
        }
      }
    }
  }

  private List<Outbox> claimDue(Connection connection, UUID userId, String channel) throws SQLException {
    List<Outbox> due = new ArrayList<>();
    try (PreparedStatement select = connection.prepareStatement(
        "SELECT id,payload FROM notification_outbox WHERE user_id=? AND notification_type='todo_reminder' "
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

  private record Outbox(UUID id, String payload) {}

  @FunctionalInterface
  public interface MessageSender {
    void send(String message) throws Exception;
  }
}
