CREATE TABLE IF NOT EXISTS travel_refresh_configs (
  plan_id BINARY(16) NOT NULL PRIMARY KEY,
  workspace_id BINARY(16) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  next_refresh_at DATETIME NULL,
  last_refreshed_at DATETIME NULL,
  failure_count INT UNSIGNED NOT NULL DEFAULT 0,
  last_error VARCHAR(500) NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_travel_refresh_plan FOREIGN KEY (plan_id) REFERENCES plans (id) ON DELETE CASCADE,
  CONSTRAINT fk_travel_refresh_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
  INDEX idx_travel_refresh_due (enabled, next_refresh_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS travel_data_snapshots (
  id BINARY(16) NOT NULL PRIMARY KEY,
  workspace_id BINARY(16) NOT NULL,
  plan_id BINARY(16) NOT NULL,
  provider VARCHAR(40) NOT NULL,
  data_type VARCHAR(40) NOT NULL,
  payload_json JSON NOT NULL,
  content_hash CHAR(64) NOT NULL,
  fetched_at DATETIME NOT NULL,
  expires_at DATETIME NOT NULL,
  last_error VARCHAR(500) NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_travel_snapshot_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
  CONSTRAINT fk_travel_snapshot_plan FOREIGN KEY (plan_id) REFERENCES plans (id) ON DELETE CASCADE,
  UNIQUE KEY uk_travel_snapshot_content (plan_id, provider, data_type, content_hash),
  INDEX idx_travel_snapshot_latest (plan_id, provider, data_type, fetched_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS travel_change_drafts (
  id BINARY(16) NOT NULL PRIMARY KEY,
  workspace_id BINARY(16) NOT NULL,
  user_id BINARY(16) NOT NULL,
  plan_id BINARY(16) NOT NULL,
  reason VARCHAR(240) NOT NULL,
  changes_json JSON NOT NULL,
  content_hash CHAR(64) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'pending',
  expires_at DATETIME NOT NULL,
  confirmed_at DATETIME NULL,
  cancelled_at DATETIME NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_travel_change_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
  CONSTRAINT fk_travel_change_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT fk_travel_change_plan FOREIGN KEY (plan_id) REFERENCES plans (id) ON DELETE CASCADE,
  UNIQUE KEY uk_travel_change_content (plan_id, content_hash),
  INDEX idx_travel_change_pending (workspace_id, user_id, status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS travel_notifications (
  id BINARY(16) NOT NULL PRIMARY KEY,
  workspace_id BINARY(16) NOT NULL,
  user_id BINARY(16) NOT NULL,
  plan_id BINARY(16) NOT NULL,
  draft_id BINARY(16) NOT NULL,
  title VARCHAR(240) NOT NULL,
  message VARCHAR(1000) NOT NULL,
  read_at DATETIME NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_travel_notification_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
  CONSTRAINT fk_travel_notification_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT fk_travel_notification_plan FOREIGN KEY (plan_id) REFERENCES plans (id) ON DELETE CASCADE,
  CONSTRAINT fk_travel_notification_draft FOREIGN KEY (draft_id) REFERENCES travel_change_drafts (id) ON DELETE CASCADE,
  UNIQUE KEY uk_travel_notification_draft (draft_id),
  INDEX idx_travel_notification_unread (workspace_id, user_id, read_at, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
