package com.changlu.planner.agent.subagents.image.tools;

import com.changlu.planner.shared.database.Database;
import java.util.Optional;

/** 文生图记录仓储，按用户/工作区隔离，幂等键保证重试不重复计费。 */
public interface ImageGenerationRepository {
  Optional<ImageRecord> findByIdempotencyKey(String idempotencyKey, Database.Context identity);

  void save(ImageRecord record, Database.Context identity);
}