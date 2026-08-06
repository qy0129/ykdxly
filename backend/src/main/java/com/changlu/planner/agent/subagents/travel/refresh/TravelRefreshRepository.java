package com.changlu.planner.agent.subagents.travel.refresh;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence boundary kept narrow so refresh timing and change detection stay deterministic in tests. */
public interface TravelRefreshRepository {
  List<TravelPlan> duePlans(Instant now) throws Exception;
  Optional<StoredSnapshot> latest(UUID planId, String provider, String dataType) throws Exception;
  void saveSnapshot(TravelPlan plan, TravelRefreshProvider.Snapshot snapshot, String contentHash,
                    Instant fetchedAt, Instant expiresAt, String lastError) throws Exception;
  boolean createChangeDraft(TravelPlan plan, JsonArray changes, String contentHash, Instant expiresAt) throws Exception;
  void recordSuccess(TravelPlan plan, Instant refreshedAt, Instant nextRefreshAt) throws Exception;
  void recordFailure(TravelPlan plan, String errorCode, int failureCount, Instant nextRetryAt) throws Exception;

  record TravelPlan(UUID planId, UUID workspaceId, UUID userId, String title, String destination,
                    Instant departureAt, Instant endAt, boolean confirmed, boolean active,
                    int failureCount, JsonObject context) {
    public TravelPlan {
      context = context == null ? new JsonObject() : context.deepCopy();
    }
  }

  record StoredSnapshot(String contentHash, JsonObject payload, Instant fetchedAt) {
    public StoredSnapshot {
      payload = payload == null ? new JsonObject() : payload.deepCopy();
    }
  }
}
