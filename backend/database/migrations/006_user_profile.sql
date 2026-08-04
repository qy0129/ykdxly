ALTER TABLE users
  ADD COLUMN avatar_url VARCHAR(1000) NOT NULL DEFAULT '' AFTER display_name;

INSERT IGNORE INTO schema_migrations (version) VALUES ('006_user_profile');
