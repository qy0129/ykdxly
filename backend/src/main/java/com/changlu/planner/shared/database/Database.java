package com.changlu.planner.shared.database;

import com.changlu.planner.shared.config.EnvironmentConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/** 数据库基础设施边界：业务模块只通过连接和用户上下文访问数据库。 */
public final class Database {
  public record Context(UUID userId, UUID workspaceId) {}
  public static final UUID DEFAULT_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  public static final UUID DEFAULT_WORKSPACE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private final String jdbcUrl;
  private final String user;
  private final String password;
  private final HikariConfig config;
  private HikariDataSource dataSource;

  private Database(String url, String user, String password) {
    this.jdbcUrl = url;
    this.user = user;
    this.password = password;
    config = new HikariConfig();
    config.setJdbcUrl(url);
    config.setUsername(user);
    config.setPassword(password);
    config.setMaximumPoolSize(8);
    config.setMinimumIdle(1);
    config.setPoolName("changlu-planner");
    // 学习计划等大型 JSON（数千行/2MB+）在排序时会超过默认 256KB sort_buffer 导致 "Out of sort memory"，
    // 通过初始化 SQL 提升每条连接的排序缓冲区。
    config.setConnectionInitSql("SET SESSION sort_buffer_size = 8388608");
  }

  public static Database fromEnvironment() {
    String url = EnvironmentConfig.value("PLANNER_DB_URL", "database.url", "jdbc:mysql://127.0.0.1:3306/changlu_planner?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai");
    String user = EnvironmentConfig.value("PLANNER_DB_USER", "database.username", "root");
    String password = EnvironmentConfig.value("PLANNER_DB_PASSWORD", "database.password", "");
    return new Database(url, user, password);
  }

  /** 先建库并执行缺失迁移，再创建供业务使用的连接池。 */
  public void start() throws SQLException {
    DatabaseMigrator.migrate(jdbcUrl, user, password);
    dataSource = new HikariDataSource(config);
  }
  public void stop() { if (dataSource != null) dataSource.close(); }
  public Connection connection() throws SQLException { return dataSource.getConnection(); }

  /** 为本地首次启动准备固定用户和工作区，保证网页预览有稳定的归属主体。 */
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

  public Context contextForExternalUser(String externalId) throws SQLException {
    // 微信等外部渠道只提供 externalId，具体业务数据仍归一到内部用户和工作区。
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
