package com.changlu.planner.shared.database;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class Migration020Test {
  @Test void reminderExtensionIsPackagedAndRegisteredLast() throws Exception {
    Field field = DatabaseMigrator.class.getDeclaredField("MIGRATIONS");
    field.setAccessible(true);
    @SuppressWarnings("unchecked") List<String> migrations = (List<String>) field.get(null);
    assertEquals("020_reminder_extensions.sql", migrations.get(migrations.size() - 1));

    String migration = resource("/db/migrations/020_reminder_extensions.sql");
    assertAll(
        () -> assertTrue(migration.contains("plan_tasks")),
        () -> assertTrue(migration.contains("schedule_items")),
        () -> assertTrue(migration.contains("reminder_minutes")));
  }

  private String resource(String path) throws Exception {
    try (InputStream input = getClass().getResourceAsStream(path)) {
      assertNotNull(input, "缺少迁移资源 " + path);
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
