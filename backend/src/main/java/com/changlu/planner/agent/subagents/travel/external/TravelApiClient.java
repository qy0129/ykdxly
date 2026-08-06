package com.changlu.planner.agent.subagents.travel.external;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared HTTP boundary for travel providers. Never logs URLs, keys, coordinates or response bodies. */
public final class TravelApiClient {
  private static final Logger LOG = LoggerFactory.getLogger(TravelApiClient.class);
  private final HttpClient http;
  private final Duration requestTimeout;
  private final int maxAttempts;
  private final long minimumIntervalMs;
  private final Map<String, Long> nextAllowedAt = new ConcurrentHashMap<>();

  public TravelApiClient() {
    this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
        Duration.ofSeconds(10), 2, 100);
  }

  public TravelApiClient(HttpClient http, Duration requestTimeout, int maxAttempts,
                         long minimumIntervalMs) {
    this.http = http;
    this.requestTimeout = requestTimeout;
    this.maxAttempts = Math.max(1, maxAttempts);
    this.minimumIntervalMs = Math.max(0, minimumIntervalMs);
  }

  public JsonObject getJson(String provider, URI uri, String traceId) throws Exception {
    return getJson(provider, uri, traceId, Map.of());
  }

  public JsonObject getJson(String provider, URI uri, String traceId, Map<String, String> headers) throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(requestTimeout).GET();
    headers.forEach(builder::header);
    return send(provider, builder.build(), traceId);
  }

  public JsonObject postJson(String provider, URI uri, JsonObject body, String traceId) throws Exception {
    return send(provider, HttpRequest.newBuilder(uri).timeout(requestTimeout)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build(), traceId);
  }

  private JsonObject send(String provider, HttpRequest request, String traceId) throws Exception {
    Exception last = null;
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      awaitRateLimit(provider);
      long startedAt = System.nanoTime();
      try {
        HttpResponse<String> response = http.send(request,
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        LOG.debug("[travel-api] provider={} status={} durationMs={} traceId={}",
            provider, response.statusCode(), elapsedMs, traceId);
        if (response.statusCode() == 429 || response.statusCode() >= 500) {
          last = new ProviderHttpException(provider, response.statusCode(), safeErrorDetail(response.body()));
          if (attempt < maxAttempts) { backoff(attempt); continue; }
          throw last;
        }
        if (response.statusCode() / 100 != 2) {
          throw new ProviderHttpException(provider, response.statusCode(), safeErrorDetail(response.body()));
        }
        try {
          return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (Exception error) {
          throw new InvalidProviderResponseException(provider, error);
        }
      } catch (IOException error) {
        last = error instanceof ProviderHttpException || error instanceof InvalidProviderResponseException
            ? error : new ProviderNetworkException(provider, error);
        if (attempt < maxAttempts) { backoff(attempt); continue; }
        throw last;
      }
    }
    throw last == null ? new IllegalStateException("travel_provider_failed") : last;
  }

  private void awaitRateLimit(String provider) throws InterruptedException {
    long delay;
    synchronized (nextAllowedAt) {
      long now = System.currentTimeMillis();
      long next = nextAllowedAt.getOrDefault(provider, now);
      delay = Math.max(0, next - now);
      nextAllowedAt.put(provider, Math.max(now, next) + minimumIntervalMs);
    }
    if (delay > 0) Thread.sleep(delay);
  }

  private void backoff(int attempt) throws InterruptedException {
    Thread.sleep(Math.min(1_000L, 200L * attempt));
  }

  public static String encode(String value) {
    return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
  }

  private static String safeErrorDetail(String body) {
    if (body == null || body.isBlank()) return "empty_error_response";
    try {
      JsonObject root = JsonParser.parseString(body).getAsJsonObject();
      String code = text(root, "code");
      String message = text(root, "message");
      if (message.isBlank() && root.has("error") && root.get("error").isJsonObject()) {
        JsonObject error = root.getAsJsonObject("error");
        code = code.isBlank() ? text(error, "code") : code;
        message = text(error, "message");
      }
      String detail = !code.isBlank() && !message.isBlank() ? "code=" + code + ", message=" + message
          : !message.isBlank() ? "message=" + message : !code.isBlank() ? "code=" + code : "json_error";
      return detail.length() <= 240 ? detail : detail.substring(0, 240) + "...";
    } catch (RuntimeException ignored) {
      return "non_json_error_response";
    }
  }

  private static String text(JsonObject object, String name) {
    return object.has(name) && object.get(name).isJsonPrimitive() ? object.get(name).getAsString().trim() : "";
  }

  public static final class ProviderHttpException extends IOException {
    private final String provider;
    private final int statusCode;
    public ProviderHttpException(String provider, int statusCode, String detail) {
      super("travel_provider_http_error:" + provider + ":" + statusCode + ":" + detail);
      this.provider = provider;
      this.statusCode = statusCode;
    }
    public String provider() { return provider; }
    public int statusCode() { return statusCode; }
  }

  public static final class InvalidProviderResponseException extends IOException {
    public InvalidProviderResponseException(String provider, Throwable cause) {
      super("travel_provider_invalid_json:" + provider, cause);
    }
  }

  public static final class ProviderNetworkException extends IOException {
    public ProviderNetworkException(String provider, IOException cause) {
      super("travel_provider_network_error:" + provider + ":" + cause.getClass().getSimpleName(), cause);
    }
  }
}
