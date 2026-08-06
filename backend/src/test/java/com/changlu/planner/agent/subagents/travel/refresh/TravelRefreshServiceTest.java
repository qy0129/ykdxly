package com.changlu.planner.agent.subagents.travel.refresh;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TravelRefreshServiceTest {
  private static final Instant NOW = Instant.parse("2026-08-05T02:00:00Z");

  @Test void refreshWindowUsesDailyThenSixHourlyCadence() {
    FakeRepository repository = new FakeRepository(plan(NOW.plus(Duration.ofDays(4)), 0));
    TravelRefreshService service = service(repository, weather("晴", "2026-08-05T02:00:00Z"));
    assertEquals(NOW.plus(Duration.ofHours(24)), service.nextRefresh(NOW, NOW.plus(Duration.ofDays(4))));
    assertEquals(NOW.plus(Duration.ofHours(6)), service.nextRefresh(NOW, NOW.plus(Duration.ofHours(47))));
    assertFalse(service.eligible(plan(NOW.plus(Duration.ofDays(8)), 0), NOW));
    assertTrue(service.eligible(plan(NOW.plus(Duration.ofDays(7)), 0), NOW));
  }

  @Test void changedWeatherCreatesOneDeduplicatedDraftAndIgnoresFetchedAt() throws Exception {
    FakeRepository repository = new FakeRepository(plan(NOW.plus(Duration.ofDays(4)), 0));
    MutableProvider provider = new MutableProvider(weather("晴", "2026-08-05T02:00:00Z"));
    TravelRefreshService service = service(repository, provider);

    assertEquals(0, service.runOnce().changeDrafts());
    provider.payload = weather("晴", "2026-08-05T08:00:00Z");
    assertEquals(0, service.runOnce().changeDrafts(), "抓取时间变化不应生成草案");
    provider.payload = weather("暴雨", "2026-08-05T09:00:00Z");
    assertEquals(1, service.runOnce().changeDrafts());
    assertEquals(1, repository.drafts.size());
    assertEquals(0, service.runOnce().changeDrafts(), "相同内容不得重复通知");
  }

  @Test void routeDurationMustChangeByThirtyPercent() {
    JsonObject before = route(1000); JsonObject smallChange = route(1200); JsonObject majorChange = route(1400);
    assertFalse(TravelRefreshService.materialChange("routing", before, smallChange));
    assertTrue(TravelRefreshService.materialChange("routing", before, majorChange));
  }

  @Test void failuresAreSanitizedAndUseExponentialBackoff() throws Exception {
    FakeRepository repository = new FakeRepository(plan(NOW.plus(Duration.ofDays(2)), 3));
    TravelRefreshProvider failing = (plan, traceId) -> { throw new IllegalStateException("secret-key-value"); };
    TravelRefreshService.RunSummary result = service(repository, failing).runOnce();
    assertEquals(1, result.failures());
    assertEquals("travel_refresh_failed:IllegalStateException", repository.lastError);
    assertEquals(4, repository.failureCount);
    assertEquals(NOW.plus(Duration.ofHours(2)), repository.nextRetryAt);
  }

  private TravelRefreshService service(FakeRepository repository, JsonObject payload) {
    return service(repository, new MutableProvider(payload));
  }

  private TravelRefreshService service(FakeRepository repository, TravelRefreshProvider provider) {
    return new TravelRefreshService(repository, provider, Clock.fixed(NOW, ZoneOffset.UTC),
        Duration.ofHours(24), Duration.ofHours(6), Duration.ofMinutes(15));
  }

  private TravelRefreshRepository.TravelPlan plan(Instant departure, int failureCount) {
    return new TravelRefreshRepository.TravelPlan(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
        "青岛旅行计划", "青岛", departure, departure.plus(Duration.ofDays(10)), true, true,
        failureCount, new JsonObject());
  }

  private static JsonObject weather(String condition, String fetchedAt) {
    JsonObject payload = new JsonObject(); JsonArray rows = new JsonArray(); JsonObject day = new JsonObject();
    day.addProperty("date", "2026-08-09"); day.addProperty("condition", condition);
    day.addProperty("fetchedAt", fetchedAt); rows.add(day); payload.add("weather", rows); return payload;
  }

  private static JsonObject route(int duration) {
    JsonObject value = new JsonObject(); value.addProperty("durationSeconds", duration); return value;
  }

  private static final class MutableProvider implements TravelRefreshProvider {
    private JsonObject payload;
    private MutableProvider(JsonObject payload) { this.payload = payload; }
    @Override public RefreshBatch refresh(TravelRefreshRepository.TravelPlan plan, String traceId) {
      return new RefreshBatch(List.of(new Snapshot("amap", "weather", payload, Duration.ofHours(6))), List.of());
    }
  }

  private static final class FakeRepository implements TravelRefreshRepository {
    private final TravelPlan plan;
    private final Map<String, StoredSnapshot> snapshots = new HashMap<>();
    private final Set<String> drafts = new HashSet<>();
    private String lastError;
    private int failureCount;
    private Instant nextRetryAt;
    private FakeRepository(TravelPlan plan) { this.plan = plan; }
    @Override public List<TravelPlan> duePlans(Instant now) { return List.of(plan); }
    @Override public Optional<StoredSnapshot> latest(UUID planId, String provider, String dataType) {
      return Optional.ofNullable(snapshots.get(provider + ":" + dataType));
    }
    @Override public void saveSnapshot(TravelPlan plan, TravelRefreshProvider.Snapshot snapshot, String contentHash,
                                       Instant fetchedAt, Instant expiresAt, String lastError) {
      snapshots.put(snapshot.provider() + ":" + snapshot.dataType(), new StoredSnapshot(contentHash, snapshot.payload(), fetchedAt));
    }
    @Override public boolean createChangeDraft(TravelPlan plan, JsonArray changes, String contentHash, Instant expiresAt) {
      return drafts.add(contentHash);
    }
    @Override public void recordSuccess(TravelPlan plan, Instant refreshedAt, Instant nextRefreshAt) { }
    @Override public void recordFailure(TravelPlan plan, String errorCode, int failureCount, Instant nextRetryAt) {
      this.lastError = errorCode; this.failureCount = failureCount; this.nextRetryAt = nextRetryAt;
    }
  }
}
