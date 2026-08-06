-- Earlier plan deletion only marked plans as deleted. Hide their still-linked calendar entries
-- so they cannot cause schedule_conflict while the plan is in the recycle bin.
UPDATE schedule_items s
JOIN plans p ON p.id = s.plan_id
SET s.deleted_at = p.deleted_at,
    s.purge_after = DATE_ADD(p.deleted_at, INTERVAL 30 DAY),
    s.version = s.version + 1
WHERE p.deleted_at IS NOT NULL
  AND s.deleted_at IS NULL;
