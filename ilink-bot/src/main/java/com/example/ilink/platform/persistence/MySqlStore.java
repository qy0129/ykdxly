package com.example.ilink.platform.persistence;

import com.example.ilink.bootstrap.Config;
import com.example.ilink.capabilities.calendar.CalendarEvent;
import com.example.ilink.capabilities.planning.PlanTask;
import com.example.ilink.capabilities.calendar.ReminderDelivery;
import com.example.ilink.capabilities.planning.TaskPlan;
import com.example.ilink.capabilities.planning.TodoItem;
import com.example.ilink.capabilities.memory.UserMemory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 可选的 MySQL 持久化存储。
 *
 * <p>数据库默认关闭；连接失败时仅记录错误，现有内存功能仍可继续运行。</p>
 */
public final class MySqlStore {

    private static final MySqlStore INSTANCE = new MySqlStore();

    private final boolean enabled;
    private volatile boolean available;
    private MySqlStore() {
        enabled = Config.DATABASE_ENABLED;
        if (!enabled) {
            return;
        }

        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            initializeTables();
            available = true;
            System.out.println("[Database] MySQL 已连接，bot_id=" + Config.DATABASE_BOT_ID);
        } catch (Exception e) {
            System.err.println("[Database] MySQL 初始化失败，继续使用内存存储: " + e.getMessage());
        }
    }

    /** 获取整个进程共享的数据库存储。 */
    public static MySqlStore getInstance() {
        return INSTANCE;
    }

    /** 判断数据库是否已启用并成功连接。 */
    public boolean isAvailable() {
        return enabled && available;
    }

    /** 保存一轮用户消息和机器人回复。 */
    public void saveConversation(String userId, String userContent, String assistantContent) {
        if (!isAvailable()) return;

        String sql = "INSERT INTO chat_messages (bot_id, user_id, role, content) VALUES (?, ?, ?, ?)";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            addMessageBatch(statement, userId, "user", userContent);
            addMessageBatch(statement, userId, "assistant", assistantContent);
            statement.executeBatch();
        } catch (SQLException e) {
            logFailure("保存聊天记录", e);
        }
    }

    /** 读取用户最近的聊天消息，并按时间正序返回。 */
    public List<ChatEntry> loadRecentMessages(String userId, int limit) {
        if (!isAvailable()) return List.of();

        String sql = "SELECT role, content FROM ("
                + "SELECT id, role, content FROM chat_messages "
                + "WHERE bot_id = ? AND user_id = ? ORDER BY id DESC LIMIT ?"
                + ") recent ORDER BY id ASC";
        List<ChatEntry> messages = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, Config.DATABASE_BOT_ID);
            statement.setString(2, userId);
            statement.setInt(3, limit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    messages.add(new ChatEntry(result.getString("role"), result.getString("content")));
                }
            }
        } catch (SQLException e) {
            logFailure("读取聊天记录", e);
        }
        return messages;
    }

    /** 读取用户的历史对话摘要。 */
    public String loadConversationSummary(String userId) {
        if (!isAvailable()) return null;

        String sql = "SELECT summary FROM conversation_summaries WHERE bot_id = ? AND user_id = ?";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, Config.DATABASE_BOT_ID);
            statement.setString(2, userId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString("summary") : null;
            }
        } catch (SQLException e) {
            logFailure("读取对话摘要", e);
            return null;
        }
    }

    /** 在同一事务中更新摘要并删除已经压缩的最早消息。 */
    public void saveSummaryAndDeleteOldest(String userId, String summary, int deleteCount) {
        if (!isAvailable()) return;

        String summarySql = "INSERT INTO conversation_summaries (bot_id, user_id, summary) "
                + "VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE summary = ?, updated_at = CURRENT_TIMESTAMP";
        String deleteSql = "DELETE FROM chat_messages WHERE id IN (SELECT id FROM ("
                + "SELECT id FROM chat_messages WHERE bot_id = ? AND user_id = ? "
                + "ORDER BY id ASC LIMIT ?) oldest)";

        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement summaryStatement = connection.prepareStatement(summarySql);
                 PreparedStatement deleteStatement = connection.prepareStatement(deleteSql)) {
                summaryStatement.setString(1, Config.DATABASE_BOT_ID);
                summaryStatement.setString(2, userId);
                summaryStatement.setString(3, summary);
                summaryStatement.setString(4, summary);
                summaryStatement.executeUpdate();

                deleteStatement.setString(1, Config.DATABASE_BOT_ID);
                deleteStatement.setString(2, userId);
                deleteStatement.setInt(3, deleteCount);
                deleteStatement.executeUpdate();
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            logFailure("更新对话摘要", e);
        }
    }

    /** 保存用户当前人设。 */
    public void savePersona(String userId, String persona) {
        if (!isAvailable()) return;

        String sql = "INSERT INTO user_settings (bot_id, user_id, persona) VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE persona = ?, updated_at = CURRENT_TIMESTAMP";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, Config.DATABASE_BOT_ID);
            statement.setString(2, userId);
            statement.setString(3, persona);
            statement.setString(4, persona);
            statement.executeUpdate();
        } catch (SQLException e) {
            logFailure("保存用户人设", e);
        }
    }

    /** 读取用户上次选择的人设。 */
    public String loadPersona(String userId) {
        if (!isAvailable()) return null;

        String sql = "SELECT persona FROM user_settings WHERE bot_id = ? AND user_id = ?";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, Config.DATABASE_BOT_ID);
            statement.setString(2, userId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString("persona") : null;
            }
        } catch (SQLException e) {
            logFailure("读取用户人设", e);
        return null;
    }
    }

    /** 保存日历事件；使用事件 ID 覆盖写入，供提醒调度器更新下一次触发时间。 */
    public void saveCalendarEvent(CalendarEvent event) {
        if (!isAvailable()) return;
        String sql = "INSERT INTO calendar_events (id, bot_id, user_id, title, type, start_at, next_reminder_at, "
                + "recurrence, recurrence_anchor, reminder_minutes, status, group_id, source, notes, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE title=?, type=?, start_at=?, next_reminder_at=?, recurrence=?, "
                + "recurrence_anchor=?, reminder_minutes=?, status=?, group_id=?, source=?, notes=?";
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, event.id());
            statement.setString(2, Config.DATABASE_BOT_ID);
            statement.setString(3, event.userId());
            statement.setString(4, event.title());
            statement.setString(5, event.type());
            statement.setTimestamp(6, Timestamp.valueOf(event.startAt()));
            setTimestamp(statement, 7, event.nextReminderAt());
            statement.setString(8, event.recurrence());
            statement.setString(9, event.recurrenceAnchor());
            statement.setInt(10, event.reminderMinutes());
            statement.setString(11, event.status());
            statement.setString(12, event.groupId());
            statement.setString(13, event.source());
            statement.setString(14, event.notes());
            statement.setTimestamp(15, Timestamp.valueOf(event.createdAt()));
            statement.setString(16, event.title());
            statement.setString(17, event.type());
            statement.setTimestamp(18, Timestamp.valueOf(event.startAt()));
            setTimestamp(statement, 19, event.nextReminderAt());
            statement.setString(20, event.recurrence());
            statement.setString(21, event.recurrenceAnchor());
            statement.setInt(22, event.reminderMinutes());
            statement.setString(23, event.status());
            statement.setString(24, event.groupId());
            statement.setString(25, event.source());
            statement.setString(26, event.notes());
            statement.executeUpdate();
        } catch (SQLException e) {
            logFailure("保存日历事件", e);
        }
    }

    /** 启动时恢复当前机器人保存的日历事件，确保重启不会丢失提醒。 */
    public List<CalendarEvent> loadCalendarEvents() {
        if (!isAvailable()) return List.of();
        String sql = "SELECT id, user_id, title, type, start_at, next_reminder_at, recurrence, recurrence_anchor, "
                + "reminder_minutes, status, group_id, source, notes, created_at FROM calendar_events WHERE bot_id = ?";
        List<CalendarEvent> events = new ArrayList<>();
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, Config.DATABASE_BOT_ID);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    events.add(new CalendarEvent(result.getString("id"), result.getString("user_id"),
                            result.getString("title"), result.getString("type"),
                            result.getTimestamp("start_at").toLocalDateTime(),
                            toLocalDateTime(result.getTimestamp("next_reminder_at")),
                            result.getString("recurrence"), result.getString("recurrence_anchor"),
                            result.getInt("reminder_minutes"), result.getString("status"),
                            result.getString("group_id"), result.getString("source"), result.getString("notes"),
                            result.getTimestamp("created_at").toLocalDateTime()));
                }
            }
        } catch (SQLException e) {
            logFailure("读取日历事件", e);
        }
        return events;
    }

    /** 单独保存一条消息，供统一的入站和出站记录链路使用。 */
    public void saveMessage(String userId, String role, String content) {
        if (!isAvailable() || userId == null || userId.isBlank()
                || content == null || content.isBlank()) return;
        String sql = "INSERT INTO chat_messages (bot_id, user_id, role, content) VALUES (?, ?, ?, ?)";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, Config.DATABASE_BOT_ID);
            statement.setString(2, userId);
            statement.setString(3, role);
            statement.setString(4, content);
            statement.executeUpdate();
        } catch (SQLException e) {
            logFailure("保存聊天消息", e);
        }
    }

    /** 保存可跨重启恢复的轻量用户状态。 */
    public void saveUserState(String userId, String key, String value) {
        if (!isAvailable() || userId == null || userId.isBlank()
                || key == null || key.isBlank() || value == null || value.isBlank()) return;
        String sql = "INSERT INTO user_states (bot_id, user_id, state_key, state_value) VALUES (?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE state_value=?, updated_at=CURRENT_TIMESTAMP";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, Config.DATABASE_BOT_ID);
            statement.setString(2, userId);
            statement.setString(3, key);
            statement.setString(4, value);
            statement.setString(5, value);
            statement.executeUpdate();
        } catch (SQLException e) {
            logFailure("保存用户状态", e);
        }
    }

    /** 读取最近保存的轻量用户状态。 */
    public String loadUserState(String userId, String key) {
        if (!isAvailable()) return "";
        String sql = "SELECT state_value FROM user_states WHERE bot_id=? AND user_id=? AND state_key=?";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, Config.DATABASE_BOT_ID);
            statement.setString(2, userId);
            statement.setString(3, key);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString("state_value") : "";
            }
        } catch (SQLException e) {
            logFailure("读取用户状态", e);
            return "";
        }
    }

    /** 删除已经完成或取消的轻量用户状态。 */
    public void deleteUserState(String userId, String key) {
        if (!isAvailable() || userId == null || userId.isBlank()
                || key == null || key.isBlank()) return;
        String sql = "DELETE FROM user_states WHERE bot_id=? AND user_id=? AND state_key=?";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, Config.DATABASE_BOT_ID);
            statement.setString(2, userId);
            statement.setString(3, key);
            statement.executeUpdate();
        } catch (SQLException e) {
            logFailure("删除用户状态", e);
        }
    }

    /** 在同一事务中保存计划及其全部任务。 */
    public void saveTaskPlan(String userId, TaskPlan plan) {
        if (!isAvailable()) return;
        String planSql = "INSERT INTO plans (id, bot_id, user_id, goal, deadline, available_time, created_date, status) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE goal=?, deadline=?, available_time=?, "
                + "status=?, updated_at=CURRENT_TIMESTAMP";
        String deleteTasksSql = "DELETE FROM plan_tasks WHERE bot_id=? AND plan_id=?";
        String taskSql = "INSERT INTO plan_tasks (id, plan_id, bot_id, user_id, title, description, "
                + "estimated_minutes, priority, scheduled_date, status, sort_order) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement planStatement = connection.prepareStatement(planSql);
                 PreparedStatement deleteStatement = connection.prepareStatement(deleteTasksSql);
                 PreparedStatement taskStatement = connection.prepareStatement(taskSql)) {
                String status = plan.tasks().isEmpty() || plan.completedCount() < plan.tasks().size()
                        ? "active" : "completed";
                planStatement.setString(1, plan.id());
                planStatement.setString(2, Config.DATABASE_BOT_ID);
                planStatement.setString(3, userId);
                planStatement.setString(4, plan.goal());
                planStatement.setDate(5, java.sql.Date.valueOf(plan.deadline()));
                planStatement.setString(6, plan.availableTime());
                planStatement.setDate(7, java.sql.Date.valueOf(plan.createdDate()));
                planStatement.setString(8, status);
                planStatement.setString(9, plan.goal());
                planStatement.setDate(10, java.sql.Date.valueOf(plan.deadline()));
                planStatement.setString(11, plan.availableTime());
                planStatement.setString(12, status);
                planStatement.executeUpdate();

                deleteStatement.setString(1, Config.DATABASE_BOT_ID);
                deleteStatement.setString(2, plan.id());
                deleteStatement.executeUpdate();

                for (int index = 0; index < plan.tasks().size(); index++) {
                    PlanTask task = plan.tasks().get(index);
                    taskStatement.setString(1, task.id());
                    taskStatement.setString(2, plan.id());
                    taskStatement.setString(3, Config.DATABASE_BOT_ID);
                    taskStatement.setString(4, userId);
                    taskStatement.setString(5, task.title());
                    taskStatement.setString(6, task.description());
                    taskStatement.setInt(7, task.estimatedMinutes());
                    taskStatement.setString(8, task.priority());
                    if (task.scheduledDate().isBlank()) taskStatement.setNull(9, java.sql.Types.DATE);
                    else taskStatement.setDate(9, java.sql.Date.valueOf(task.scheduledDate()));
                    taskStatement.setString(10, task.status());
                    taskStatement.setInt(11, index);
                    taskStatement.addBatch();
                }
                taskStatement.executeBatch();
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        } catch (Exception e) {
            logFailure("保存任务计划", e);
        }
    }

    /** 读取用户最近一份仍在执行的计划，没有活动计划时返回最近一份历史计划。 */
    public TaskPlan loadCurrentTaskPlan(String userId) {
        if (!isAvailable()) return null;
        String planSql = "SELECT id, goal, deadline, available_time, created_date FROM plans "
                + "WHERE bot_id=? AND user_id=? ORDER BY (status='active') DESC, updated_at DESC LIMIT 1";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(planSql)) {
            statement.setString(1, Config.DATABASE_BOT_ID);
            statement.setString(2, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                String planId = result.getString("id");
                return new TaskPlan(planId, result.getString("goal"),
                        result.getDate("deadline").toLocalDate().toString(),
                        result.getString("available_time"),
                        result.getDate("created_date").toLocalDate().toString(),
                        loadPlanTasks(connection, planId));
            }
        } catch (SQLException e) {
            logFailure("读取任务计划", e);
            return null;
        }
    }

    private List<PlanTask> loadPlanTasks(Connection connection, String planId) throws SQLException {
        String sql = "SELECT id, title, description, estimated_minutes, priority, scheduled_date, status "
                + "FROM plan_tasks WHERE bot_id=? AND plan_id=? ORDER BY sort_order, id";
        List<PlanTask> tasks = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, Config.DATABASE_BOT_ID);
            statement.setString(2, planId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    java.sql.Date date = result.getDate("scheduled_date");
                    tasks.add(new PlanTask(result.getString("id"), result.getString("title"),
                            result.getString("description"), result.getInt("estimated_minutes"),
                            result.getString("priority"), date == null ? "" : date.toLocalDate().toString(),
                            result.getString("status")));
                }
            }
        }
        return tasks;
    }

    /** 保存计划任务与日历事件的关联。 */
    public void linkPlanTaskToCalendar(String taskId, String calendarEventId) {
        if (!isAvailable()) return;
        String sql = "INSERT INTO task_calendar_links (task_id, calendar_event_id, bot_id) VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE calendar_event_id=?";
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, taskId);
            statement.setString(2, calendarEventId);
            statement.setString(3, Config.DATABASE_BOT_ID);
            statement.setString(4, calendarEventId);
            statement.executeUpdate();
        } catch (SQLException e) {
            logFailure("保存计划日历关联", e);
        }
    }

    public String loadCalendarEventIdForTask(String taskId) {
        if (!isAvailable()) return "";
        String sql = "SELECT calendar_event_id FROM task_calendar_links WHERE bot_id=? AND task_id=?";
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, Config.DATABASE_BOT_ID);
            statement.setString(2, taskId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString("calendar_event_id") : "";
            }
        } catch (SQLException e) {
            logFailure("读取计划日历关联", e);
            return "";
        }
    }

    public void deletePlanTaskCalendarLink(String taskId) {
        if (!isAvailable()) return;
        String sql = "DELETE FROM task_calendar_links WHERE bot_id=? AND task_id=?";
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, Config.DATABASE_BOT_ID);
            statement.setString(2, taskId);
            statement.executeUpdate();
        } catch (SQLException e) {
            logFailure("删除计划日历关联", e);
        }
    }

    /** 保存或更新一条待办。 */
    public void saveTodo(TodoItem todo) {
        if (!isAvailable()) return;
        String sql = "INSERT INTO todos (id, bot_id, user_id, title, due_at, status, calendar_event_id, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE title=?, due_at=?, status=?, "
                + "calendar_event_id=?, updated_at=?";
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, todo.id());
            statement.setString(2, Config.DATABASE_BOT_ID);
            statement.setString(3, todo.userId());
            statement.setString(4, todo.title());
            setTimestamp(statement, 5, todo.dueAt());
            statement.setString(6, todo.status());
            statement.setString(7, todo.calendarEventId());
            statement.setTimestamp(8, Timestamp.valueOf(todo.createdAt()));
            statement.setTimestamp(9, Timestamp.valueOf(todo.updatedAt()));
            statement.setString(10, todo.title());
            setTimestamp(statement, 11, todo.dueAt());
            statement.setString(12, todo.status());
            statement.setString(13, todo.calendarEventId());
            statement.setTimestamp(14, Timestamp.valueOf(todo.updatedAt()));
            statement.executeUpdate();
        } catch (SQLException e) {
            logFailure("保存待办", e);
        }
    }

    /** 读取用户全部待办，状态筛选交给领域服务处理。 */
    public List<TodoItem> loadTodos(String userId) {
        if (!isAvailable()) return List.of();
        String sql = "SELECT id, title, due_at, status, calendar_event_id, created_at, updated_at "
                + "FROM todos WHERE bot_id=? AND user_id=? ORDER BY created_at";
        List<TodoItem> todos = new ArrayList<>();
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, Config.DATABASE_BOT_ID);
            statement.setString(2, userId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    todos.add(new TodoItem(result.getString("id"), userId, result.getString("title"),
                            toLocalDateTime(result.getTimestamp("due_at")), result.getString("status"),
                            result.getString("calendar_event_id"),
                            result.getTimestamp("created_at").toLocalDateTime(),
                            result.getTimestamp("updated_at").toLocalDateTime()));
                }
            }
        } catch (SQLException e) {
            logFailure("读取待办", e);
        }
        return todos;
    }

    public void saveReminderDelivery(ReminderDelivery delivery) {
        if (!isAvailable()) return;
        String sql = "INSERT INTO reminder_deliveries (id, bot_id, event_id, user_id, scheduled_at, status, retry_count, "
                + "next_retry_at, sent_at, error_message, dedup_key, locked_until) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE status=?, retry_count=?, next_retry_at=?, sent_at=?, error_message=?, locked_until=?";
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, delivery.id());
            statement.setString(2, Config.DATABASE_BOT_ID);
            statement.setString(3, delivery.eventId());
            statement.setString(4, delivery.userId());
            statement.setTimestamp(5, Timestamp.valueOf(delivery.scheduledAt()));
            statement.setString(6, delivery.status());
            statement.setInt(7, delivery.retryCount());
            setTimestamp(statement, 8, delivery.nextRetryAt());
            setTimestamp(statement, 9, delivery.sentAt());
            statement.setString(10, delivery.errorMessage());
            statement.setString(11, delivery.dedupKey());
            setTimestamp(statement, 12, delivery.lockedUntil());
            statement.setString(13, delivery.status());
            statement.setInt(14, delivery.retryCount());
            setTimestamp(statement, 15, delivery.nextRetryAt());
            setTimestamp(statement, 16, delivery.sentAt());
            statement.setString(17, delivery.errorMessage());
            setTimestamp(statement, 18, delivery.lockedUntil());
            statement.executeUpdate();
        } catch (SQLException e) {
            logFailure("保存提醒投递", e);
        }
    }

    /** 启动时恢复未完成投递，发送中的过期租约也会由领取逻辑接管。 */
    public List<ReminderDelivery> loadActiveReminderDeliveries() {
        if (!isAvailable()) return List.of();
        String sql = "SELECT id, event_id, user_id, scheduled_at, status, retry_count, next_retry_at, sent_at, "
                + "error_message, dedup_key, locked_until FROM reminder_deliveries WHERE bot_id=? "
                + "AND status IN ('pending','failed','sending')";
        List<ReminderDelivery> deliveries = new ArrayList<>();
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, Config.DATABASE_BOT_ID);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    deliveries.add(new ReminderDelivery(result.getString("id"), result.getString("event_id"),
                            result.getString("user_id"), result.getTimestamp("scheduled_at").toLocalDateTime(),
                            result.getString("status"), result.getInt("retry_count"),
                            toLocalDateTime(result.getTimestamp("next_retry_at")),
                            toLocalDateTime(result.getTimestamp("sent_at")), result.getString("error_message"),
                            result.getString("dedup_key"), toLocalDateTime(result.getTimestamp("locked_until"))));
                }
            }
        } catch (SQLException e) {
            logFailure("读取提醒投递", e);
        }
        return deliveries;
    }

    public boolean reminderDeliveryExists(String dedupKey) {
        if (!isAvailable()) return false;
        String sql = "SELECT 1 FROM reminder_deliveries WHERE bot_id=? AND dedup_key=? LIMIT 1";
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, Config.DATABASE_BOT_ID);
            statement.setString(2, dedupKey);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        } catch (SQLException e) {
            logFailure("检查提醒去重键", e);
            return false;
        }
    }

    public void saveMemory(UserMemory memory) {
        if (!isAvailable()) return;
        String sql = "INSERT INTO user_memories (id, bot_id, user_id, memory_type, memory_key, memory_value, source, "
                + "confidence, status, created_at, updated_at, last_used_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE memory_value=?, source=?, confidence=?, status='active', "
                + "updated_at=?, last_used_at=?";
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, memory.id());
            statement.setString(2, Config.DATABASE_BOT_ID);
            statement.setString(3, memory.userId());
            statement.setString(4, memory.type());
            statement.setString(5, memory.key());
            statement.setString(6, memory.value());
            statement.setString(7, memory.source());
            statement.setDouble(8, memory.confidence());
            statement.setString(9, memory.status());
            statement.setTimestamp(10, Timestamp.valueOf(memory.createdAt()));
            statement.setTimestamp(11, Timestamp.valueOf(memory.updatedAt()));
            setTimestamp(statement, 12, memory.lastUsedAt());
            statement.setString(13, memory.value());
            statement.setString(14, memory.source());
            statement.setDouble(15, memory.confidence());
            statement.setTimestamp(16, Timestamp.valueOf(memory.updatedAt()));
            setTimestamp(statement, 17, memory.lastUsedAt());
            statement.executeUpdate();
        } catch (SQLException e) {
            logFailure("保存个人记忆", e);
        }
    }

    public List<UserMemory> loadMemories(String userId) {
        if (!isAvailable()) return List.of();
        String sql = "SELECT id, memory_type, memory_key, memory_value, source, confidence, status, created_at, "
                + "updated_at, last_used_at FROM user_memories WHERE bot_id=? AND user_id=? AND status='active' "
                + "ORDER BY updated_at DESC LIMIT 100";
        List<UserMemory> memories = new ArrayList<>();
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, Config.DATABASE_BOT_ID);
            statement.setString(2, userId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    memories.add(new UserMemory(result.getString("id"), userId, result.getString("memory_type"),
                            result.getString("memory_key"), result.getString("memory_value"), result.getString("source"),
                            result.getDouble("confidence"), result.getString("status"),
                            result.getTimestamp("created_at").toLocalDateTime(),
                            result.getTimestamp("updated_at").toLocalDateTime(),
                            toLocalDateTime(result.getTimestamp("last_used_at"))));
                }
            }
        } catch (SQLException e) {
            logFailure("读取个人记忆", e);
        }
        return memories;
    }

    public int forgetMemories(String userId, String keyword) {
        if (!isAvailable()) return 0;
        String sql = "UPDATE user_memories SET status='deleted', updated_at=CURRENT_TIMESTAMP "
                + "WHERE bot_id=? AND user_id=? AND status='active' AND (memory_key LIKE ? OR memory_value LIKE ?)";
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            String like = "%" + keyword + "%";
            statement.setString(1, Config.DATABASE_BOT_ID);
            statement.setString(2, userId);
            statement.setString(3, like);
            statement.setString(4, like);
            return statement.executeUpdate();
        } catch (SQLException e) {
            logFailure("删除个人记忆", e);
            return 0;
        }
    }

    /** 创建一个绑定到当前 bot 的自定义扫码二维码。 */
    private void initializeTables() throws SQLException {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS chat_messages ("
                    + "id BIGINT PRIMARY KEY AUTO_INCREMENT,"
                    + "bot_id VARCHAR(128) NOT NULL,"
                    + "user_id VARCHAR(255) NOT NULL,"
                    + "role VARCHAR(16) NOT NULL,"
                    + "content LONGTEXT NOT NULL,"
                    + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + "INDEX idx_chat_user_time (bot_id, user_id, id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS conversation_summaries ("
                    + "bot_id VARCHAR(128) NOT NULL,"
                    + "user_id VARCHAR(255) NOT NULL,"
                    + "summary LONGTEXT NOT NULL,"
                    + "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    + "PRIMARY KEY (bot_id, user_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS user_settings ("
                    + "bot_id VARCHAR(128) NOT NULL,"
                    + "user_id VARCHAR(255) NOT NULL,"
                    + "persona VARCHAR(100),"
                    + "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    + "PRIMARY KEY (bot_id, user_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS user_states ("
                    + "bot_id VARCHAR(128) NOT NULL,"
                    + "user_id VARCHAR(255) NOT NULL,"
                    + "state_key VARCHAR(100) NOT NULL,"
                    + "state_value TEXT NOT NULL,"
                    + "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    + "PRIMARY KEY (bot_id, user_id, state_key)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS calendar_events ("
                    + "id VARCHAR(64) PRIMARY KEY,"
                    + "bot_id VARCHAR(128) NOT NULL,"
                    + "user_id VARCHAR(255) NOT NULL,"
                    + "title VARCHAR(500) NOT NULL,"
                    + "type VARCHAR(32) NOT NULL,"
                    + "start_at DATETIME NOT NULL,"
                    + "next_reminder_at DATETIME NULL,"
                    + "recurrence VARCHAR(16) NOT NULL,"
                    + "recurrence_anchor VARCHAR(16) NOT NULL DEFAULT '',"
                    + "reminder_minutes INT NOT NULL DEFAULT 0,"
                    + "status VARCHAR(16) NOT NULL,"
                    + "group_id VARCHAR(64) NOT NULL DEFAULT '',"
                    + "source VARCHAR(32) NOT NULL DEFAULT '',"
                    + "notes TEXT NULL,"
                    + "created_at DATETIME NOT NULL,"
                    + "INDEX idx_calendar_due (bot_id, status, next_reminder_at),"
                    + "INDEX idx_calendar_user_time (bot_id, user_id, start_at)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            ensureColumn(connection, statement, "calendar_events", "recurrence_anchor",
                    "VARCHAR(16) NOT NULL DEFAULT '' AFTER recurrence");
            ensureColumn(connection, statement, "calendar_events", "group_id",
                    "VARCHAR(64) NOT NULL DEFAULT '' AFTER status");
            ensureColumn(connection, statement, "calendar_events", "source",
                    "VARCHAR(32) NOT NULL DEFAULT '' AFTER group_id");
            statement.executeUpdate("UPDATE calendar_events SET recurrence_anchor=DAY(start_at) "
                    + "WHERE recurrence='monthly' AND recurrence_anchor=''");
            statement.executeUpdate("UPDATE calendar_events SET recurrence_anchor=CONCAT(MONTH(start_at), '-', DAY(start_at)) "
                    + "WHERE recurrence='yearly' AND recurrence_anchor=''");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS plans ("
                    + "id VARCHAR(64) PRIMARY KEY,"
                    + "bot_id VARCHAR(128) NOT NULL,"
                    + "user_id VARCHAR(255) NOT NULL,"
                    + "goal VARCHAR(1000) NOT NULL,"
                    + "deadline DATE NOT NULL,"
                    + "available_time VARCHAR(500) NOT NULL DEFAULT '',"
                    + "created_date DATE NOT NULL,"
                    + "status VARCHAR(16) NOT NULL DEFAULT 'active',"
                    + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    + "INDEX idx_plans_user_status (bot_id, user_id, status, updated_at)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS plan_tasks ("
                    + "id VARCHAR(64) PRIMARY KEY,"
                    + "plan_id VARCHAR(64) NOT NULL,"
                    + "bot_id VARCHAR(128) NOT NULL,"
                    + "user_id VARCHAR(255) NOT NULL,"
                    + "title VARCHAR(500) NOT NULL,"
                    + "description TEXT NULL,"
                    + "estimated_minutes INT NOT NULL,"
                    + "priority VARCHAR(16) NOT NULL,"
                    + "scheduled_date DATE NULL,"
                    + "status VARCHAR(16) NOT NULL DEFAULT 'pending',"
                    + "sort_order INT NOT NULL DEFAULT 0,"
                    + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    + "INDEX idx_plan_tasks_plan (bot_id, plan_id, sort_order),"
                    + "INDEX idx_plan_tasks_user_status (bot_id, user_id, status, scheduled_date)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS task_calendar_links ("
                    + "task_id VARCHAR(64) PRIMARY KEY,"
                    + "calendar_event_id VARCHAR(64) NOT NULL,"
                    + "bot_id VARCHAR(128) NOT NULL,"
                    + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + "UNIQUE KEY uk_task_calendar_event (bot_id, calendar_event_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS todos ("
                    + "id VARCHAR(64) PRIMARY KEY,"
                    + "bot_id VARCHAR(128) NOT NULL,"
                    + "user_id VARCHAR(255) NOT NULL,"
                    + "title VARCHAR(500) NOT NULL,"
                    + "due_at DATETIME NULL,"
                    + "status VARCHAR(16) NOT NULL DEFAULT 'pending',"
                    + "calendar_event_id VARCHAR(64) NULL,"
                    + "created_at DATETIME NOT NULL,"
                    + "updated_at DATETIME NOT NULL,"
                    + "INDEX idx_todos_user_status (bot_id, user_id, status, due_at)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS reminder_deliveries ("
                    + "id VARCHAR(64) PRIMARY KEY,"
                    + "bot_id VARCHAR(128) NOT NULL,"
                    + "event_id VARCHAR(64) NOT NULL,"
                    + "user_id VARCHAR(255) NOT NULL,"
                    + "scheduled_at DATETIME NOT NULL,"
                    + "status VARCHAR(16) NOT NULL,"
                    + "retry_count INT NOT NULL DEFAULT 0,"
                    + "next_retry_at DATETIME NULL,"
                    + "sent_at DATETIME NULL,"
                    + "error_message TEXT NULL,"
                    + "dedup_key VARCHAR(255) NOT NULL,"
                    + "locked_until DATETIME NULL,"
                    + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    + "UNIQUE KEY uk_reminder_dedup (bot_id, dedup_key),"
                    + "INDEX idx_reminder_due (bot_id, status, scheduled_at, next_retry_at)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS user_memories ("
                    + "id VARCHAR(64) PRIMARY KEY,"
                    + "bot_id VARCHAR(128) NOT NULL,"
                    + "user_id VARCHAR(255) NOT NULL,"
                    + "memory_type VARCHAR(32) NOT NULL,"
                    + "memory_key VARCHAR(128) NOT NULL,"
                    + "memory_value TEXT NOT NULL,"
                    + "source TEXT NULL,"
                    + "confidence DOUBLE NOT NULL DEFAULT 1,"
                    + "status VARCHAR(16) NOT NULL DEFAULT 'active',"
                    + "created_at DATETIME NOT NULL,"
                    + "updated_at DATETIME NOT NULL,"
                    + "last_used_at DATETIME NULL,"
                    + "UNIQUE KEY uk_user_memory_key (bot_id, user_id, memory_key),"
                    + "INDEX idx_user_memories (bot_id, user_id, status, updated_at)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        }
    }

    private void ensureColumn(Connection connection, Statement statement, String table,
                              String column, String definition) throws SQLException {
        try (ResultSet columns = connection.getMetaData().getColumns(connection.getCatalog(), null, table, column)) {
            if (!columns.next()) statement.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    private void setTimestamp(PreparedStatement statement, int index, LocalDateTime value) throws SQLException {
        if (value == null) statement.setNull(index, java.sql.Types.TIMESTAMP);
        else statement.setTimestamp(index, Timestamp.valueOf(value));
    }

    private LocalDateTime toLocalDateTime(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }

    Connection openConnection() throws SQLException {
        return DriverManager.getConnection(
                Config.DATABASE_URL, Config.DATABASE_USERNAME, Config.DATABASE_PASSWORD);
    }

    private void addMessageBatch(PreparedStatement statement, String userId,
                                 String role, String content) throws SQLException {
        statement.setString(1, Config.DATABASE_BOT_ID);
        statement.setString(2, userId);
        statement.setString(3, role);
        statement.setString(4, content);
        statement.addBatch();
    }

    private void logFailure(String action, Exception error) {
        System.err.println("[Database] " + action + "失败，继续使用内存数据: " + error.getMessage());
    }

    /** 数据库中的一条聊天消息。 */
    public record ChatEntry(String role, String content) {
    }

}
