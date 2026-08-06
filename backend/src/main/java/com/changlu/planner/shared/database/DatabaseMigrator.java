package com.changlu.planner.shared.database;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * 在应用启动时执行内置 SQL 迁移。
 *
 * 每个脚本只会成功执行一次，执行记录保存在 schema_migrations，
 * 因此组员无需手动创建数据库或表。
 */
final class DatabaseMigrator {
  private static final List<String> MIGRATIONS = List.of(
      "001_core.sql",
      "002_ai_review.sql",
      "003_wechat_login.sql",
      "004_ai_command_drafts.sql",
      "005_plan_execution_loop.sql",
      "006_user_profile.sql",
      "007_profile_avatar_data.sql",
      "008_agent_runtime.sql",
      "009_agent_documents.sql",
      "010_ai_conversations_memory.sql",
      "011_task_recurrence.sql",
      "012_learning_planner.sql",
      "013_agent_loop.sql",
        "014_ai_images.sql",
        "015_ai_message_images.sql",
        "016_ai_image_assets.sql",
        "017_learning_metrics.sql",
        "018_travel_schedule_context.sql",
        "019_travel_refresh.sql"
  );

  private DatabaseMigrator() {}

  static void migrate(String jdbcUrl, String user, String password) throws SQLException {
    String databaseName = databaseName(jdbcUrl);
    createDatabase(jdbcUrl, databaseName, user, password);

    try (Connection connection = DriverManager.getConnection(jdbcUrl, user, password)) {
      ensureMigrationTable(connection);
      for (String scriptName : MIGRATIONS) {
        String version = scriptName.substring(0, scriptName.length() - ".sql".length());
        if (isApplied(connection, version)) continue;
        executeScript(connection, scriptName);
        markApplied(connection, version);
      }
    }
  }

  private static void createDatabase(String jdbcUrl, String databaseName, String user, String password) throws SQLException {
    try (Connection connection = DriverManager.getConnection(serverUrl(jdbcUrl), user, password);
         Statement statement = connection.createStatement()) {
      statement.executeUpdate("CREATE DATABASE IF NOT EXISTS `" + databaseName
          + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    }
  }

  private static void ensureMigrationTable(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute("""
          CREATE TABLE IF NOT EXISTS schema_migrations (
            version VARCHAR(64) NOT NULL PRIMARY KEY,
            applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
          ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
          """);
    }
  }

  private static boolean isApplied(Connection connection, String version) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT 1 FROM schema_migrations WHERE version = ?")) {
      statement.setString(1, version);
      try (ResultSet result = statement.executeQuery()) {
        return result.next();
      }
    }
  }

  private static void markApplied(Connection connection, String version) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "INSERT IGNORE INTO schema_migrations (version) VALUES (?)")) {
      statement.setString(1, version);
      statement.executeUpdate();
    }
  }

  private static void executeScript(Connection connection, String scriptName) throws SQLException {
    String script = loadScript(scriptName);
    // 当前迁移不含存储过程或 DELIMITER，按分号逐条执行即可。
    for (String sql : script.split(";")) {
      String statementSql = sql.trim();
      if (statementSql.isBlank()) continue;
      try (Statement statement = connection.createStatement()) {
        statement.execute(statementSql);
      }
    }
  }

  private static String loadScript(String scriptName) {
    String resource = "/db/migrations/" + scriptName;
    try (InputStream input = DatabaseMigrator.class.getResourceAsStream(resource)) {
      if (input == null) throw new IllegalStateException("缺少数据库迁移脚本: " + resource);
      return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\uFEFF", "");
    } catch (IOException exception) {
      throw new IllegalStateException("无法读取数据库迁移脚本: " + resource, exception);
    }
  }

  private static String databaseName(String jdbcUrl) {
    int hostStart = jdbcUrl.indexOf("//");
    int slash = hostStart < 0 ? -1 : jdbcUrl.indexOf('/', hostStart + 2);
    int query = jdbcUrl.indexOf('?', slash + 1);
    if (slash < 0 || slash == jdbcUrl.length() - 1) {
      throw new IllegalArgumentException("数据库地址必须包含数据库名: " + jdbcUrl);
    }
    String name = jdbcUrl.substring(slash + 1, query < 0 ? jdbcUrl.length() : query);
    if (!name.matches("[A-Za-z0-9_]+")) {
      throw new IllegalArgumentException("数据库名只能包含字母、数字和下划线: " + name);
    }
    return name;
  }

  private static String serverUrl(String jdbcUrl) {
    int hostStart = jdbcUrl.indexOf("//");
    int slash = hostStart < 0 ? -1 : jdbcUrl.indexOf('/', hostStart + 2);
    int query = jdbcUrl.indexOf('?', slash + 1);
    if (slash < 0) throw new IllegalArgumentException("无效的数据库地址: " + jdbcUrl);
    return jdbcUrl.substring(0, slash + 1) + (query < 0 ? "" : jdbcUrl.substring(query));
  }
}
