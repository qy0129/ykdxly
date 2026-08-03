ALTER TABLE plans
  ADD COLUMN task_progress DECIMAL(5,2) NOT NULL DEFAULT 0 AFTER progress,
  ADD COLUMN effort_progress DECIMAL(5,2) NOT NULL DEFAULT 0 AFTER task_progress,
  ADD COLUMN version INT NOT NULL DEFAULT 0 AFTER effort_progress,
  ADD COLUMN deleted_at DATETIME NULL AFTER version,
  ADD COLUMN purge_after DATETIME NULL AFTER deleted_at;

ALTER TABLE plan_stages
  ADD COLUMN legacy_progress DECIMAL(5,2) NULL AFTER progress,
  ADD COLUMN task_progress DECIMAL(5,2) NOT NULL DEFAULT 0 AFTER legacy_progress,
  ADD COLUMN effort_progress DECIMAL(5,2) NOT NULL DEFAULT 0 AFTER task_progress,
  ADD COLUMN version INT NOT NULL DEFAULT 0 AFTER effort_progress,
  ADD COLUMN deleted_at DATETIME NULL AFTER version,
  ADD COLUMN purge_after DATETIME NULL AFTER deleted_at;

UPDATE plan_stages SET legacy_progress = progress WHERE legacy_progress IS NULL;

CREATE TABLE IF NOT EXISTS plan_tasks (
  id BINARY(16) NOT NULL PRIMARY KEY,
  plan_id BINARY(16) NOT NULL,
  stage_id BINARY(16) NOT NULL,
  title VARCHAR(240) NOT NULL,
  description TEXT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'pending',
  priority VARCHAR(10) NOT NULL DEFAULT 'medium',
  estimated_minutes INT UNSIGNED NULL,
  actual_minutes INT UNSIGNED NULL,
  due_at DATETIME NULL,
  completed_at DATETIME NULL,
  blocked_reason TEXT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  version INT NOT NULL DEFAULT 0,
  deleted_at DATETIME NULL,
  purge_after DATETIME NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_plan_tasks_plan FOREIGN KEY (plan_id) REFERENCES plans (id) ON DELETE CASCADE,
  CONSTRAINT fk_plan_tasks_stage FOREIGN KEY (stage_id) REFERENCES plan_stages (id) ON DELETE CASCADE,
  INDEX idx_plan_tasks_stage_order (stage_id, deleted_at, sort_order),
  INDEX idx_plan_tasks_plan_status (plan_id, deleted_at, status, due_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE todos
  ADD COLUMN version INT NOT NULL DEFAULT 0 AFTER completed_at,
  ADD COLUMN deleted_at DATETIME NULL AFTER version,
  ADD COLUMN purge_after DATETIME NULL AFTER deleted_at;

ALTER TABLE schedule_items
  ADD COLUMN task_id BINARY(16) NULL AFTER stage_id,
  ADD COLUMN version INT NOT NULL DEFAULT 0 AFTER completed_at,
  ADD COLUMN deleted_at DATETIME NULL AFTER version,
  ADD COLUMN purge_after DATETIME NULL AFTER deleted_at,
  ADD CONSTRAINT fk_schedule_task FOREIGN KEY (task_id) REFERENCES plan_tasks (id) ON DELETE SET NULL;

CREATE TABLE IF NOT EXISTS planning_preferences (
  workspace_id BINARY(16) NOT NULL,
  user_id BINARY(16) NOT NULL,
  timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai',
  availability JSON NULL,
  max_session_minutes INT UNSIGNED NOT NULL DEFAULT 120,
  buffer_minutes INT UNSIGNED NOT NULL DEFAULT 15,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (workspace_id, user_id),
  CONSTRAINT fk_preferences_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
  CONSTRAINT fk_preferences_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE ai_conversations
  ADD COLUMN source_channel VARCHAR(20) NOT NULL DEFAULT 'web' AFTER title;

CREATE TABLE IF NOT EXISTS ai_channel_sessions (
  workspace_id BINARY(16) NOT NULL,
  user_id BINARY(16) NOT NULL,
  channel VARCHAR(20) NOT NULL,
  conversation_id BINARY(16) NOT NULL,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (workspace_id, user_id, channel),
  CONSTRAINT fk_ai_session_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
  CONSTRAINT fk_ai_session_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT fk_ai_session_conversation FOREIGN KEY (conversation_id) REFERENCES ai_conversations (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE ai_action_drafts
  ADD COLUMN change_set_id BINARY(16) NULL AFTER conversation_id,
  ADD COLUMN superseded_at DATETIME NULL AFTER cancelled_at,
  ADD COLUMN undone_at DATETIME NULL AFTER superseded_at;

ALTER TABLE execution_records
  ADD COLUMN change_set_id BINARY(16) NULL AFTER draft_id,
  ADD COLUMN before_snapshot JSON NULL AFTER action_type,
  ADD COLUMN after_snapshot JSON NULL AFTER before_snapshot,
  ADD COLUMN reason TEXT NULL AFTER after_snapshot,
  ADD COLUMN source_channel VARCHAR(20) NOT NULL DEFAULT 'web' AFTER reason,
  ADD COLUMN version_after INT NULL AFTER actual_minutes,
  ADD COLUMN undone_at DATETIME NULL AFTER occurred_at,
  ADD INDEX idx_execution_change_set (change_set_id, undone_at);

INSERT IGNORE INTO schema_migrations (version) VALUES ('005_plan_execution_loop');
