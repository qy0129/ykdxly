ALTER TABLE plan_tasks
  ADD COLUMN recurrence_type VARCHAR(24) NOT NULL DEFAULT 'once' AFTER due_at,
  ADD COLUMN schedule_start_date DATE NULL AFTER recurrence_type,
  ADD COLUMN recurrence_end_date DATE NULL AFTER schedule_start_date,
  ADD COLUMN scheduled_time TIME NULL AFTER recurrence_end_date;

CREATE INDEX idx_schedule_task_recurrence
  ON schedule_items (task_id, source_type, deleted_at, start_at);
