package com.changlu.planner.shared.database;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class TravelMigrationTest {
  @Test void travelMigrationsArePackagedAndRegisteredInOrder() throws Exception {
    Field field = DatabaseMigrator.class.getDeclaredField("MIGRATIONS");
    field.setAccessible(true);
    @SuppressWarnings("unchecked") List<String> migrations = (List<String>) field.get(null);
    assertEquals(List.of("017_travel_schedule_context.sql", "018_travel_refresh.sql",
            "019_agent_run_lookup_indexes.sql", "020_agent_tool_call_order_index.sql",
            "021_deleted_plan_schedule_cleanup.sql"),
        migrations.subList(migrations.size() - 5, migrations.size()));

    String refresh = resource("/db/migrations/018_travel_refresh.sql");
    String runIndexes = resource("/db/migrations/019_agent_run_lookup_indexes.sql");
    String toolCallIndex = resource("/db/migrations/020_agent_tool_call_order_index.sql");
    String deletedPlanCleanup = resource("/db/migrations/021_deleted_plan_schedule_cleanup.sql");
    assertAll(
        () -> assertTrue(refresh.contains("travel_data_snapshots")),
        () -> assertTrue(refresh.contains("payload_json")),
        () -> assertTrue(refresh.contains("content_hash")),
        () -> assertTrue(refresh.contains("last_error")),
        () -> assertTrue(refresh.contains("travel_change_drafts")),
        () -> assertTrue(refresh.contains("travel_notifications")),
        () -> assertTrue(runIndexes.contains("idx_agent_runs_conversation_latest")),
        () -> assertTrue(runIndexes.contains("workspace_id, user_id, conversation_id, updated_at, id")),
        () -> assertTrue(runIndexes.contains("idx_agent_runs_pending_draft_latest")),
        () -> assertTrue(toolCallIndex.contains("idx_agent_tool_calls_run_started")),
        () -> assertTrue(toolCallIndex.contains("run_id, started_at, id")),
        () -> assertTrue(deletedPlanCleanup.contains("JOIN plans p ON p.id = s.plan_id")),
        () -> assertTrue(deletedPlanCleanup.contains("s.deleted_at IS NULL")));
  }

  private String resource(String path) throws Exception {
    try (InputStream input = getClass().getResourceAsStream(path)) {
      assertNotNull(input, "缺少迁移资源 " + path);
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
