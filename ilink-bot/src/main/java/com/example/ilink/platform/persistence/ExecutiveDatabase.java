package com.example.ilink.platform.persistence;

import com.example.ilink.application.executive.ApprovalRequest;
import com.example.ilink.application.executive.ExecutionLog;
import com.example.ilink.application.executive.ExecutiveStep;
import com.example.ilink.application.executive.ExecutiveTask;
import com.example.ilink.application.executive.OutboxMessage;
import com.example.ilink.application.executive.RiskLevel;
import com.example.ilink.application.executive.ScheduleRule;
import com.example.ilink.application.executive.StepStatus;
import com.example.ilink.application.executive.TaskStatus;
import com.example.ilink.bootstrap.Config;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Executive Core 的 MySQL 持久化实现。 */
public final class ExecutiveDatabase {
    private final MySqlStore database;
    private final Gson gson = new Gson();

    public ExecutiveDatabase() {
        this(MySqlStore.getInstance());
    }

    /** 创建不连接数据库的实例，供隔离测试和显式内存运行使用。 */
    public static ExecutiveDatabase disabled() {
        return new ExecutiveDatabase(null);
    }

    ExecutiveDatabase(MySqlStore database) {
        this.database = database;
        if (available()) initializeTables();
    }

    public boolean available() {
        return database != null && database.isAvailable();
    }

