CREATE INDEX idx_agent_tool_calls_run_started
  ON agent_tool_calls (run_id, started_at, id);

INSERT IGNORE INTO schema_migrations (version) VALUES ('020_agent_tool_call_order_index');
