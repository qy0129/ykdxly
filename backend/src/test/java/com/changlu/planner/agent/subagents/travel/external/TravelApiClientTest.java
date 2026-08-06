package com.changlu.planner.agent.subagents.travel.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class TravelApiClientTest {
  private HttpServer server;

  @AfterEach void close() { if (server != null) server.stop(0); }

  @Test void parsesSuccessfulJson() throws Exception {
    URI uri = endpoint(exchange -> respond(exchange, 200, "{\"ok\":true}"));
    assertEquals(true, client(Duration.ofSeconds(1), 1).getJson("fake", uri, "trace")
        .get("ok").getAsBoolean());
  }

  @Test void retries429Once() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    URI uri = endpoint(exchange -> respond(exchange, calls.incrementAndGet() == 1 ? 429 : 200,
        "{\"ok\":true}"));
    client(Duration.ofSeconds(1), 2).getJson("fake", uri, "trace");
    assertEquals(2, calls.get());
  }

  @Test void sendsConfiguredHeaders() throws Exception {
    URI uri = endpoint(exchange -> {
      assertEquals("test-key", exchange.getRequestHeaders().getFirst("X-Test-Key"));
      respond(exchange, 200, "{\"ok\":true}");
    });
    client(Duration.ofSeconds(1), 1).getJson("fake", uri, "trace", Map.of("X-Test-Key", "test-key"));
  }

  @Test void rejectsInvalidJson() throws Exception {
    URI uri = endpoint(exchange -> respond(exchange, 200, "not-json"));
    assertThrows(TravelApiClient.InvalidProviderResponseException.class,
        () -> client(Duration.ofSeconds(1), 1).getJson("fake", uri, "trace"));
  }

  @Test void enforcesRequestTimeout() throws Exception {
    URI uri = endpoint(exchange -> {
      try { Thread.sleep(300); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
      respond(exchange, 200, "{}");
    });
    TravelApiClient.ProviderNetworkException error = assertThrows(
        TravelApiClient.ProviderNetworkException.class,
        () -> client(Duration.ofMillis(30), 1).getJson("fake", uri, "trace"));
    assertEquals("travel_provider_network_error:fake:HttpTimeoutException", error.getMessage());
  }

  private TravelApiClient client(Duration timeout, int attempts) {
    return new TravelApiClient(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
        timeout, attempts, 0);
  }

  private URI endpoint(com.sun.net.httpserver.HttpHandler handler) throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/test", handler);
    server.start();
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/test");
  }

  private void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
      throws java.io.IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    try (var output = exchange.getResponseBody()) { output.write(bytes); }
  }
}
