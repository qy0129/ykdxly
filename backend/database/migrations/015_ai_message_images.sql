ALTER TABLE ai_messages
  ADD COLUMN image_urls JSON NULL AFTER proposed_changes;

INSERT IGNORE INTO schema_migrations (version) VALUES ('015_ai_message_images');
