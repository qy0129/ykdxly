-- 文生图 Subagent 的生成记录表。
-- 幂等键保证同一次请求在重试时不会重复调用模型或重复计费。
CREATE TABLE ai_images (
  id BINARY(16) NOT NULL,
  workspace_id BINARY(16) NOT NULL,
  user_id BINARY(16) NOT NULL,
  request_id VARCHAR(64) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  prompt VARCHAR(2000) NOT NULL,
  size VARCHAR(24) NOT NULL DEFAULT '1024x1024',
  style VARCHAR(32) NOT NULL DEFAULT 'default',
  status VARCHAR(24) NOT NULL DEFAULT 'SUCCESS',
  image_url VARCHAR(2048) NULL,
  provider VARCHAR(64) NULL,
  error_message VARCHAR(1000) NULL,
  trace_id VARCHAR(64) NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at TIMESTAMP NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ai_images_idempotency (workspace_id, user_id, idempotency_key),
  KEY idx_ai_images_owner_time (workspace_id, user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
