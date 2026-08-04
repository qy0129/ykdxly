CREATE TABLE IF NOT EXISTS agent_runs (
  id BINARY(16) NOT NULL PRIMARY KEY,
  workspace_id BINARY(16) NOT NULL,
  user_id BINARY(16) NOT NULL,
  conversation_id BINARY(16) NOT NULL,
  pending_draft_id BINARY(16) NULL,
  channel VARCHAR(20) NOT NULL,
  goal TEXT NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'RUNNING',
  iteration INT NOT NULL DEFAULT 0,
  summary LONGTEXT NULL,
  result JSON NULL,
  last_error TEXT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  completed_at DATETIME NULL,
  CONSTRAINT fk_agent_runs_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
  CONSTRAINT fk_agent_runs_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT fk_agent_runs_conversation FOREIGN KEY (conversation_id) REFERENCES ai_conversations (id) ON DELETE CASCADE,
  CONSTRAINT fk_agent_runs_draft FOREIGN KEY (pending_draft_id) REFERENCES ai_action_drafts (id) ON DELETE SET NULL,
  INDEX idx_agent_runs_owner (workspace_id, user_id, updated_at),
  INDEX idx_agent_runs_status (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS agent_tool_calls (
  id BINARY(16) NOT NULL PRIMARY KEY,
  run_id BINARY(16) NOT NULL,
  tool_call_id VARCHAR(100) NOT NULL,
  executor_type VARCHAR(20) NOT NULL,
  tool_name VARCHAR(80) NOT NULL,
  arguments JSON NULL,
  result JSON NULL,
  status VARCHAR(32) NOT NULL,
  requires_confirmation TINYINT(1) NOT NULL DEFAULT 0,
  attempt_count INT NOT NULL DEFAULT 0,
  error TEXT NULL,
  started_at DATETIME NULL,
  completed_at DATETIME NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_agent_tool_calls_run FOREIGN KEY (run_id) REFERENCES agent_runs (id) ON DELETE CASCADE,
  UNIQUE KEY uk_agent_tool_call (tool_call_id),
  INDEX idx_agent_tool_calls_run (run_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO schema_migrations (version) VALUES ('008_agent_runtime');
