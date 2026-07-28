package com.example.ilink.platform.persistence;

import com.example.ilink.bootstrap.Config;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;

/** 复用 MySQL 连接，避免每次读写都重新建立 TCP 连接。 */
public final class DatabaseConnectionPool implements AutoCloseable {

    private final HikariDataSource dataSource;

    public DatabaseConnectionPool() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(Config.DATABASE_URL);
        config.setUsername(Config.DATABASE_USERNAME);
        config.setPassword(Config.DATABASE_PASSWORD);
        config.setPoolName("ilink-database");
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(5_000);
        config.setInitializationFailTimeout(5_000);
        dataSource = new HikariDataSource(config);
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
