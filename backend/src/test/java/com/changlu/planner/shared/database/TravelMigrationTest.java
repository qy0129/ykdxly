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
    assertEquals(List.of("017_learning_metrics.sql", "018_travel_schedule_context.sql", "019_travel_refresh.sql", "020_reminder_extensions.sql"),
        migrations.subList(migrations.size() - 4, migrations.size()));

    String context = resource("/db/migrations/018_travel_schedule_context.sql");
    String refresh = resource("/db/migrations/019_travel_refresh.sql");
    assertAll(
        () -> assertTrue(context.contains("location_name")),
        () -> assertTrue(context.contains("reservation_required")),
        () -> assertTrue(refresh.contains("travel_data_snapshots")),
        () -> assertTrue(refresh.contains("payload_json")),
        () -> assertTrue(refresh.contains("content_hash")),
        () -> assertTrue(refresh.contains("last_error")),
        () -> assertTrue(refresh.contains("travel_change_drafts")),
        () -> assertTrue(refresh.contains("travel_notifications")));
  }

  private String resource(String path) throws Exception {
    try (InputStream input = getClass().getResourceAsStream(path)) {
      assertNotNull(input, "缺少迁移资源 " + path);
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
