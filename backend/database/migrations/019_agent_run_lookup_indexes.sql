CREATE INDEX idx_agent_runs_conversation_latest
  ON agent_runs (workspace_id, user_id, conversation_id, updated_at, id);

CREATE INDEX idx_agent_runs_pending_draft_latest
  ON agent_runs (pending_draft_id, workspace_id, user_id, updated_at, id);

INSERT IGNORE INTO schema_migrations (version) VALUES ('019_agent_run_lookup_indexes');
