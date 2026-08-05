package com.changlu.planner.agent.subagents.image.tools;

import com.changlu.planner.shared.database.Database;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 内存仓储，仅用于测试，不做跨进程去重。 */
public final class InMemoryImageGenerationRepository implements ImageGenerationRepository {
  private final List<ImageRecord> records = new ArrayList<>();

  @Override public synchronized Optional<ImageRecord> findByIdempotencyKey(String idempotencyKey, Database.Context identity) {
    if (idempotencyKey == null) return Optional.empty();
    return records.stream().filter(record -> idempotencyKey.equals(record.idempotencyKey())).findFirst();
  }

  @Override public synchronized void save(ImageRecord record, Database.Context identity) {
    records.add(record);
  }

  public int size() { return records.size(); }
  public List<ImageRecord> all() { return List.copyOf(records); }
}