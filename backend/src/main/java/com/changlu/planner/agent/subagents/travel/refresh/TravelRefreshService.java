package com.changlu.planner.agent.subagents.travel.refresh;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Refreshes confirmed trips and creates reviewable change drafts without mutating the plan. */
public final class TravelRefreshService {
  private static final Set<String> VOLATILE_FIELDS = Set.of("fetchedAt", "updatedAt", "requestId", "traceId");
  private final TravelRefreshRepository repository;
  private final TravelRefreshProvider provider;
  private final Clock clock;
  private final Duration dailyInterval;
  private final Duration urgentInterval;
  private final Duration retryBase;

  public TravelRefreshService(TravelRefreshRepository repository, TravelRefreshProvider provider, Clock clock,
                              Duration dailyInterval, Duration urgentInterval, Duration retryBase) {
    this.repository = repository;
    this.provider = provider;
    this.clock = clock;
    this.dailyInterval = positive(dailyInterval, Duration.ofHours(24));
    this.urgentInterval = positive(urgentInterval, Duration.ofHours(6));
    this.retryBase = positive(retryBase, Duration.ofMinutes(15));
  }

  public RunSummary runOnce() throws Exception {
    Instant now = clock.instant();
    int refreshed = 0, drafts = 0, failures = 0;
    for (TravelRefreshRepository.TravelPlan plan : repository.duePlans(now)) {
      if (!eligible(plan, now)) continue;
      try {
        TravelRefreshProvider.RefreshBatch batch = provider.refresh(plan, "travel-refresh:" + plan.planId());
        if (batch.snapshots().isEmpty() && !batch.errors().isEmpty()) throw new RefreshUnavailableException();
        JsonArray changes = new JsonArray();
        for (TravelRefreshProvider.Snapshot snapshot : batch.snapshots()) {
          String hash = hash(snapshot.payload());
          var previous = repository.latest(plan.planId(), snapshot.provider(), snapshot.dataType());
          if (previous.isPresent() && !previous.get().contentHash().equals(hash)
              && materialChange(snapshot.dataType(), previous.get().payload(), snapshot.payload())) {
            JsonObject change = new JsonObject();
            change.addProperty("provider", snapshot.provider());
            change.addProperty("dataType", snapshot.dataType());
            change.addProperty("previousHash", previous.get().contentHash());
            change.addProperty("currentHash", hash);
            change.add("before", previous.get().payload().deepCopy());
            change.add("after", snapshot.payload().deepCopy());
            changes.add(change);
          }
          repository.saveSnapshot(plan, snapshot, hash, now, now.plus(snapshot.ttl()),
              batch.errors().isEmpty() ? null : String.join(",", batch.errors()));
        }
        if (!changes.isEmpty() && repository.createChangeDraft(plan, changes, hash(changes), now.plus(Duration.ofDays(7)))) drafts++;
        if (batch.errors().isEmpty()) repository.recordSuccess(plan, now, nextRefresh(now, plan.departureAt()));
        else {
          failures++;
          int failureCount = plan.failureCount() + 1;
          repository.recordFailure(plan, String.join(",", batch.errors()), failureCount, now.plus(backoff(failureCount)));
        }
        refreshed++;
      } catch (Exception error) {
        failures++;
        int failureCount = plan.failureCount() + 1;
        repository.recordFailure(plan, safeError(error), failureCount, now.plus(backoff(failureCount)));
      }
    }
    return new RunSummary(refreshed, drafts, failures);
  }

  public boolean eligible(TravelRefreshRepository.TravelPlan plan, Instant now) {
    if (!plan.confirmed() || !plan.active() || plan.departureAt() == null || plan.endAt() == null) return false;
    return !now.isBefore(plan.departureAt().minus(Duration.ofDays(7))) && now.isBefore(plan.endAt());
  }

  public Instant nextRefresh(Instant now, Instant departureAt) {
    Duration untilDeparture = departureAt == null ? Duration.ZERO : Duration.between(now, departureAt);
    return now.plus(!untilDeparture.isNegative() && untilDeparture.compareTo(Duration.ofHours(48)) > 0
        ? dailyInterval : urgentInterval);
  }

  Duration backoff(int failureCount) {
    int exponent = Math.max(0, Math.min(5, failureCount - 1));
    Duration value = retryBase.multipliedBy(1L << exponent);
    return value.compareTo(urgentInterval) > 0 ? urgentInterval : value;
  }

  static boolean materialChange(String dataType, JsonObject before, JsonObject after) {
    if (canonical(before).equals(canonical(after))) return false;
    if ("routing".equals(dataType)) {
      Double previous = durationSeconds(before), current = durationSeconds(after);
      if (previous != null && current != null && previous > 0) return Math.abs(current - previous) / previous >= 0.30;
    }
    return true;
  }

  static String hash(JsonElement value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(canonical(value).getBytes(StandardCharsets.UTF_8));
      StringBuilder result = new StringBuilder(64);
      for (byte item : digest) result.append("%02x".formatted(item));
      return result.toString();
    } catch (Exception error) {
      throw new IllegalStateException("travel_hash_unavailable", error);
    }
  }

  private static String canonical(JsonElement value) {
    if (value == null || value.isJsonNull()) return "null";
    if (value.isJsonPrimitive()) return value.toString();
    if (value.isJsonArray()) {
      List<String> items = new ArrayList<>();
      for (JsonElement item : value.getAsJsonArray()) items.add(canonical(item));
      return "[" + String.join(",", items) + "]";
    }
    return "{" + value.getAsJsonObject().entrySet().stream()
        .filter(entry -> !VOLATILE_FIELDS.contains(entry.getKey()))
        .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
        .map(entry -> entry.getKey() + ":" + canonical(entry.getValue()))
        .reduce((left, right) -> left + "," + right).orElse("") + "}";
  }

  private static Double durationSeconds(JsonElement value) {
    if (value == null || value.isJsonNull()) return null;
    if (value.isJsonObject()) {
      JsonObject object = value.getAsJsonObject();
      for (String key : List.of("durationSeconds", "duration")) {
        if (object.has(key) && object.get(key).isJsonPrimitive()) try { return object.get(key).getAsDouble(); }
        catch (RuntimeException ignored) { }
      }
      for (JsonElement child : object.asMap().values()) {
        Double result = durationSeconds(child); if (result != null) return result;
      }
    } else if (value.isJsonArray()) {
      for (JsonElement child : value.getAsJsonArray()) {
        Double result = durationSeconds(child); if (result != null) return result;
      }
    }
    return null;
  }

  private Duration positive(Duration value, Duration fallback) {
    return value == null || value.isZero() || value.isNegative() ? fallback : value;
  }

  private String safeError(Exception error) {
    if (error instanceof RefreshUnavailableException) return "travel_provider_unavailable";
    return "travel_refresh_failed:" + error.getClass().getSimpleName();
  }

  public record RunSummary(int refreshedPlans, int changeDrafts, int failures) {}
  private static final class RefreshUnavailableException extends Exception {}
}
