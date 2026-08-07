ALTER TABLE plan_tasks
  ADD COLUMN reminder_minutes INT NULL AFTER due_at;

ALTER TABLE schedule_items
  ADD COLUMN reminder_minutes INT NULL AFTER start_at;
