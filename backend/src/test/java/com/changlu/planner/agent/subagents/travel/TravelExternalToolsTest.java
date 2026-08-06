package com.changlu.planner.agent.subagents.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.changlu.planner.agent.core.contract.AgentContext;
import com.changlu.planner.agent.core.contract.AgentResult;
import com.changlu.planner.agent.core.tool.ToolCall;
import com.changlu.planner.agent.core.tool.ToolHandler;
import com.changlu.planner.agent.core.tool.ToolRiskLevel;
import com.changlu.planner.agent.core.tool.ToolSideEffect;
import com.changlu.planner.agent.subagents.travel.services.*;
import com.changlu.planner.agent.subagents.travel.tools.*;
import com.changlu.planner.shared.database.Database;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class TravelExternalToolsTest {
  private HttpServer server;

  @AfterEach void closeServer() {
    if (server != null) server.stop(0);
  }

  @Test void toolDefinitionsAreReadOnlyAndRequireTravelPermission() {
    for (ToolHandler tool : tools()) {
      assertEquals(ToolRiskLevel.READ_ONLY, tool.definition().riskLevel());
      assertEquals(ToolSideEffect.NONE, tool.definition().sideEffect());
      assertTrue(tool.definition().requiredPermissions().contains("travel:read"));
    }
  }

  @Test void fakeServicesReturnStructuredContainers() throws Exception {
    AgentContext context = context(Set.of("travel:read"));
    JsonObject location = new LocationContextTool((request, trace) -> object("provider", "fake"))
        .execute(call(LocationContextTool.NAME), context).data();
    assertEquals("fake", location.getAsJsonObject("locationContext").get("provider").getAsString());
    assertTrue(new WeatherForecastTool((request, trace) -> arrayResult("weather"))
        .execute(call(WeatherForecastTool.NAME), context).data().get("weather").isJsonArray());
    assertTrue(new AttractionResearchTool((request, trace) -> arrayResult("attractions"))
        .execute(call(AttractionResearchTool.NAME), context).data().get("attractions").isJsonArray());
    assertTrue(new MapRoutingTool((request, trace) -> arrayResult("transitMatrix"))
        .execute(call(MapRoutingTool.NAME), context).data().get("transitMatrix").isJsonArray());
  }

  @Test void productionAdaptersDegradeClearlyWhenKeyMissing() {
    var http = new com.changlu.planner.agent.subagents.travel.external.TravelApiClient();
    assertThrows(IllegalStateException.class,
        () -> new AmapGeocodingService(http, "", "http://127.0.0.1").resolve(new JsonObject(), "trace"));
    assertThrows(IllegalStateException.class,
        () -> new AmapWeatherForecastService(http, "", "http://127.0.0.1")
            .forecast(new JsonObject(), "trace"));
  }

  @Test void amapWeatherNormalizesDailyForecastUsingDestinationAdcode() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v3/weather/weatherInfo", exchange -> {
      assertTrue(exchange.getRequestURI().getQuery().contains("city=370200"));
      assertTrue(exchange.getRequestURI().getQuery().contains("extensions=all"));
      byte[] body = ("{\"status\":\"1\",\"forecasts\":[{\"casts\":[{\"date\":\"2026-08-06\","
          + "\"dayweather\":\"晴\",\"nightweather\":\"多云\",\"daytemp_float\":\"31.5\","
          + "\"nighttemp_float\":\"25.0\"}]}]}").getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      try (var output = exchange.getResponseBody()) { output.write(body); }
    });
    server.start();
    var http = new com.changlu.planner.agent.subagents.travel.external.TravelApiClient(
        HttpClient.newHttpClient(), Duration.ofSeconds(1), 1, 0);
    JsonObject request = new JsonObject(); request.addProperty("destinationAdcode", "370200");
    JsonObject result = new AmapWeatherForecastService(http, "test-key",
        "http://127.0.0.1:" + server.getAddress().getPort() + "/v3").forecast(request, "trace");
    JsonObject weather = result.getAsJsonArray("weather").get(0).getAsJsonObject();
    assertEquals("amap", weather.get("provider").getAsString());
    assertEquals("晴 / 多云", weather.get("condition").getAsString());
    assertEquals(31.5, weather.get("tempHigh").getAsDouble());
    assertTrue(weather.get("humidityPercent").isJsonNull());
    assertTrue(weather.getAsJsonArray("warnings").isEmpty());
  }

  private java.util.List<ToolHandler> tools() {
    GeocodingService geo = (request, trace) -> new JsonObject();
    WeatherForecastService weather = (request, trace) -> arrayResult("weather");
    AttractionResearchService attractions = (request, trace) -> arrayResult("attractions");
    MapRoutingService routing = (request, trace) -> arrayResult("transitMatrix");
    return java.util.List.of(new LocationContextTool(geo), new WeatherForecastTool(weather),
        new AttractionResearchTool(attractions), new MapRoutingTool(routing));
  }
  private JsonObject arrayResult(String name) { JsonObject value = new JsonObject(); value.add(name, new JsonArray()); return value; }
  private JsonObject object(String name, String value) { JsonObject data = new JsonObject(); data.addProperty(name, value); return data; }
  private ToolCall call(String name) { return new ToolCall("call", null, name, new JsonObject()); }
  private AgentContext context(Set<String> permissions) { return new AgentContext(UUID.randomUUID(), UUID.randomUUID(), "trace",
      new Database.Context(UUID.randomUUID(), UUID.randomUUID()), "test", permissions, Instant.now().plusSeconds(5), new JsonObject()); }
}
