package com.example.ilink.platform.persistence;

import com.example.ilink.application.conversation.User;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;

public final class UserRepository {

    private final MySqlStore database;

    public UserRepository(MySqlStore database) {
        this.database = database;
    }

    public User findByWechatId(String wechatId) {
        if (!database.isAvailable()) return null;
        String sql = "SELECT id, wechat_id, nickname, first_login_time, last_login_time, created_time, updated_time "
                + "FROM user WHERE wechat_id = ?";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, wechatId);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return new User(result.getLong("id"), result.getString("wechat_id"),
                            result.getString("nickname"),
                            toLocalDateTime(result.getTimestamp("first_login_time")),
                            toLocalDateTime(result.getTimestamp("last_login_time")),
                            toLocalDateTime(result.getTimestamp("created_time")),
                            toLocalDateTime(result.getTimestamp("updated_time")));
                }
            }
        } catch (SQLException e) {
            System.err.println("[UserRepository] 查询用户失败: " + e.getMessage());
        }
        return null;
    }

    public User save(String wechatId, String nickname) {
        if (!database.isAvailable()) return null;
        String sql = "INSERT INTO user (wechat_id, nickname, first_login_time, last_login_time) VALUES (?, ?, NOW(), NOW())";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, wechatId);
            statement.setString(2, nickname == null ? "" : nickname);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return new User(keys.getLong(1), wechatId, nickname,
                            LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now());
                }
            }
        } catch (SQLException e) {
            System.err.println("[UserRepository] 创建用户失败: " + e.getMessage());
        }
        return null;
    }

    public void updateLastLoginTime(String wechatId) {
        if (!database.isAvailable()) return;
        String sql = "UPDATE user SET last_login_time = NOW() WHERE wechat_id = ?";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, wechatId);
            statement.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[UserRepository] 更新登录时间失败: " + e.getMessage());
        }
    }

    private static LocalDateTime toLocalDateTime(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