    public void saveTask(ExecutiveTask task) {
        if (!available()) return;
        String sql = "INSERT INTO executive_tasks (id, bot_id, user_id, goal, source_type, source_id, dedup_key, "
                + "status, priority, deadline_at, next_run_at, schedule_rule, current_step, plan_version, "
                + "retry_count, max_retries, last_error, lock_owner, locked_until, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE goal=VALUES(goal), status=VALUES(status), priority=VALUES(priority), "
                + "deadline_at=VALUES(deadline_at), next_run_at=VALUES(next_run_at), "
                + "schedule_rule=VALUES(schedule_rule), current_step=VALUES(current_step), "
                + "plan_version=VALUES(plan_version), retry_count=VALUES(retry_count), "
                + "max_retries=VALUES(max_retries), last_error=VALUES(last_error), "
                + "lock_owner=VALUES(lock_owner), locked_until=VALUES(locked_until), updated_at=VALUES(updated_at)";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, task.id());
            statement.setString(2, Config.DATABASE_BOT_ID);
            statement.setString(3, task.userId());
            statement.setString(4, task.goal());
            statement.setString(5, task.sourceType());
            statement.setString(6, task.sourceId());
            statement.setString(7, task.dedupKey());
            statement.setString(8, task.status().name());
            statement.setString(9, task.priority());
            timestamp(statement, 10, task.deadlineAt());
            timestamp(statement, 11, task.nextRunAt());
            statement.setString(12, task.scheduleRule().name());
            statement.setInt(13, task.currentStep());
            statement.setInt(14, task.planVersion());
            statement.setInt(15, task.retryCount());
            statement.setInt(16, task.maxRetries());
            statement.setString(17, task.lastError());
            statement.setString(18, task.lockOwner());
            timestamp(statement, 19, task.lockedUntil());
            timestamp(statement, 20, task.createdAt());
            timestamp(statement, 21, task.updatedAt());
            statement.executeUpdate();
        } catch (SQLException error) {
            failure("保存 ExecutiveTask", error);
        }
    }

    public ExecutiveTask findTask(String taskId) {
        if (!available()) return null;
        String sql = "SELECT * FROM executive_tasks WHERE bot_id=? AND id=?";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, Config.DATABASE_BOT_ID);
            statement.setString(2, taskId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? task(result) : null;
            }
        } catch (SQLException error) {
            failure("读取 ExecutiveTask", error);
            return null;
        }
    }

    public ExecutiveTask findByDedupKey(String userId, String dedupKey) {
        if (!available()) return null;
        String sql = "SELECT * FROM executive_tasks WHERE bot_id=? AND user_id=? AND dedup_key=? LIMIT 1";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, Config.DATABASE_BOT_ID);
            statement.setString(2, userId);
            statement.setString(3, dedupKey);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? task(result) : null;
            }
        } catch (SQLException error) {
            failure("按去重键读取任务", error);
            return null;
        }
    }

    public List<ExecutiveTask> listTasks(String userId, int limit) {
        if (!available()) return List.of();
        boolean allUsers = userId == null || userId.isBlank();
        String sql = "SELECT * FROM executive_tasks WHERE bot_id=? "
                + (allUsers ? "" : "AND user_id=? ") + "ORDER BY updated_at DESC LIMIT ?";
        List<ExecutiveTask> tasks = new ArrayList<>();
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, Config.DATABASE_BOT_ID);
            int index = 2;
            if (!allUsers) statement.setString(index++, userId);
            statement.setInt(index, Math.max(1, limit));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) tasks.add(task(result));
            }
        } catch (SQLException error) {
            failure("读取任务列表", error);
        }
        return tasks;
    }

    public List<ExecutiveTask> dueTasks(LocalDateTime now, int limit) {
        if (!available()) return List.of();
        String sql = "SELECT * FROM executive_tasks WHERE bot_id=? "
                + "AND status IN ('READY','RETRYING') AND next_run_at<=? "
                + "AND (locked_until IS NULL OR locked_until<=?) ORDER BY next_run_at LIMIT ?";
        List<ExecutiveTask> tasks = new ArrayList<>();
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, Config.DATABASE_BOT_ID);
            statement.setTimestamp(2, Timestamp.valueOf(now));
            statement.setTimestamp(3, Timestamp.valueOf(now));
            statement.setInt(4, Math.max(1, limit));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) tasks.add(task(result));
            }
        } catch (SQLException error) {
            failure("读取到期任务", error);
        }
        return tasks;
    }

    public boolean tryClaim(String taskId, String owner, LocalDateTime now, LocalDateTime until) {
        if (!available()) return true;
        String sql = "UPDATE executive_tasks SET lock_owner=?, locked_until=?, updated_at=? "
                + "WHERE bot_id=? AND id=? AND (locked_until IS NULL OR locked_until<=?)";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, owner);
            statement.setTimestamp(2, Timestamp.valueOf(until));
            statement.setTimestamp(3, Timestamp.valueOf(now));
            statement.setString(4, Config.DATABASE_BOT_ID);
            statement.setString(5, taskId);
            statement.setTimestamp(6, Timestamp.valueOf(now));
            return statement.executeUpdate() == 1;
        } catch (SQLException error) {
            failure("领取任务", error);
            return false;
        }
    }

    public void saveStep(ExecutiveStep step) {
        if (!available()) return;
        String sql = "INSERT INTO executive_steps (id, task_id, bot_id, sequence_no, title, capability, tool_name, "
                + "input_json, output_text, status, depends_on, requires_approval, risk_level, attempts, "
                + "max_attempts, next_run_at, verification_rule, last_error, started_at, finished_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE title=VALUES(title), input_json=VALUES(input_json), "
                + "output_text=VALUES(output_text), status=VALUES(status), attempts=VALUES(attempts), "
                + "next_run_at=VALUES(next_run_at), last_error=VALUES(last_error), "
                + "started_at=VALUES(started_at), finished_at=VALUES(finished_at)";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, step.id());
            statement.setString(2, step.taskId());
            statement.setString(3, Config.DATABASE_BOT_ID);
            statement.setInt(4, step.sequence());
            statement.setString(5, step.title());
            statement.setString(6, step.capability());
            statement.setString(7, step.toolName());
            statement.setString(8, step.inputJson());
            statement.setString(9, step.outputText());
            statement.setString(10, step.status().name());
            statement.setString(11, gson.toJson(step.dependsOn()));
            statement.setBoolean(12, step.requiresApproval());
            statement.setString(13, step.riskLevel().name());
            statement.setInt(14, step.attempts());
            statement.setInt(15, step.maxAttempts());
            timestamp(statement, 16, step.nextRunAt());
            statement.setString(17, step.verificationRule());
            statement.setString(18, step.lastError());
            timestamp(statement, 19, step.startedAt());
            timestamp(statement, 20, step.finishedAt());
            statement.executeUpdate();
        } catch (SQLException error) {
            failure("保存 ExecutiveStep", error);
        }
    }

    public List<ExecutiveStep> loadSteps(String taskId) {
        if (!available()) return List.of();
        String sql = "SELECT * FROM executive_steps WHERE bot_id=? AND task_id=? ORDER BY sequence_no";
        List<ExecutiveStep> steps = new ArrayList<>();
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, Config.DATABASE_BOT_ID);
            statement.setString(2, taskId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) steps.add(step(result));
            }
        } catch (SQLException error) {
            failure("读取任务步骤", error);
        }
        return steps;
    }

    public void saveLog(ExecutionLog log) {
        if (!available()) return;
        String sql = "INSERT INTO execution_logs (id, bot_id, task_id, step_id, user_id, event_type, "
                + "status, message, payload_json, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, log.id());
            statement.setString(2, Config.DATABASE_BOT_ID);
            statement.setString(3, log.taskId());
            statement.setString(4, log.stepId());
            statement.setString(5, log.userId());
            statement.setString(6, log.eventType());
            statement.setString(7, log.status());
            statement.setString(8, log.message());
            statement.setString(9, log.payloadJson());
            statement.setTimestamp(10, Timestamp.valueOf(log.createdAt()));
            statement.executeUpdate();
        } catch (SQLException error) {
            failure("保存执行日志", error);
        }
    }

    public List<ExecutionLog> loadLogs(String taskId, int limit) {
        if (!available()) return List.of();
        String sql = "SELECT * FROM execution_logs WHERE bot_id=? AND task_id=? ORDER BY created_at, id LIMIT ?";
        List<ExecutionLog> logs = new ArrayList<>();
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, Config.DATABASE_BOT_ID);
            statement.setString(2, taskId);
            statement.setInt(3, Math.max(1, limit));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) logs.add(new ExecutionLog(result.getString("id"),
                        result.getString("task_id"), result.getString("step_id"),
                        result.getString("user_id"), result.getString("event_type"),
                        result.getString("status"), result.getString("message"),
                        result.getString("payload_json"), time(result, "created_at")));
            }
        } catch (SQLException error) {
            failure("读取执行日志", error);
        }
        return logs;
    }

    public void saveApproval(ApprovalRequest approval) {
        if (!available()) return;
        String sql = "INSERT INTO approval_requests (id, bot_id, task_id, step_id, user_id, risk_level, "
                + "action_summary, status, expires_at, acted_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE status=VALUES(status), acted_at=VALUES(acted_at)";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, approval.id());
            statement.setString(2, Config.DATABASE_BOT_ID);
            statement.setString(3, approval.taskId());
            statement.setString(4, approval.stepId());
            statement.setString(5, approval.userId());
            statement.setString(6, approval.riskLevel().name());
            statement.setString(7, approval.actionSummary());
            statement.setString(8, approval.status());
            timestamp(statement, 9, approval.expiresAt());
            timestamp(statement, 10, approval.actedAt());
            timestamp(statement, 11, approval.createdAt());
            statement.executeUpdate();
        } catch (SQLException error) {
            failure("保存审批请求", error);
        }
    }

    public ApprovalRequest findApprovalByStep(String stepId) {
        if (!available()) return null;
        String sql = "SELECT * FROM approval_requests WHERE bot_id=? AND step_id=? ORDER BY created_at DESC LIMIT 1";
        return findApproval(sql, stepId);
    }

    public ApprovalRequest findApproval(String approvalId) {
        if (!available()) return null;
        String sql = "SELECT * FROM approval_requests WHERE bot_id=? AND id=? LIMIT 1";
        return findApproval(sql, approvalId);
    }

    private ApprovalRequest findApproval(String sql, String value) {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, Config.DATABASE_BOT_ID);
            statement.setString(2, value);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? approval(result) : null;
            }
        } catch (SQLException error) {
            failure("读取审批请求", error);
            return null;
        }
    }

    public void saveOutbox(OutboxMessage message) {
        if (!available()) return;
        String sql = "INSERT INTO notification_outbox (id, bot_id, task_id, user_id, type, content, status, "
                + "attempts, available_at, sent_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE status=VALUES(status), attempts=VALUES(attempts), "
                + "available_at=VALUES(available_at), sent_at=VALUES(sent_at)";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, message.id());
            statement.setString(2, Config.DATABASE_BOT_ID);
            statement.setString(3, message.taskId());
            statement.setString(4, message.userId());
            statement.setString(5, message.type());
            statement.setString(6, message.content());
            statement.setString(7, message.status());
            statement.setInt(8, message.attempts());
            timestamp(statement, 9, message.availableAt());
            timestamp(statement, 10, message.sentAt());
            timestamp(statement, 11, message.createdAt());
            statement.executeUpdate();
        } catch (SQLException error) {
            failure("保存通知 Outbox", error);
        }
    }

    public List<OutboxMessage> pendingOutbox(String userId, LocalDateTime now, int limit) {
        if (!available()) return List.of();
        String sql = "SELECT * FROM notification_outbox WHERE bot_id=? AND user_id=? "
                + "AND status='PENDING' AND available_at<=? ORDER BY created_at LIMIT ?";
        List<OutboxMessage> messages = new ArrayList<>();
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, Config.DATABASE_BOT_ID);
            statement.setString(2, userId);
            statement.setTimestamp(3, Timestamp.valueOf(now));
            statement.setInt(4, Math.max(1, limit));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) messages.add(outbox(result));
            }
        } catch (SQLException error) {
            failure("读取通知 Outbox", error);
        }
        return messages;
    }

    private ExecutiveTask task(ResultSet result) throws SQLException {
        return new ExecutiveTask(result.getString("id"), result.getString("user_id"),
                result.getString("goal"), result.getString("source_type"), result.getString("source_id"),
                result.getString("dedup_key"), TaskStatus.valueOf(result.getString("status")),
                result.getString("priority"), time(result, "deadline_at"), time(result, "next_run_at"),
                ScheduleRule.valueOf(result.getString("schedule_rule")), result.getInt("current_step"),
                result.getInt("plan_version"), result.getInt("retry_count"), result.getInt("max_retries"),
                result.getString("last_error"), result.getString("lock_owner"), time(result, "locked_until"),
                time(result, "created_at"), time(result, "updated_at"));
    }

    private ExecutiveStep step(ResultSet result) throws SQLException {
        List<String> dependencies = gson.fromJson(result.getString("depends_on"),
                new TypeToken<List<String>>() { }.getType());
        return new ExecutiveStep(result.getString("id"), result.getString("task_id"),
                result.getInt("sequence_no"), result.getString("title"), result.getString("capability"),
                result.getString("tool_name"), result.getString("input_json"), result.getString("output_text"),
                StepStatus.valueOf(result.getString("status")), dependencies,
                result.getBoolean("requires_approval"), RiskLevel.valueOf(result.getString("risk_level")),
                result.getInt("attempts"), result.getInt("max_attempts"), time(result, "next_run_at"),
                result.getString("verification_rule"), result.getString("last_error"),
                time(result, "started_at"), time(result, "finished_at"));
    }

    private ApprovalRequest approval(ResultSet result) throws SQLException {
        return new ApprovalRequest(result.getString("id"), result.getString("task_id"),
                result.getString("step_id"), result.getString("user_id"),
                RiskLevel.valueOf(result.getString("risk_level")), result.getString("action_summary"),
                result.getString("status"), time(result, "expires_at"), time(result, "acted_at"),
                time(result, "created_at"));
    }

    private OutboxMessage outbox(ResultSet result) throws SQLException {
        return new OutboxMessage(result.getString("id"), result.getString("task_id"),
                result.getString("user_id"), result.getString("type"), result.getString("content"),
                result.getString("status"), result.getInt("attempts"), time(result, "available_at"),
                time(result, "sent_at"), time(result, "created_at"));
    }

    private void initializeTables() {
        try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS executive_tasks ("
                    + "id VARCHAR(64) PRIMARY KEY, bot_id VARCHAR(128) NOT NULL, user_id VARCHAR(255) NOT NULL,"
                    + "goal TEXT NOT NULL, source_type VARCHAR(32) NOT NULL, source_id VARCHAR(128) NOT NULL DEFAULT '',"
                    + "dedup_key VARCHAR(128) NOT NULL, status VARCHAR(32) NOT NULL, priority VARCHAR(16) NOT NULL,"
                    + "deadline_at DATETIME NULL, next_run_at DATETIME NULL, schedule_rule VARCHAR(16) NOT NULL,"
                    + "current_step INT NOT NULL DEFAULT 0, plan_version INT NOT NULL DEFAULT 1,"
                    + "retry_count INT NOT NULL DEFAULT 0, max_retries INT NOT NULL DEFAULT 3,"
                    + "last_error TEXT NULL, lock_owner VARCHAR(128) NOT NULL DEFAULT '', locked_until DATETIME NULL,"
                    + "created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL,"
                    + "UNIQUE KEY uk_executive_dedup (bot_id, user_id, dedup_key),"
                    + "INDEX idx_executive_due (bot_id, status, next_run_at, locked_until),"
                    + "INDEX idx_executive_user (bot_id, user_id, updated_at)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS executive_steps ("
                    + "id VARCHAR(64) PRIMARY KEY, task_id VARCHAR(64) NOT NULL, bot_id VARCHAR(128) NOT NULL,"
                    + "sequence_no INT NOT NULL, title VARCHAR(500) NOT NULL, capability VARCHAR(128) NOT NULL,"
                    + "tool_name VARCHAR(128) NOT NULL, input_json LONGTEXT NOT NULL, output_text LONGTEXT NULL,"
                    + "status VARCHAR(32) NOT NULL, depends_on TEXT NOT NULL, requires_approval BOOLEAN NOT NULL,"
                    + "risk_level VARCHAR(32) NOT NULL, attempts INT NOT NULL, max_attempts INT NOT NULL,"
                    + "next_run_at DATETIME NULL, verification_rule VARCHAR(64) NOT NULL, last_error TEXT NULL,"
                    + "started_at DATETIME NULL, finished_at DATETIME NULL,"
                    + "UNIQUE KEY uk_executive_step_seq (bot_id, task_id, sequence_no),"
                    + "INDEX idx_executive_step_task (bot_id, task_id, sequence_no)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS execution_logs ("
                    + "id VARCHAR(64) PRIMARY KEY, bot_id VARCHAR(128) NOT NULL, task_id VARCHAR(64) NOT NULL,"
                    + "step_id VARCHAR(64) NOT NULL DEFAULT '', user_id VARCHAR(255) NOT NULL,"
                    + "event_type VARCHAR(32) NOT NULL, status VARCHAR(32) NOT NULL, message TEXT NULL,"
                    + "payload_json LONGTEXT NOT NULL, created_at DATETIME NOT NULL,"
                    + "INDEX idx_execution_log_task (bot_id, task_id, created_at)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS approval_requests ("
                    + "id VARCHAR(64) PRIMARY KEY, bot_id VARCHAR(128) NOT NULL, task_id VARCHAR(64) NOT NULL,"
                    + "step_id VARCHAR(64) NOT NULL, user_id VARCHAR(255) NOT NULL, risk_level VARCHAR(32) NOT NULL,"
                    + "action_summary TEXT NOT NULL, status VARCHAR(16) NOT NULL, expires_at DATETIME NULL,"
                    + "acted_at DATETIME NULL, created_at DATETIME NOT NULL,"
                    + "INDEX idx_approval_step (bot_id, step_id, created_at),"
                    + "INDEX idx_approval_user (bot_id, user_id, status, created_at)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS notification_outbox ("
                    + "id VARCHAR(64) PRIMARY KEY, bot_id VARCHAR(128) NOT NULL, task_id VARCHAR(64) NOT NULL DEFAULT '',"
                    + "user_id VARCHAR(255) NOT NULL, type VARCHAR(32) NOT NULL, content TEXT NOT NULL,"
                    + "status VARCHAR(16) NOT NULL, attempts INT NOT NULL DEFAULT 0, available_at DATETIME NOT NULL,"
                    + "sent_at DATETIME NULL, created_at DATETIME NOT NULL,"
                    + "INDEX idx_outbox_due (bot_id, user_id, status, available_at)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        } catch (SQLException error) {
            failure("初始化 Executive Core 数据表", error);
        }
    }

    private static void timestamp(PreparedStatement statement, int index, LocalDateTime value) throws SQLException {
        if (value == null) statement.setNull(index, java.sql.Types.TIMESTAMP);
        else statement.setTimestamp(index, Timestamp.valueOf(value));
    }

    private static LocalDateTime time(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private static void failure(String action, Exception error) {
        System.err.println("[ExecutiveDatabase] " + action + "失败，继续使用内存状态: " + error.getMessage());
    }
}
