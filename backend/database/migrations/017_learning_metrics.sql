ALTER TABLE learning_goals
  ADD COLUMN target_metrics JSON NULL COMMENT '领域自适应量化指标，如 [{"label":"雅思总分","value":"7","unit":"分"}]' AFTER status,
  ADD COLUMN milestones JSON NULL COMMENT '里程碑字符串数组' AFTER target_metrics;

INSERT IGNORE INTO schema_migrations (version) VALUES ('017_learning_metrics');
