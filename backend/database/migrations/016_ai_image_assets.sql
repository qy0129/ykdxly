ALTER TABLE ai_images
  ADD COLUMN asset_path VARCHAR(1024) NULL AFTER image_url;

INSERT IGNORE INTO schema_migrations (version) VALUES ('016_ai_image_assets');
