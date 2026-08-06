package com.changlu.planner.agent.subagents.travel.refresh;

import com.changlu.planner.shared.database.Database;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** MySQL persistence for refresh snapshots, retry state, change drafts and user notifications. */
public final class JdbcTravelRefreshRepository implements TravelRefreshRepository {
  private final Database database;
  private final Gson gson = new Gson();

  public JdbcTravelRefreshRepository(Database database) { this.database = database; }

  @Override public List<TravelPlan> duePlans(Instant now) throws Exception {
    String sql = "SELECT p.id,p.workspace_id,p.owner_id,p.title,COALESCE(c.failure_count,0) failure_count,MIN(s.start_at) departure_at,"
        + "MAX(DATE_ADD(s.start_at,INTERVAL s.duration_minutes MINUTE)) end_at "
        + "FROM plans p JOIN schedule_items s ON s.plan_id=p.id AND s.deleted_at IS NULL "
        + "LEFT JOIN travel_refresh_configs c ON c.plan_id=p.id "
        + "WHERE p.deleted_at IS NULL AND p.status='active' AND p.description LIKE '由旅游 Agent%' "
        + "AND (c.plan_id IS NULL OR (c.enabled=TRUE AND (c.next_refresh_at IS NULL OR c.next_refresh_at<=?))) "
        + "GROUP BY p.id,p.workspace_id,p.owner_id,p.title,c.failure_count "
        + "HAVING departure_at<=? AND end_at>? ORDER BY departure_at";
    List<TravelPlan> result = new ArrayList<>();
    try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setTimestamp(1, Timestamp.from(now));
      statement.setTimestamp(2, Timestamp.from(now.plus(Duration.ofDays(7))));
      statement.setTimestamp(3, Timestamp.from(now));
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          UUID planId = Database.bytesUuid(rows.getBytes("id"));
          JsonObject context = context(connection, planId);
          String title = rows.getString("title");
          String destination = title == null ? "" : title.replaceFirst("旅行计划$", "");
          result.add(new TravelPlan(planId, Database.bytesUuid(rows.getBytes("workspace_id")),
              Database.bytesUuid(rows.getBytes("owner_id")), title, destination,
              rows.getTimestamp("departure_at").toInstant(), rows.getTimestamp("end_at").toInstant(),
              true, true, rows.getInt("failure_count"), context));
        }
      }
    }
    return result;
  }

  @Override public Optional<StoredSnapshot> latest(UUID planId, String provider, String dataType) throws Exception {
    String sql = "SELECT content_hash,payload_json,fetched_at FROM travel_data_snapshots "
        + "WHERE plan_id=? AND provider=? AND data_type=? ORDER BY fetched_at DESC,created_at DESC LIMIT 1";
    try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setBytes(1, Database.uuidBytes(planId)); statement.setString(2, provider); statement.setString(3, dataType);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) return Optional.empty();
        return Optional.of(new StoredSnapshot(rows.getString("content_hash"),
            JsonParser.parseString(rows.getString("payload_json")).getAsJsonObject(), rows.getTimestamp("fetched_at").toInstant()));
      }
    }
  }

  @Override public void saveSnapshot(TravelPlan plan, TravelRefreshProvider.Snapshot snapshot, String contentHash,
                                     Instant fetchedAt, Instant expiresAt, String lastError) throws Exception {
    String sql = "INSERT IGNORE INTO travel_data_snapshots "
        + "(id,workspace_id,plan_id,provider,data_type,payload_json,content_hash,fetched_at,expires_at,last_error) "
        + "VALUES (?,?,?,?,?,?,?,?,?,?)";
    try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setBytes(1, Database.uuidBytes(UUID.randomUUID())); statement.setBytes(2, Database.uuidBytes(plan.workspaceId()));
      statement.setBytes(3, Database.uuidBytes(plan.planId())); statement.setString(4, snapshot.provider());
      statement.setString(5, snapshot.dataType()); statement.setString(6, gson.toJson(snapshot.payload()));
      statement.setString(7, contentHash); statement.setTimestamp(8, Timestamp.from(fetchedAt));
      statement.setTimestamp(9, Timestamp.from(expiresAt)); statement.setString(10, lastError); statement.executeUpdate();
    }
  }

  @Override public boolean createChangeDraft(TravelPlan plan, JsonArray changes, String contentHash, Instant expiresAt) throws Exception {
    String draftSql = "INSERT IGNORE INTO travel_change_drafts "
        + "(id,workspace_id,user_id,plan_id,reason,changes_json,content_hash,expires_at) VALUES (?,?,?,?,?,?,?,?)";
    try (Connection connection = database.connection()) {
      connection.setAutoCommit(false);
      try {
        UUID draftId = UUID.randomUUID(); int inserted;
        try (PreparedStatement statement = connection.prepareStatement(draftSql)) {
          statement.setBytes(1, Database.uuidBytes(draftId)); statement.setBytes(2, Database.uuidBytes(plan.workspaceId()));
          statement.setBytes(3, Database.uuidBytes(plan.userId())); statement.setBytes(4, Database.uuidBytes(plan.planId()));
          statement.setString(5, "出发前资料发生变化，请确认是否调整行程"); statement.setString(6, gson.toJson(changes));
          statement.setString(7, contentHash); statement.setTimestamp(8, Timestamp.from(expiresAt)); inserted = statement.executeUpdate();
        }
        if (inserted == 0) { connection.rollback(); return false; }
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO travel_notifications (id,workspace_id,user_id,plan_id,draft_id,title,message) VALUES (?,?,?,?,?,?,?)")) {
          statement.setBytes(1, Database.uuidBytes(UUID.randomUUID())); statement.setBytes(2, Database.uuidBytes(plan.workspaceId()));
          statement.setBytes(3, Database.uuidBytes(plan.userId())); statement.setBytes(4, Database.uuidBytes(plan.planId()));
          statement.setBytes(5, Database.uuidBytes(draftId)); statement.setString(6, plan.title() + "有新变化");
          statement.setString(7, "天气、景区状态或路线发生变化，已生成待确认变更草案。"); statement.executeUpdate();
        }
        connection.commit(); return true;
      } catch (Exception error) { connection.rollback(); throw error; }
      finally { connection.setAutoCommit(true); }
    }
  }

  @Override public void recordSuccess(TravelPlan plan, Instant refreshedAt, Instant nextRefreshAt) throws Exception {
    updateConfig(plan, refreshedAt, nextRefreshAt, 0, null);
  }

  @Override public void recordFailure(TravelPlan plan, String errorCode, int failureCount, Instant nextRetryAt) throws Exception {
    updateConfig(plan, null, nextRetryAt, failureCount, errorCode);
    try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement(
        "UPDATE travel_data_snapshots SET last_error=? WHERE plan_id=? ORDER BY fetched_at DESC LIMIT 1")) {
      statement.setString(1, errorCode); statement.setBytes(2, Database.uuidBytes(plan.planId())); statement.executeUpdate();
    }
  }

  private void updateConfig(TravelPlan plan, Instant refreshedAt, Instant nextRefreshAt, int failures, String lastError) throws Exception {
    String sql = "INSERT INTO travel_refresh_configs (plan_id,workspace_id,next_refresh_at,last_refreshed_at,failure_count,last_error) "
        + "VALUES (?,?,?,?,?,?) ON DUPLICATE KEY UPDATE next_refresh_at=VALUES(next_refresh_at),"
        + "last_refreshed_at=COALESCE(VALUES(last_refreshed_at),last_refreshed_at),failure_count=VALUES(failure_count),"
        + "last_error=VALUES(last_error)";
    try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setBytes(1, Database.uuidBytes(plan.planId())); statement.setBytes(2, Database.uuidBytes(plan.workspaceId()));
      statement.setTimestamp(3, Timestamp.from(nextRefreshAt)); statement.setTimestamp(4, refreshedAt == null ? null : Timestamp.from(refreshedAt));
      statement.setInt(5, failures); statement.setString(6, lastError); statement.executeUpdate();
    }
  }

  private JsonObject context(Connection connection, UUID planId) throws Exception {
    JsonObject result = new JsonObject(); JsonArray routes = new JsonArray(); JsonObject previous = null;
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT latitude,longitude,coordinate_system,timezone_id,location_name,start_at FROM schedule_items "
            + "WHERE plan_id=? AND deleted_at IS NULL ORDER BY start_at")) {
      statement.setBytes(1, Database.uuidBytes(planId));
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          if (rows.getObject("latitude") == null || rows.getObject("longitude") == null) continue;
          JsonObject point = new JsonObject(); point.addProperty("lat", rows.getBigDecimal("latitude"));
          point.addProperty("lng", rows.getBigDecimal("longitude")); point.addProperty("name", rows.getString("location_name"));
          if (!result.has("destinationLat")) {
            result.addProperty("destinationLat", rows.getBigDecimal("latitude"));
            result.addProperty("destinationLng", rows.getBigDecimal("longitude"));
            result.addProperty("coordinateSystem", rows.getString("coordinate_system"));
            result.addProperty("timezone", rows.getString("timezone_id"));
          }
          if (previous != null) {
            JsonObject route = new JsonObject(); route.addProperty("origin", previous.get("lng").getAsString() + "," + previous.get("lat").getAsString());
            route.addProperty("destination", point.get("lng").getAsString() + "," + point.get("lat").getAsString());
            route.addProperty("mode", "walking"); routes.add(route);
          }
          previous = point;
        }
      }
    }
    result.add("routes", routes); return result;
  }
}
