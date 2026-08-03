CREATE TABLE IF NOT EXISTS schema_migrations (
  version VARCHAR(64) NOT NULL PRIMARY KEY,
  applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS users (
  id BINARY(16) NOT NULL PRIMARY KEY,
  external_id VARCHAR(128) NOT NULL,
  display_name VARCHAR(100) NOT NULL DEFAULT '',
  timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_users_external_id (external_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS workspaces (
  id BINARY(16) NOT NULL PRIMARY KEY,
  owner_id BINARY(16) NOT NULL,
  name VARCHAR(160) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_workspaces_owner FOREIGN KEY (owner_id) REFERENCES users (id),
  INDEX idx_workspaces_owner (owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS workspace_members (
  workspace_id BINARY(16) NOT NULL,
  user_id BINARY(16) NOT NULL,
  role VARCHAR(32) NOT NULL DEFAULT 'member',
  joined_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (workspace_id, user_id),
  CONSTRAINT fk_workspace_members_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
  CONSTRAINT fk_workspace_members_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS plans (
  id BINARY(16) NOT NULL PRIMARY KEY,
  workspace_id BINARY(16) NOT NULL,
  owner_id BINARY(16) NOT NULL,
  title VARCHAR(200) NOT NULL,
  description TEXT NULL,
  color CHAR(7) NOT NULL DEFAULT '#D39A24',
  status VARCHAR(20) NOT NULL DEFAULT 'active',
  progress DECIMAL(5,2) NOT NULL DEFAULT 0,
  due_date DATE NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_plans_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
  CONSTRAINT fk_plans_owner FOREIGN KEY (owner_id) REFERENCES users (id),
  INDEX idx_plans_workspace_status (workspace_id, status, updated_at),
  INDEX idx_plans_owner (owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS plan_stages (
  id BINARY(16) NOT NULL PRIMARY KEY,
  plan_id BINARY(16) NOT NULL,
  title VARCHAR(200) NOT NULL,
  description TEXT NULL,
  progress DECIMAL(5,2) NOT NULL DEFAULT 0,
  status VARCHAR(20) NOT NULL DEFAULT 'pending',
  due_date DATE NULL,
  sort_order INT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_plan_stages_plan FOREIGN KEY (plan_id) REFERENCES plans (id) ON DELETE CASCADE,
  INDEX idx_plan_stages_plan_order (plan_id, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS schedule_items (
  id BINARY(16) NOT NULL PRIMARY KEY,
  workspace_id BINARY(16) NOT NULL,
  plan_id BINARY(16) NULL,
  stage_id BINARY(16) NULL,
  created_by BINARY(16) NOT NULL,
  title VARCHAR(240) NOT NULL,
  description TEXT NULL,
  start_at DATETIME NOT NULL,
  duration_minutes SMALLINT UNSIGNED NOT NULL DEFAULT 30,
  status VARCHAR(20) NOT NULL DEFAULT 'pending',
  progress DECIMAL(5,2) NOT NULL DEFAULT 0,
  source_type VARCHAR(20) NOT NULL DEFAULT 'manual',
  completed_at DATETIME NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_schedule_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
  CONSTRAINT fk_schedule_plan FOREIGN KEY (plan_id) REFERENCES plans (id) ON DELETE SET NULL,
  CONSTRAINT fk_schedule_stage FOREIGN KEY (stage_id) REFERENCES plan_stages (id) ON DELETE SET NULL,
  CONSTRAINT fk_schedule_creator FOREIGN KEY (created_by) REFERENCES users (id),
  INDEX idx_schedule_workspace_time (workspace_id, start_at),
  INDEX idx_schedule_plan_time (plan_id, start_at),
  INDEX idx_schedule_status (workspace_id, status, start_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS todos (
  id BINARY(16) NOT NULL PRIMARY KEY,
  workspace_id BINARY(16) NOT NULL,
  created_by BINARY(16) NOT NULL,
  title VARCHAR(240) NOT NULL,
  description TEXT NULL,
  due_at DATETIME NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'pending',
  priority VARCHAR(10) NOT NULL DEFAULT 'medium',
  reminder_minutes INT NULL,
  completed_at DATETIME NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_todos_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
  CONSTRAINT fk_todos_creator FOREIGN KEY (created_by) REFERENCES users (id),
  INDEX idx_todos_workspace_due (workspace_id, status, due_at),
  INDEX idx_todos_creator (created_by, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS note_categories (
  id BINARY(16) NOT NULL PRIMARY KEY,
  workspace_id BINARY(16) NOT NULL,
  name VARCHAR(100) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_note_categories_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
  UNIQUE KEY uk_note_categories_name (workspace_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS notes (
  id BINARY(16) NOT NULL PRIMARY KEY,
  workspace_id BINARY(16) NOT NULL,
  created_by BINARY(16) NOT NULL,
  category_id BINARY(16) NULL,
  title VARCHAR(240) NOT NULL,
  excerpt VARCHAR(1000) NOT NULL DEFAULT '',
  content LONGTEXT NOT NULL,
  source_type VARCHAR(20) NOT NULL DEFAULT 'manual',
  status VARCHAR(20) NOT NULL DEFAULT 'active',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_notes_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
  CONSTRAINT fk_notes_creator FOREIGN KEY (created_by) REFERENCES users (id),
  CONSTRAINT fk_notes_category FOREIGN KEY (category_id) REFERENCES note_categories (id) ON DELETE SET NULL,
  INDEX idx_notes_workspace_updated (workspace_id, status, updated_at),
  INDEX idx_notes_category (category_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS note_tags (
  id BINARY(16) NOT NULL PRIMARY KEY,
  workspace_id BINARY(16) NOT NULL,
  name VARCHAR(80) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_note_tags_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
  UNIQUE KEY uk_note_tags_name (workspace_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS note_tag_links (
  note_id BINARY(16) NOT NULL,
  tag_id BINARY(16) NOT NULL,
  PRIMARY KEY (note_id, tag_id),
  CONSTRAINT fk_note_tag_links_note FOREIGN KEY (note_id) REFERENCES notes (id) ON DELETE CASCADE,
  CONSTRAINT fk_note_tag_links_tag FOREIGN KEY (tag_id) REFERENCES note_tags (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS note_relations (
  from_note_id BINARY(16) NOT NULL,
  to_note_id BINARY(16) NOT NULL,
  relation_type VARCHAR(32) NOT NULL DEFAULT 'related',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (from_note_id, to_note_id),
  CONSTRAINT fk_note_relations_from FOREIGN KEY (from_note_id) REFERENCES notes (id) ON DELETE CASCADE,
  CONSTRAINT fk_note_relations_to FOREIGN KEY (to_note_id) REFERENCES notes (id) ON DELETE CASCADE,
  CONSTRAINT chk_note_relations_not_self CHECK (from_note_id <> to_note_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS source_materials (
  id BINARY(16) NOT NULL PRIMARY KEY,
  workspace_id BINARY(16) NOT NULL,
  collected_by BINARY(16) NULL,
  source_type VARCHAR(20) NOT NULL,
  title VARCHAR(500) NOT NULL,
  url VARCHAR(1024) NOT NULL,
  summary TEXT NULL,
  content LONGTEXT NULL,
  metadata JSON NULL,
  collected_at DATETIME NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_source_materials_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
  CONSTRAINT fk_source_materials_collector FOREIGN KEY (collected_by) REFERENCES users (id) ON DELETE SET NULL,
  INDEX idx_source_materials_workspace (workspace_id, source_type, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS schedule_material_links (
  schedule_id BINARY(16) NOT NULL,
  material_id BINARY(16) NOT NULL,
  PRIMARY KEY (schedule_id, material_id),
  CONSTRAINT fk_schedule_materials_schedule FOREIGN KEY (schedule_id) REFERENCES schedule_items (id) ON DELETE CASCADE,
  CONSTRAINT fk_schedule_materials_material FOREIGN KEY (material_id) REFERENCES source_materials (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS note_material_links (
  note_id BINARY(16) NOT NULL,
  material_id BINARY(16) NOT NULL,
  PRIMARY KEY (note_id, material_id),
  CONSTRAINT fk_note_materials_note FOREIGN KEY (note_id) REFERENCES notes (id) ON DELETE CASCADE,
  CONSTRAINT fk_note_materials_material FOREIGN KEY (material_id) REFERENCES source_materials (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS reminders (
  id BINARY(16) NOT NULL PRIMARY KEY,
  workspace_id BINARY(16) NOT NULL,
  user_id BINARY(16) NOT NULL,
  schedule_id BINARY(16) NULL,
  todo_id BINARY(16) NULL,
  remind_at DATETIME NOT NULL,
  channel VARCHAR(20) NOT NULL DEFAULT 'web',
  status VARCHAR(20) NOT NULL DEFAULT 'pending',
  sent_at DATETIME NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_reminders_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
  CONSTRAINT fk_reminders_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT fk_reminders_schedule FOREIGN KEY (schedule_id) REFERENCES schedule_items (id) ON DELETE CASCADE,
  CONSTRAINT fk_reminders_todo FOREIGN KEY (todo_id) REFERENCES todos (id) ON DELETE CASCADE,
  CONSTRAINT chk_reminders_target CHECK ((schedule_id IS NOT NULL AND todo_id IS NULL) OR (schedule_id IS NULL AND todo_id IS NOT NULL)),
  INDEX idx_reminders_due (status, remind_at),
  INDEX idx_reminders_user (user_id, status, remind_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS review_entries (
  id BINARY(16) NOT NULL PRIMARY KEY,
  workspace_id BINARY(16) NOT NULL,
  user_id BINARY(16) NOT NULL,
  review_date DATE NOT NULL,
  facts JSON NOT NULL,
  ai_summary LONGTEXT NULL,
  ai_suggestions JSON NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_reviews_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
  CONSTRAINT fk_reviews_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
  UNIQUE KEY uk_reviews_user_date (workspace_id, user_id, review_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS briefings (
  id BINARY(16) NOT NULL PRIMARY KEY,
  workspace_id BINARY(16) NOT NULL,
  user_id BINARY(16) NOT NULL,
  briefing_date DATE NOT NULL,
  title VARCHAR(240) NOT NULL,
  content LONGTEXT NOT NULL,
  plan_snapshot JSON NULL,
  news_snapshot JSON NULL,
  channel VARCHAR(20) NOT NULL DEFAULT 'web',
  status VARCHAR(20) NOT NULL DEFAULT 'draft',
  sent_at DATETIME NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_briefings_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
  CONSTRAINT fk_briefings_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
  UNIQUE KEY uk_briefings_user_date_channel (workspace_id, user_id, briefing_date, channel),
  INDEX idx_briefings_date (workspace_id, briefing_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS notification_outbox (
  id BINARY(16) NOT NULL PRIMARY KEY,
  user_id BINARY(16) NOT NULL,
  notification_type VARCHAR(32) NOT NULL,
  payload JSON NOT NULL,
  channel VARCHAR(20) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'pending',
  scheduled_at DATETIME NOT NULL,
  sent_at DATETIME NULL,
  attempts INT NOT NULL DEFAULT 0,
  last_error TEXT NULL,
  dedup_key VARCHAR(255) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_notification_outbox_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
  UNIQUE KEY uk_notification_outbox_dedup (dedup_key),
  INDEX idx_notification_outbox_due (status, scheduled_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO schema_migrations (version) VALUES ('001_core');
