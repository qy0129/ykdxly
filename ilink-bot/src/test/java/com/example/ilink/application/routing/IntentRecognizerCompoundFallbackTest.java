package com.example.ilink.application.routing;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IntentRecognizerCompoundFallbackTest {

    @Test
    void coverageRetryRestoresWeatherWhenFirstAssignmentOnlyReturnsExpress() {
        List<String> responses = List.of(
                "{\"message_mode\":\"command\",\"requirements\":["
                        + "{\"id\":\"r1\",\"text\":\"查询今天杭州天气\",\"depends_on\":[]},"
                        + "{\"id\":\"r2\",\"text\":\"查询快递6466167676767941\",\"depends_on\":[]}],"
                        + "\"actions\":[{\"requirement_id\":\"r2\",\"action_text\":\"查询快递6466167676767941\","
                        + "\"intent\":\"express_query\"}]}",
                "{\"actions\":[{\"requirement_id\":\"r1\",\"intent\":\"weather\","
                        + "\"weather_location\":\"杭州\",\"weather_day\":\"today\"}]}"
        );
        AtomicInteger index = new AtomicInteger();
        IntentRecognizer recognizer = new IntentRecognizer(body -> responses.get(index.getAndIncrement()));

        IntentPlan plan = recognizer.recognize("user",
                "今天杭州的天气怎么样？顺便帮我查一下快递单号 6466167676767941 的快递",
                new IntentContext(false, false, false, false, false));

        assertEquals(List.of("weather", "express_query"),
                plan.actions().stream().map(action -> action.route().intent()).toList());
        assertEquals("杭州", plan.actions().getFirst().route().weatherLocation());
        assertEquals(2, index.get());
    }

    @Test
    void assignmentAuditRestoresRequirementMissedBySplitter() {
        List<String> responses = List.of(
                "{\"message_mode\":\"command\","
                        + "\"requirements\":[{\"id\":\"r1\",\"text\":\"帮我查一下快递\",\"depends_on\":[]}],"
                        + "\"missing_requirements\":[{\"id\":\"r2\",\"text\":\"今天杭州的天气怎么样\",\"depends_on\":[]}],"
                        + "\"actions\":[{\"requirement_id\":\"r1\",\"action_text\":\"帮我查一下快递\","
                        + "\"intent\":\"express_query\"}]}",
                "{\"actions\":[{\"requirement_id\":\"r2\",\"intent\":\"weather\",\"weather_location\":\"杭州\"}]}"
        );
        AtomicInteger index = new AtomicInteger();
        IntentRecognizer recognizer = new IntentRecognizer(body -> responses.get(index.getAndIncrement()));

        IntentPlan plan = recognizer.recognize("user", "今天杭州的天气怎么样？顺便帮我查一下快递",
                new IntentContext(false, false, false, false, false));

        assertEquals(List.of("weather", "express_query"),
                plan.actions().stream().map(action -> action.route().intent()).toList());
        assertEquals(2, index.get());
    }

    @Test
    void keepsAllFiveExplicitActionsInComplexTravelRequest() {
        List<String> responses = List.of(
                "{\"requirements\":["
                        + requirement("r1", "早上8点从阿里高桥云港园区打车去梦想小镇") + ","
                        + requirement("r2", "查询明天余杭天气") + ","
                        + requirement("r3", "中午点一份麦当劳外卖") + ","
                        + requirement("r4", "计算往返打车预估总费用") + ","
                        + "{\"id\":\"r5\",\"text\":\"下午4点提醒准时离开\",\"depends_on\":[]}]}",
                "{\"actions\":["
                        + action("r1", "taxi_trip", "\"travel_origin\":\"阿里高桥云港园区\",\"travel_destination\":\"梦想小镇\"") + ","
                        + action("r2", "weather", "\"weather_location\":\"余杭\",\"weather_day\":\"tomorrow\"") + ","
                        + action("r3", "food_order", "\"food_order_restaurants\":\"麦当劳\"") + ","
                        + action("r4", "calculator", "") + ","
                        + action("r5", "calendar_event", "\"calendar_action\":\"create\",\"calendar_time\":\"明天下午4点\"")
                        + "]}"
        );
        AtomicInteger index = new AtomicInteger();
        IntentRecognizer recognizer = new IntentRecognizer(body -> responses.get(index.getAndIncrement()));

        IntentPlan plan = recognizer.recognize("user",
                "帮我规划明天一整天出行安排：早上8点从阿里高桥云港园区出发，先打车去梦想小镇，"
                        + "路上查一下明天余杭的天气；中午在附近点一份麦当劳外卖；下午计算一下往返打车预估总费用，"
                        + "同时提醒我下午4点准时离开。",
                new IntentContext(false, false, false, false, false));

        assertEquals(List.of("taxi_trip", "weather", "food_order", "calculator", "calendar_event"),
                plan.actions().stream().map(action -> action.route().intent()).toList());
        assertEquals(5, plan.actions().stream().map(IntentAction::requirementId).distinct().count());
        assertEquals(2, index.get());
    }

    @Test
    void routesCompositeHalfDayTripThroughModelAndKeepsThreeActions() {
        AtomicInteger calls = new AtomicInteger();
        IntentRecognizer recognizer = new IntentRecognizer(body -> {
            calls.incrementAndGet();
            return """
                    {
                      "message_mode":"command",
                      "requirements":[
                        {"id":"r1","text":"明天上午从当前位置去西湖","depends_on":[]},
                        {"id":"r2","text":"中午查找西湖附近适合用餐的餐厅","depends_on":["r1"]},
                        {"id":"r3","text":"明天下午4点提醒返程","depends_on":[]}
                      ],
                      "actions":[
                        {"requirement_id":"r1","action_text":"明天上午从当前位置去西湖", "intent":"travel_plan",
                         "travel_origin":"当前位置","travel_destination":"西湖","travel_departure_time":"明天上午"},
                        {"requirement_id":"r2","depends_on":["r1"],
                         "action_text":"中午查找西湖附近适合用餐的餐厅","intent":"nearby_food",
                         "nearby_location":"西湖","meal_keyword":"午餐"},
                        {"requirement_id":"r3","action_text":"明天下午4点提醒返程","intent":"calendar_event",
                         "calendar_action":"create","calendar_title":"返程","calendar_time":"明天下午4点"}
                      ]
                    }
                    """;
        });

        IntentPlan plan = recognizer.recognize("user",
                "帮我规划明天杭州半日游：上午去西湖，中午找附近餐厅，下午 4 点提醒我返程",
                new IntentContext(false, false, false, false, false));

        assertEquals(List.of("travel_plan", "nearby_food", "calendar_event"),
                plan.actions().stream().map(action -> action.route().intent()).toList());
        assertEquals("西湖", plan.actions().get(1).route().nearbyLocation());
        assertEquals("午餐", plan.actions().get(1).route().mealKeyword());
        assertEquals("明天下午4点", plan.actions().get(2).route().calendarTime());
        assertEquals(1, calls.get());
    }

    @Test
    void cleansConditionalPrefixFromWeatherLocationAndKeepsBranches() {
        IntentRecognizer recognizer = new IntentRecognizer(body -> """
                {
                  "message_mode":"command",
                  "requirements":[
                    {"id":"r1","text":"查询明天杭州天气","depends_on":[]},
                    {"id":"r2","text":"如果适合出行，帮我打车去杭州东站","depends_on":["r1"]},
                    {"id":"r3","text":"如果下雨，提醒我带伞","depends_on":["r1"]}
                  ],
                  "actions":[
                    {"requirement_id":"r1","action_text":"查询明天杭州天气","intent":"weather",
                     "weather_location":"如果杭州","weather_day":"tomorrow"},
                    {"requirement_id":"r2","action_text":"如果适合出行，帮我打车去杭州东站","intent":"taxi_trip",
                     "travel_destination":"杭州东站","depends_on":["r1"]},
                    {"requirement_id":"r3","action_text":"如果下雨，提醒我带伞","intent":"calendar_event",
                     "calendar_title":"带伞","calendar_time":"明天早上","depends_on":["r1"]}
                  ]
                }
                """);

        IntentPlan plan = recognizer.recognize("user",
                "查询明天杭州天气;如果适合出行，帮我打车去杭州东站;如果下雨，提醒我带伞。",
                new IntentContext(false, false, false, false, false));

        assertEquals(List.of("weather", "taxi_trip", "calendar_event"),
                plan.actions().stream().map(action -> action.route().intent()).toList());
        assertEquals("杭州", plan.actions().getFirst().route().weatherLocation());
    }

    @Test
    void locallyRestoresConditionalBranchesWhenModelReturnsOnlyWeather() {
        IntentRecognizer recognizer = new IntentRecognizer(body -> """
                {
                  "message_mode":"command",
                  "requirements":[{"id":"r1","text":"查询明天杭州天气","depends_on":[]}],
                  "actions":[{"requirement_id":"r1","action_text":"查询明天杭州天气","intent":"weather",
                    "weather_location":"杭州","weather_day":"tomorrow"}]
                }
                """);
        String request = "查询明天杭州天气;如果适合出行，帮我打车去杭州东站;如果下雨，提醒我带伞。";

        IntentPlan plan = recognizer.recognize("user", request,
                new IntentContext(false, false, false, false, false));

        assertEquals(List.of("weather", "taxi_trip", "calendar_event"),
                plan.actions().stream().map(action -> action.route().intent()).toList());
        assertEquals("杭州东站", plan.actions().get(1).route().travelDestination());
        assertEquals("带伞", plan.actions().get(2).route().calendarTitle());
        assertEquals(List.of("r1"), plan.actions().get(1).dependsOn());
        assertEquals(List.of("r1"), plan.actions().get(2).dependsOn());
    }

    @Test
    void conditionalWeatherFallbackKeepsTomorrowAndRestoresBranches() {
        IntentRecognizer recognizer = new IntentRecognizer(body -> {
            throw new IllegalStateException("timeout");
        });
        String request = "查询明天杭州天气;如果适合出行，帮我打车去杭州东站;如果下雨，提醒我带伞。";

        IntentPlan plan = recognizer.recognize("user", request,
                new IntentContext(false, false, false, false, false));

        assertEquals(List.of("weather", "taxi_trip", "calendar_event"),
                plan.actions().stream().map(action -> action.route().intent()).toList());
        assertEquals("tomorrow", plan.actions().getFirst().route().weatherDay());
    }

    @Test
    void restoresWeatherBeforeConditionalTaxiWhenModelOmitsWeatherAndDependency() {
        IntentRecognizer recognizer = new IntentRecognizer(body -> """
                {
                  "message_mode":"command",
                  "requirements":[
                    {"id":"r2","text":"如果适合出行，帮我打车去杭州东站","depends_on":[]}
                  ],
                  "actions":[
                    {"requirement_id":"r2","action_text":"如果适合出行，帮我打车去杭州东站",
                     "intent":"taxi_trip","travel_destination":"杭州东站"}
                  ]
                }
                """);
        String request = "查询明天杭州天气;如果适合出行，帮我打车去杭州东站;如果下雨，提醒我带伞。";

        IntentPlan plan = recognizer.recognize("user", request,
                new IntentContext(false, false, false, false, false));

        assertEquals(List.of("weather", "taxi_trip", "calendar_event"),
                plan.actions().stream().map(action -> action.route().intent()).toList());
        assertEquals("杭州", plan.actions().getFirst().route().weatherLocation());
        assertEquals(List.of(plan.actions().getFirst().requirementId()), plan.actions().get(1).dependsOn());
        assertEquals(List.of(plan.actions().getFirst().requirementId()), plan.actions().get(2).dependsOn());
    }

    private static String requirement(String id, String text) {
        return "{\"id\":\"" + id + "\",\"text\":\"" + text + "\",\"depends_on\":[]}";
    }

    private static String action(String id, String intent, String fields) {
        return "{\"requirement_id\":\"" + id + "\",\"intent\":\"" + intent + "\""
                + (fields.isBlank() ? "" : "," + fields) + "}";
    }
}
