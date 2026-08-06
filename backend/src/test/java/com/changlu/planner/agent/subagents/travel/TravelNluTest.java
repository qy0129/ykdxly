package com.changlu.planner.agent.subagents.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.changlu.planner.agent.core.contract.SubagentRequest;
import com.google.gson.JsonObject;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

final class TravelNluTest {
  private final TravelPolicy policy = new TravelPolicy(Clock.fixed(
      Instant.parse("2026-08-05T02:00:00Z"), ZoneId.of("Asia/Shanghai")));

  @Test void parsesQingdaoTenDayBeachRequest() {
    JsonObject arguments = normalize("明天去青岛玩十天，预算10万，喜欢海边，不要太累");
    assertEquals("2026-08-06", arguments.get("startDate").getAsString());
    assertEquals("2026-08-15", arguments.get("endDate").getAsString());
    assertEquals(100000, arguments.getAsJsonObject("budget").get("amount").getAsInt());
    assertTrue(arguments.get("beachPreference").getAsBoolean());
    assertEquals("relaxed", arguments.get("pace").getAsString());
  }

  @Test void parsesParentsHotelRailAndChineseMoney() {
    JsonObject arguments = normalize("带父母去三亚五天，四星级酒店，高铁优先，预算一万");
    assertTrue(arguments.get("elderlyTravel").getAsBoolean());
    assertEquals(4, arguments.get("hotelStarRating").getAsInt());
    assertEquals("highSpeedRail", arguments.get("preferredTransport").getAsString());
    assertEquals(10000, arguments.getAsJsonObject("budget").get("amount").getAsInt());
  }

  @Test void parsesNextMondayAndAvoidEarlyMorning() {
    JsonObject arguments = normalize("下周一去北京三天，不要早起");
    assertEquals("2026-08-10", arguments.get("startDate").getAsString());
    assertEquals("2026-08-12", arguments.get("endDate").getAsString());
    assertTrue(arguments.get("avoidEarlyMorning").getAsBoolean());
  }

  @Test void parsesTwentyFiveThousand() {
    assertEquals(25000, normalize("明天去青岛三天，预算两万五").getAsJsonObject("budget").get("amount").getAsInt());
  }

  private JsonObject normalize(String message) {
    return policy.normalizeRequest(new SubagentRequest(message, new JsonObject(), List.of())).arguments();
  }
}
