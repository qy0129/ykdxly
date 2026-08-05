ALTER TABLE agent_runs ADD COLUMN state JSON NULL COMMENT '主 Agent 循环累积状态：步骤摘要、任务数据、迭代数、待确认草稿、用户回答' AFTER result;

CREATE TABLE IF NOT EXISTS agent_run_steps (
  id BINARY(16) NOT NULL PRIMARY KEY,
  run_id BINARY(16) NOT NULL,
  seq INT NOT NULL,
  parent_step_id BINARY(16) NULL,
  step_level VARCHAR(10) NOT NULL DEFAULT 'main',
  executor_type VARCHAR(20) NOT NULL,
  executor_name VARCHAR(80) NOT NULL,
  label VARCHAR(240) NOT NULL,
  message VARCHAR(1000) NULL,
  status VARCHAR(32) NOT NULL,
  result JSON NULL,
  tool_call_id VARCHAR(100) NULL,
  duration_ms INT NULL,
  started_at DATETIME NOT NULL,
  completed_at DATETIME NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_agent_run_steps_run FOREIGN KEY (run_id) REFERENCES agent_runs (id) ON DELETE CASCADE,
  CONSTRAINT fk_agent_run_steps_parent FOREIGN KEY (parent_step_id) REFERENCES agent_run_steps (id) ON DELETE CASCADE,
  INDEX idx_agent_run_steps_run (run_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO schema_migrations (version) VALUES ('013_agent_loop');
