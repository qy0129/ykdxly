package com.changlu.planner.agent.subagents.travel.refresh;

import com.changlu.planner.agent.subagents.travel.external.TravelApiClient;
import com.changlu.planner.agent.subagents.travel.services.AmapAttractionResearchService;
import com.changlu.planner.agent.subagents.travel.services.AmapRoutingService;
import com.changlu.planner.agent.subagents.travel.services.AmapWeatherForecastService;
import com.changlu.planner.shared.config.EnvironmentConfig;
import com.changlu.planner.shared.database.Database;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Small lifecycle wrapper for periodic refresh; one run is bounded by the provider HTTP timeouts. */
public final class TravelRefreshScheduler implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(TravelRefreshScheduler.class);
  private final TravelRefreshService service;
  private final boolean enabled;
  private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
      Thread.ofPlatform().daemon(true).name("travel-refresh").factory());

  public TravelRefreshScheduler(TravelRefreshService service, boolean enabled) {
    this.service = service; this.enabled = enabled;
  }

  public static TravelRefreshScheduler production(Database database) {
    TravelApiClient http = new TravelApiClient();
    TravelRefreshProvider provider = new ProductionTravelRefreshProvider(
        new AmapWeatherForecastService(http), new AmapAttractionResearchService(http), new AmapRoutingService(http));
    TravelRefreshService service = new TravelRefreshService(new JdbcTravelRefreshRepository(database), provider,
        Clock.systemUTC(), hours("TRAVEL_REFRESH_DAILY_HOURS", "travel.refresh.daily-hours", 24),
        hours("TRAVEL_REFRESH_URGENT_HOURS", "travel.refresh.urgent-hours", 6),
        minutes("TRAVEL_REFRESH_RETRY_BASE_MINUTES", "travel.refresh.retry-base-minutes", 15));
    boolean enabled = Boolean.parseBoolean(EnvironmentConfig.value("TRAVEL_REFRESH_ENABLED", "travel.refresh.enabled", "true"));
    return new TravelRefreshScheduler(service, enabled);
  }

  public void start() {
    if (!enabled) return;
    executor.scheduleWithFixedDelay(() -> {
      try {
        TravelRefreshService.RunSummary result = service.runOnce();
        LOG.info("[travel-refresh] refreshed={} drafts={} failures={}",
            result.refreshedPlans(), result.changeDrafts(), result.failures());
      } catch (Exception error) {
        LOG.warn("[travel-refresh] cycle_failed type={}", error.getClass().getSimpleName());
      }
    }, 1, 15, TimeUnit.MINUTES);
  }

  @Override public void close() { executor.shutdownNow(); }

  private static Duration hours(String environment, String property, int fallback) {
    return Duration.ofHours(number(environment, property, fallback));
  }
  private static Duration minutes(String environment, String property, int fallback) {
    return Duration.ofMinutes(number(environment, property, fallback));
  }
  private static int number(String environment, String property, int fallback) {
    try { return Math.max(1, Integer.parseInt(EnvironmentConfig.value(environment, property, String.valueOf(fallback)))); }
    catch (NumberFormatException ignored) { return fallback; }
  }
}
