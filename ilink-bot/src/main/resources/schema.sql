-- =====================================================
-- ClawBot 数据库迁移脚本 v2.0
-- 新增：user、chat_session
-- 扩展：chat_messages、conversation_summaries、user_memories
-- =====================================================

-- 1. 用户表（新增）
CREATE TABLE IF NOT EXISTS `user` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
    `wechat_id` VARCHAR(128) NOT NULL UNIQUE,
    `nickname` VARCHAR(100),
    `first_login_time` DATETIME,
    `last_login_time` DATETIME,
    `created_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_wechat_id` (`wechat_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 2. 聊天会话表（新增）
CREATE TABLE IF NOT EXISTS `chat_session` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
    `session_id` VARCHAR(64) UNIQUE NOT NULL,
    `wechat_id` VARCHAR(128) NOT NULL,
    `title` VARCHAR(100),
    `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    `last_active_time` DATETIME,
    `created_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_session_wechat` (`wechat_id`, `status`),
    INDEX `idx_session_id` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3. chat_messages 扩展（ALTER TABLE 由 MySqlStore 通过 ensureColumn 自动完成）
-- 新增列: session_id VARCHAR(64), message_type VARCHAR(30) DEFAULT 'TEXT'

-- 4. conversation_summaries 扩展（ALTER TABLE 由 MySqlStore 通过 ensureColumn 自动完成）
-- 新增列: session_id VARCHAR(64), message_count INT DEFAULT 0

-- 5. user_memories 扩展（ALTER TABLE 由 MySqlStore 通过 ensureColumn 自动完成）
-- 新增列: importance INT DEFAULT 0
