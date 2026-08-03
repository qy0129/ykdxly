CREATE TABLE IF NOT EXISTS ai_action_drafts (
  id BINARY(16) NOT NULL PRIMARY KEY,
  workspace_id BINARY(16) NOT NULL,
  user_id BINARY(16) NOT NULL,
  conversation_id BINARY(16) NULL,
  source_channel VARCHAR(20) NOT NULL,
  request_text TEXT NOT NULL,
  reply LONGTEXT NOT NULL,
  actions JSON NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'pending',
  expires_at DATETIME NOT NULL,
  confirmed_at DATETIME NULL,
  cancelled_at DATETIME NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_ai_drafts_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
  CONSTRAINT fk_ai_drafts_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT fk_ai_drafts_conversation FOREIGN KEY (conversation_id) REFERENCES ai_conversations (id) ON DELETE SET NULL,
  INDEX idx_ai_drafts_user_status (workspace_id, user_id, status, created_at),
  INDEX idx_ai_drafts_expiry (status, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS execution_records (
  id BINARY(16) NOT NULL PRIMARY KEY,
  workspace_id BINARY(16) NOT NULL,
  user_id BINARY(16) NOT NULL,
  draft_id BINARY(16) NULL,
  entity_type VARCHAR(32) NOT NULL,
  entity_id BINARY(16) NULL,
  action_type VARCHAR(32) NOT NULL,
  note TEXT NULL,
  actual_minutes INT NULL,
  occurred_at DATETIME NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_execution_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
  CONSTRAINT fk_execution_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT fk_execution_draft FOREIGN KEY (draft_id) REFERENCES ai_action_drafts (id) ON DELETE SET NULL,
  INDEX idx_execution_daily (workspace_id, user_id, occurred_at),
  INDEX idx_execution_entity (entity_type, entity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO schema_migrations (version) VALUES ('004_ai_command_drafts');
