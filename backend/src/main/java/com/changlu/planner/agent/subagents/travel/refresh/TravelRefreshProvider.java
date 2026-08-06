package com.changlu.planner.agent.subagents.travel.refresh;

import com.google.gson.JsonObject;
import java.time.Duration;
import java.util.List;

/** External data boundary for pre-departure refreshes. Tests provide a network-free Fake. */
@FunctionalInterface
public interface TravelRefreshProvider {
  RefreshBatch refresh(TravelRefreshRepository.TravelPlan plan, String traceId) throws Exception;

  record Snapshot(String provider, String dataType, JsonObject payload, Duration ttl) {
    public Snapshot {
      if (provider == null || provider.isBlank()) throw new IllegalArgumentException("provider_required");
      if (dataType == null || dataType.isBlank()) throw new IllegalArgumentException("data_type_required");
      payload = payload == null ? new JsonObject() : payload.deepCopy();
      ttl = ttl == null || ttl.isNegative() || ttl.isZero() ? Duration.ofHours(1) : ttl;
    }
  }

  record RefreshBatch(List<Snapshot> snapshots, List<String> errors) {
    public RefreshBatch {
      snapshots = snapshots == null ? List.of() : List.copyOf(snapshots);
      errors = errors == null ? List.of() : List.copyOf(errors);
    }
  }
}
