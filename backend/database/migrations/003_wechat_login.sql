USE changlu_planner;

CREATE TABLE IF NOT EXISTS wechat_login_sessions (
  wechat_user_id VARCHAR(128) NOT NULL PRIMARY KEY,
  bot_token TEXT NOT NULL,
  bot_id VARCHAR(128) NOT NULL,
  base_url VARCHAR(512) NOT NULL,
  updates_cursor TEXT NULL,
  conversations_json LONGTEXT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO schema_migrations (version) VALUES ('003_wechat_login');
