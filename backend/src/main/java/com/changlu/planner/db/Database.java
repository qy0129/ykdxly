package com.changlu.planner.db;

import com.changlu.planner.config.EnvironmentConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

public final class Database {
  public record Context(UUID userId, UUID workspaceId) {}
  public static final UUID DEFAULT_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  public static final UUID DEFAULT_WORKSPACE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private final HikariConfig config;
  private HikariDataSource dataSource;

  private Database(String url, String user, String password) {
    config = new HikariConfig();
    config.setJdbcUrl(url);
    config.setUsername(user);
    config.setPassword(password);
    config.setMaximumPoolSize(8);
    config.setMinimumIdle(1);
    config.setPoolName("changlu-planner");
  }

  public static Database fromEnvironment() {
    String url = EnvironmentConfig.value("PLANNER_DB_URL", "database.url", "jdbc:mysql://127.0.0.1:3306/changlu_planner?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai");
    String user = EnvironmentConfig.value("PLANNER_DB_USER", "database.username", "root");
    String password = EnvironmentConfig.value("PLANNER_DB_PASSWORD", "database.password", "");
    return new Database(url, user, password);
  }

  public void start() { dataSource = new HikariDataSource(config); }
  public void stop() { if (dataSource != null) dataSource.close(); }
  public Connection connection() throws SQLException { return dataSource.getConnection(); }

  public void ensureDefaultContext() throws SQLException {
    String userSql = "INSERT IGNORE INTO users (id, external_id, display_name) VALUES (?, 'local-dev', '长路用户')";
    String workspaceSql = "INSERT IGNORE INTO workspaces (id, owner_id, name) VALUES (?, ?, '我的工作区')";
    try (Connection c = connection(); PreparedStatement u = c.prepareStatement(userSql); PreparedStatement w = c.prepareStatement(workspaceSql)) {
      u.setBytes(1, uuidBytes(DEFAULT_USER_ID)); u.executeUpdate();
      w.setBytes(1, uuidBytes(DEFAULT_WORKSPACE_ID)); w.setBytes(2, uuidBytes(DEFAULT_USER_ID)); w.executeUpdate();
      try (PreparedStatement m = c.prepareStatement("INSERT IGNORE INTO workspace_members (workspace_id, user_id, role) VALUES (?, ?, 'owner')")) {
        m.setBytes(1, uuidBytes(DEFAULT_WORKSPACE_ID)); m.setBytes(2, uuidBytes(DEFAULT_USER_ID)); m.executeUpdate();
      }
    }
  }

  public void ensureWechatLoginTable() throws SQLException {
    String sql = """
        CREATE TABLE IF NOT EXISTS wechat_login_sessions (
          wechat_user_id VARCHAR(128) NOT NULL PRIMARY KEY,
          bot_token TEXT NOT NULL,
          bot_id VARCHAR(128) NOT NULL,
          base_url VARCHAR(512) NOT NULL,
          updates_cursor TEXT NULL,
          conversations_json LONGTEXT NOT NULL,
          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
          updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
        """;
    try (Connection connection = connection(); Statement statement = connection.createStatement()) {
      statement.execute(sql);
      statement.executeUpdate("INSERT IGNORE INTO schema_migrations (version) VALUES ('003_wechat_login')");
    }
  }

  public Context contextForExternalUser(String externalId) throws SQLException {
    String value = externalId == null || externalId.isBlank() ? "local-dev" : externalId.trim();
    try (Connection c = connection()) {
      try (PreparedStatement find = c.prepareStatement("SELECT id FROM users WHERE external_id = ?")) {
        find.setString(1, value);
        try (ResultSet rs = find.executeQuery()) {
          if (rs.next()) {
            UUID userId = bytesUuid(rs.getBytes("id"));
            try (PreparedStatement workspace = c.prepareStatement("SELECT id FROM workspaces WHERE owner_id = ? ORDER BY created_at LIMIT 1")) {
              workspace.setBytes(1, uuidBytes(userId));
              try (ResultSet wrs = workspace.executeQuery()) { if (wrs.next()) return new Context(userId, bytesUuid(wrs.getBytes("id"))); }
            }
          }
        }
      }
      try (PreparedStatement bind = c.prepareStatement("UPDATE users SET external_id = ? WHERE id = ? AND external_id = 'local-dev'")) {
        bind.setString(1, value); bind.setBytes(2, uuidBytes(DEFAULT_USER_ID));
        if (bind.executeUpdate() == 1) return new Context(DEFAULT_USER_ID, DEFAULT_WORKSPACE_ID);
      }
      UUID userId = UUID.randomUUID();
      UUID workspaceId = UUID.randomUUID();
      try (PreparedStatement user = c.prepareStatement("INSERT INTO users (id, external_id, display_name) VALUES (?, ?, ?)")) {
        user.setBytes(1, uuidBytes(userId)); user.setString(2, value); user.setString(3, "微信用户"); user.executeUpdate();
      }
      try (PreparedStatement workspace = c.prepareStatement("INSERT INTO workspaces (id, owner_id, name) VALUES (?, ?, ?)")) {
        workspace.setBytes(1, uuidBytes(workspaceId)); workspace.setBytes(2, uuidBytes(userId)); workspace.setString(3, "我的工作区"); workspace.executeUpdate();
      }
      try (PreparedStatement member = c.prepareStatement("INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (?, ?, 'owner')")) {
        member.setBytes(1, uuidBytes(workspaceId)); member.setBytes(2, uuidBytes(userId)); member.executeUpdate();
      }
      return new Context(userId, workspaceId);
    }
  }

  public static byte[] uuidBytes(UUID id) { ByteBuffer b = ByteBuffer.allocate(16); b.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()); return b.array(); }
  public static UUID bytesUuid(byte[] value) { ByteBuffer b = ByteBuffer.wrap(value); return new UUID(b.getLong(), b.getLong()); }
  public static String id(ResultSet rs, String column) throws SQLException { return bytesUuid(rs.getBytes(column)).toString(); }
}
