package com.example.ilink.storage;

import com.example.ilink.config.Config;
import com.example.ilink.model.CalendarEvent;

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
                + "recurrence, reminder_minutes, status, notes, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE title=?, type=?, start_at=?, next_reminder_at=?, recurrence=?, "
                + "reminder_minutes=?, status=?, notes=?";
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, event.id());
            statement.setString(2, Config.DATABASE_BOT_ID);
            statement.setString(3, event.userId());
            statement.setString(4, event.title());
            statement.setString(5, event.type());
            statement.setTimestamp(6, Timestamp.valueOf(event.startAt()));
            setTimestamp(statement, 7, event.nextReminderAt());
            statement.setString(8, event.recurrence());
            statement.setInt(9, event.reminderMinutes());
            statement.setString(10, event.status());
            statement.setString(11, event.notes());
            statement.setTimestamp(12, Timestamp.valueOf(event.createdAt()));
            statement.setString(13, event.title());
            statement.setString(14, event.type());
            statement.setTimestamp(15, Timestamp.valueOf(event.startAt()));
            setTimestamp(statement, 16, event.nextReminderAt());
            statement.setString(17, event.recurrence());
            statement.setInt(18, event.reminderMinutes());
            statement.setString(19, event.status());
            statement.setString(20, event.notes());
            statement.executeUpdate();
        } catch (SQLException e) {
            logFailure("保存日历事件", e);
        }
    }

    /** 启动时恢复当前机器人保存的日历事件，确保重启不会丢失提醒。 */
    public List<CalendarEvent> loadCalendarEvents() {
        if (!isAvailable()) return List.of();
        String sql = "SELECT id, user_id, title, type, start_at, next_reminder_at, recurrence, reminder_minutes, "
                + "status, notes, created_at FROM calendar_events WHERE bot_id = ?";
        List<CalendarEvent> events = new ArrayList<>();
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, Config.DATABASE_BOT_ID);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    events.add(new CalendarEvent(result.getString("id"), result.getString("user_id"),
                            result.getString("title"), result.getString("type"),
                            result.getTimestamp("start_at").toLocalDateTime(),
                            toLocalDateTime(result.getTimestamp("next_reminder_at")),
                            result.getString("recurrence"), result.getInt("reminder_minutes"),
                            result.getString("status"), result.getString("notes"),
                            result.getTimestamp("created_at").toLocalDateTime()));
                }
            }
        } catch (SQLException e) {
            logFailure("读取日历事件", e);
        }
        return events;
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
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS calendar_events ("
                    + "id VARCHAR(64) PRIMARY KEY,"
                    + "bot_id VARCHAR(128) NOT NULL,"
                    + "user_id VARCHAR(255) NOT NULL,"
                    + "title VARCHAR(500) NOT NULL,"
                    + "type VARCHAR(32) NOT NULL,"
                    + "start_at DATETIME NOT NULL,"
                    + "next_reminder_at DATETIME NULL,"
                    + "recurrence VARCHAR(16) NOT NULL,"
                    + "reminder_minutes INT NOT NULL DEFAULT 0,"
                    + "status VARCHAR(16) NOT NULL,"
                    + "notes TEXT NULL,"
                    + "created_at DATETIME NOT NULL,"
                    + "INDEX idx_calendar_due (bot_id, status, next_reminder_at),"
                    + "INDEX idx_calendar_user_time (bot_id, user_id, start_at)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        }
    }

    private void setTimestamp(PreparedStatement statement, int index, LocalDateTime value) throws SQLException {
        if (value == null) statement.setNull(index, java.sql.Types.TIMESTAMP);
        else statement.setTimestamp(index, Timestamp.valueOf(value));
    }

    private LocalDateTime toLocalDateTime(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }

    private Connection openConnection() throws SQLException {
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

    private void logFailure(String action, SQLException error) {
        System.err.println("[Database] " + action + "失败，继续使用内存数据: " + error.getMessage());
    }

    /** 数据库中的一条聊天消息。 */
    public record ChatEntry(String role, String content) {
    }

}
