package com.changlu.planner.agent.subagents.image.tools;

import com.changlu.planner.agent.subagents.image.ImageGenerationRequest;
import com.changlu.planner.agent.subagents.image.ImageGenerationResult;
import com.changlu.planner.agent.subagents.image.ImagePrompt;
import com.changlu.planner.shared.database.Database;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 文生图业务服务：参数校验、幂等查询、调用 Provider、持久化结果。
 * 限流/5xx 等可重试错误在本层有界退避重试，幂等键保证成功结果不重复计费。
 */
public final class ImageGenerationService {
  private static final Logger LOG = LoggerFactory.getLogger(ImageGenerationService.class);
  private static final int MAX_ATTEMPTS = 2;
  private static final long BACKOFF_MILLIS = 400;

  private final ImageGenerationProvider provider;
  private final ImageGenerationRepository repository;
  private final ImagePrompt prompt;
  private final ImageAssetStore assets = new ImageAssetStore();

  public ImageGenerationService(ImageGenerationProvider provider,
                                ImageGenerationRepository repository,
                                ImagePrompt prompt) {
    this.provider = provider;
    this.repository = repository;
    this.prompt = prompt;
  }

  public ImageGenerationResult generate(ImageGenerationRequest request, String idempotencyKey,
                                        String traceId, Database.Context identity) throws Exception {
    long startedAt = System.nanoTime();
    String promptText = prompt.requirePrompt(request.prompt());
    String size = prompt.requireSize(request.size());
    String style = prompt.requireStyle(request.style());
    int quality = prompt.requireQuality(request.quality());

    if (idempotencyKey != null && !idempotencyKey.isBlank()) {
      Optional<ImageRecord> cached = repository.findByIdempotencyKey(idempotencyKey, identity);
      if (cached.isPresent() && "SUCCESS".equals(cached.get().status())) {
        ImageRecord record = cached.get();
        return new ImageGenerationResult(record.requestId(), record.status(), record.imageUrl(),
            record.size(), record.style(), millis(startedAt), null);
      }
    }

    String requestId = UUID.randomUUID().toString();
    ImageGenerationException last = null;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      try {
        String url = provider.generate(prompt.enrich(promptText, style), size, style, quality);
        String assetPath = assets.save(url, requestId);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
          repository.save(new ImageRecord(requestId, idempotencyKey, promptText, size, style, "SUCCESS",
              url, assetPath, provider.name(), null, traceId, System.currentTimeMillis()), identity);
        }
        return new ImageGenerationResult(requestId, "SUCCESS", url, size, style, millis(startedAt), null);
      } catch (ImageGenerationException error) {
        last = error;
        if (!error.retryable() || attempt == MAX_ATTEMPTS) break;
        sleep(attempt);
      }
    }
    saveFailure(requestId, idempotencyKey, promptText, size, style, traceId, identity, last.code());
    throw last == null
        ? new ImageGenerationException("INTERNAL", "图片生成内部错误", false) : last;
  }

  private void saveFailure(String requestId, String idempotencyKey, String prompt, String size,
                           String style, String traceId, Database.Context identity, String errorCode) {
    try {
      if (idempotencyKey == null || idempotencyKey.isBlank()) return;
      repository.save(new ImageRecord(requestId, idempotencyKey, prompt, size, style, "FAILED", null,
          null, provider.name(), errorCode, traceId, System.currentTimeMillis()), identity);
    } catch (Exception ignored) {
      // 失败记录不可写时不影响主流程
    }
  }

  private void sleep(int attempt) {
    try {
      Thread.sleep(BACKOFF_MILLIS * attempt);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
    }
  }

  private long millis(long startedAtNanos) { return (System.nanoTime() - startedAtNanos) / 1_000_000; }
}
