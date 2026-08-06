ALTER TABLE schedule_items
  ADD COLUMN location_name VARCHAR(240) NULL,
  ADD COLUMN latitude DECIMAL(10,7) NULL,
  ADD COLUMN longitude DECIMAL(10,7) NULL,
  ADD COLUMN coordinate_system VARCHAR(20) NULL,
  ADD COLUMN timezone_id VARCHAR(80) NULL,
  ADD COLUMN source_url TEXT NULL,
  ADD COLUMN reservation_required BOOLEAN NULL;

INSERT IGNORE INTO schema_migrations (version) VALUES ('017_travel_schedule_context');
