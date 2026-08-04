CREATE TABLE IF NOT EXISTS agent_documents (
  id BINARY(16) NOT NULL PRIMARY KEY,
  workspace_id BINARY(16) NOT NULL,
  user_id BINARY(16) NOT NULL,
  file_name VARCHAR(500) NOT NULL,
  media_type VARCHAR(120) NOT NULL,
  extension VARCHAR(20) NOT NULL,
  content_hash CHAR(64) NOT NULL,
  extracted_chars INT NOT NULL DEFAULT 0,
  vector_indexed TINYINT(1) NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_agent_documents_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
  CONSTRAINT fk_agent_documents_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
  UNIQUE KEY uk_agent_document_content (workspace_id, user_id, content_hash),
  INDEX idx_agent_documents_owner (workspace_id, user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS agent_document_chunks (
  id BINARY(16) NOT NULL PRIMARY KEY,
  document_id BINARY(16) NOT NULL,
  workspace_id BINARY(16) NOT NULL,
  user_id BINARY(16) NOT NULL,
  chunk_index INT NOT NULL,
  content LONGTEXT NOT NULL,
  embedding JSON NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_agent_document_chunks_document FOREIGN KEY (document_id) REFERENCES agent_documents (id) ON DELETE CASCADE,
  CONSTRAINT fk_agent_document_chunks_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
  CONSTRAINT fk_agent_document_chunks_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
  UNIQUE KEY uk_agent_document_chunk (document_id, chunk_index),
  INDEX idx_agent_document_chunks_owner (workspace_id, user_id, document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO schema_migrations (version) VALUES ('009_agent_documents');
