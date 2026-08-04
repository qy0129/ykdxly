ALTER TABLE ai_conversations
  ADD COLUMN context_summary LONGTEXT NULL AFTER source_channel,
  ADD COLUMN summarized_message_count INT UNSIGNED NOT NULL DEFAULT 0 AFTER context_summary,
  ADD COLUMN context_summary_updated_at DATETIME NULL AFTER summarized_message_count;

CREATE TABLE IF NOT EXISTS ai_memories (
  id BINARY(16) NOT NULL PRIMARY KEY,
  user_id BINARY(16) NOT NULL,
  memory_key VARCHAR(120) NOT NULL,
  category VARCHAR(32) NOT NULL,
  content TEXT NOT NULL,
  source_conversation_id BINARY(16) NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_ai_memories_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT fk_ai_memories_conversation FOREIGN KEY (source_conversation_id)
    REFERENCES ai_conversations (id) ON DELETE SET NULL,
  UNIQUE KEY uk_ai_memories_user_key (user_id, memory_key),
  INDEX idx_ai_memories_user_updated (user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO schema_migrations (version) VALUES ('010_ai_conversations_memory');
