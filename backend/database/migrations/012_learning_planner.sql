-- 学习规划模块：学习目标、学习会话、知识领域
-- Learning Planner module: learning goals, sessions, knowledge areas

CREATE TABLE IF NOT EXISTS learning_goals (
  id BINARY(16) NOT NULL PRIMARY KEY,
  workspace_id BINARY(16) NOT NULL,
  user_id BINARY(16) NOT NULL,
  plan_id BINARY(16) NULL COMMENT '关联的长期计划，可选',
  title VARCHAR(240) NOT NULL,
  description TEXT NULL,
  domain VARCHAR(100) NOT NULL DEFAULT 'general' COMMENT '知识领域',
  priority VARCHAR(10) NOT NULL DEFAULT 'medium' COMMENT 'high/medium/low',
  target_date DATE NULL COMMENT '目标完成日期',
  weekly_hours DECIMAL(4,1) UNSIGNED NULL COMMENT '每周计划学习小时数',
  status VARCHAR(20) NOT NULL DEFAULT 'active' COMMENT 'active/paused/completed/abandoned',
  progress DECIMAL(5,2) NOT NULL DEFAULT 0 COMMENT '进度百分比 0-100',
  total_sessions INT UNSIGNED NOT NULL DEFAULT 0,
  completed_sessions INT UNSIGNED NOT NULL DEFAULT 0,
  total_minutes INT UNSIGNED NOT NULL DEFAULT 0,
  version INT NOT NULL DEFAULT 0,
  deleted_at DATETIME NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_learning_goals_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
  CONSTRAINT fk_learning_goals_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT fk_learning_goals_plan FOREIGN KEY (plan_id) REFERENCES plans (id) ON DELETE SET NULL,
  INDEX idx_learning_goals_workspace_status (workspace_id, status, priority),
  INDEX idx_learning_goals_domain (workspace_id, domain, status),
  INDEX idx_learning_goals_target (workspace_id, target_date, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS learning_sessions (
  id BINARY(16) NOT NULL PRIMARY KEY,
  workspace_id BINARY(16) NOT NULL,
  user_id BINARY(16) NOT NULL,
  goal_id BINARY(16) NULL COMMENT '关联的学习目标',
  schedule_id BINARY(16) NULL COMMENT '关联的日程',
  title VARCHAR(240) NOT NULL,
  domain VARCHAR(100) NOT NULL DEFAULT 'general',
  planned_minutes INT UNSIGNED NOT NULL DEFAULT 30,
  actual_minutes INT UNSIGNED NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'planned' COMMENT 'planned/in_progress/completed/skipped',
  focus_score TINYINT UNSIGNED NULL COMMENT '专注度自评 1-5',
  notes TEXT NULL COMMENT '学习笔记摘要',
  completed_at DATETIME NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_learning_sessions_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
  CONSTRAINT fk_learning_sessions_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT fk_learning_sessions_goal FOREIGN KEY (goal_id) REFERENCES learning_goals (id) ON DELETE SET NULL,
  CONSTRAINT fk_learning_sessions_schedule FOREIGN KEY (schedule_id) REFERENCES schedule_items (id) ON DELETE SET NULL,
  INDEX idx_learning_sessions_workspace_time (workspace_id, created_at),
  INDEX idx_learning_sessions_goal (goal_id, status, created_at),
  INDEX idx_learning_sessions_domain (workspace_id, domain, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS knowledge_areas (
  id BINARY(16) NOT NULL PRIMARY KEY,
  workspace_id BINARY(16) NOT NULL,
  name VARCHAR(100) NOT NULL,
  parent_id BINARY(16) NULL COMMENT '父知识领域，支持层级',
  description TEXT NULL,
  mastery_level TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '掌握程度 0-100',
  last_studied_at DATETIME NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_knowledge_areas_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
  CONSTRAINT fk_knowledge_areas_parent FOREIGN KEY (parent_id) REFERENCES knowledge_areas (id) ON DELETE SET NULL,
  UNIQUE KEY uk_knowledge_areas_name (workspace_id, name),
  INDEX idx_knowledge_areas_parent (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO schema_migrations (version) VALUES ('012_learning_planner');
