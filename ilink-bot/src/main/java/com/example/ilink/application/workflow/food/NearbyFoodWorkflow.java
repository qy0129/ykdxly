package com.example.ilink.application.workflow.food;

import com.example.ilink.application.messaging.ReplyChannel;
import com.example.ilink.application.messaging.ReplySender;

import com.example.ilink.application.conversation.UserSessionStore;
import com.example.ilink.application.messaging.AgentContext;
import com.example.ilink.application.routing.IntentPolicy;
import com.example.ilink.application.routing.IntentResult;
import com.example.ilink.capabilities.planning.DateTimeParser;
import com.example.ilink.platform.persistence.MySqlStore;
import com.example.ilink.application.tooling.ToolContext;
import com.example.ilink.application.tooling.ToolManager;
import com.example.ilink.application.tooling.ToolResult;
import com.example.ilink.capabilities.food.NearbyFoodTool;
import com.google.gson.JsonObject;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 处理位置记忆和“附近有什么好吃的”这类连续对话。 */
public final class NearbyFoodWorkflow {

    private static final String PENDING_KEY = "pending_nearby_food";
    private static final long TTL_MILLIS = 24L * 60 * 60 * 1000;
    private static final int DEFAULT_RESULT_LIMIT = 5;
    private static final int MAX_RESULT_LIMIT = 10;
    private static final Pattern CURRENT_LOCATION = Pattern.compile(
            "(?:我现在|我目前|我此刻|我)?(?:在|位于)([^，,。；;！!？?]{1,40})");
    private static final Pattern NEARBY_LOCATION = Pattern.compile(
            "(?:^|[，,。；;！!？?])([^，,。；;！!？?]{1,30}?)(?:附近|周边)");
    private static final Pattern RESULT_LIMIT = Pattern.compile(
            "([0-9一二三四五六七八九十两]+)\\s*(?:家|个)(?:餐厅|饭店|店)?");

    private final UserSessionStore sessions;
    private final ToolManager toolManager;
    private final ReplySender replySender;
    private final Map<String, PendingNearbySearch> pendingLocations = new ConcurrentHashMap<>();
    private final Set<String> loadedUsers = ConcurrentHashMap.newKeySet();
    private final MySqlStore database = MySqlStore.getInstance();
    private final Gson gson = new Gson();

    public NearbyFoodWorkflow(UserSessionStore sessions, ToolManager toolManager, ReplySender replySender) {
        this.sessions = sessions;
        this.toolManager = toolManager;
        this.replySender = replySender;
    }

    public boolean hasPendingLocation(String userId) {
        ensureLoaded(userId);
        return pendingLocations.containsKey(userId);
    }

    public boolean acceptsPendingReply(String userId, String text) {
        String value = text == null ? "" : text.trim();
        if ("取消".equals(value)) return true;
        PendingNearbySearch pending = pendingSearch(userId);
        if (pending == null) return false;
        if (pending.stage() == Stage.LOCATION_SELECTION) return value.matches("\\d+");
        return !IntentPolicy.isNearbyDiningRequest(value);
    }

    public void clearPending(String userId) {
        clearPendingSearch(userId);
    }

    /** 根据模型给出的地点和动作决定仅记住位置，还是立即搜索附近餐饮。 */
    public void handle(AgentContext context, IntentResult route) throws Exception {
        handle(context.replyChannel(), context.principalId(), route.nearbyLocation(), route);
    }

    public void handle(ReplyChannel client, String userId, IntentResult route) throws Exception {
        handle(client, userId, route.nearbyLocation(), route);
    }

    public void handle(ReplyChannel client, String userId, String originalText,
                       IntentResult route) throws Exception {
        String textLocation = explicitLocation(originalText);
        String routeLocation = normalizeLocation(route.nearbyLocation());
        String rememberedLocation = sessions.getCurrentLocation(userId);
        String name = resolveLocation(originalText, route.nearbyLocation(), rememberedLocation);
        if (!name.isBlank()) {
            sessions.setCurrentLocation(userId, name);
            if ("remember".equals(route.nearbyAction())) {
                replySender.sendReply(client, userId, "已记住你现在在“" + name + "”。你可以问我“附近有什么好吃的”。");
                return;
            }
        }
        String currentLocation = name;
        if (currentLocation == null || currentLocation.isBlank()) {
            savePendingSearch(userId, PendingNearbySearch.waitingLocation(
                    route.mealKeyword(), requestedLimit(originalText)));
            replySender.sendReply(client, userId, "先告诉我你现在的位置，例如“我现在在杭州市阿里高桥园区”。");
            return;
        }
        int limit = requestedLimit(originalText);
        System.out.println("[附近美食] 本轮位置=" + (textLocation.isBlank() ? "（未提供）" : textLocation)
                + "，路由位置=" + (routeLocation.isBlank() ? "（未提供）" : routeLocation)
                + "，实际位置=" + currentLocation + "，推荐数量=" + limit);
        searchAndReply(client, userId, currentLocation, "", "", resolveMealKeyword(route.mealKeyword()), limit);
    }

    /** 同名地点时必须由用户确认序号，不能把附近店铺搜索到错误城市。 */
    public void handleLocationSelection(AgentContext context, String text) throws Exception {
        handleLocationSelection(context.replyChannel(), context.principalId(), text);
    }

    public void handleLocationSelection(ReplyChannel client, String userId, String text) throws Exception {
        if ("取消".equals(text.trim())) {
            clearPendingSearch(userId);
            replySender.sendReply(client, userId, "已取消附近餐饮搜索。");
            return;
        }
        PendingNearbySearch pending = pendingSearch(userId);
        if (pending == null) return;
        if (pending.stage() == Stage.LOCATION_TEXT) {
            String location = normalizeLocation(text);
            if (location.isBlank()) {
                replySender.sendReply(client, userId, "请提供具体位置，例如“杭州市武林广场”。");
                return;
            }
            clearPendingSearch(userId);
            sessions.setCurrentLocation(userId, location);
            searchAndReply(client, userId, location, "", "", pending.keyword(), pending.limit());
            return;
        }
        List<com.example.ilink.capabilities.travel.AmapService.Place> candidates = pending.candidates();
        try {
            int choice = Integer.parseInt(text.trim());
            if (choice < 1 || choice > candidates.size()) throw new NumberFormatException();
            com.example.ilink.capabilities.travel.AmapService.Place selected = candidates.get(choice - 1);
            clearPendingSearch(userId);
            sessions.setCurrentLocation(userId, selected.name());
            searchAndReply(client, userId, selected.name(), selected.longitude(), selected.latitude(),
                    pending.keyword(), pending.limit());
        } catch (NumberFormatException e) {
            replySender.sendReply(client, userId, "请回复地点序号，或回复“取消”。");
        }
    }

    /** 先连续发送地图图片，再发送对应的 Markdown 表格。 */
    private void searchAndReply(ReplyChannel client, String userId, String location,
                                String longitude, String latitude, String keyword, int limit) throws Exception {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("location", location);
        arguments.addProperty("longitude", longitude);
        arguments.addProperty("latitude", latitude);
        arguments.addProperty("keyword", keyword == null ? "" : keyword.trim());
        arguments.addProperty("result_limit", limit);
        ToolResult result = toolManager.execute(NearbyFoodTool.NAME, new ToolContext(userId), arguments);
        NearbyFoodTool.NearbyFoodOutput output = result.dataAs(NearbyFoodTool.NearbyFoodOutput.class);
        if (output != null && !output.candidateMapImages().isEmpty()) {
            for (int index = 0; index < output.candidateMapImages().size(); index++) {
                client.sendImage(userId, output.candidateMapImages().get(index),
                        "nearby-location-" + (index + 1) + ".png", "");
                replySender.markSent(userId);
            }
        } else if (output != null && output.mapImage() != null) {
            client.sendImage(userId, output.mapImage(), "nearby-food-map.png", "");
            replySender.markSent(userId);
        }
        if (output != null && !output.candidates().isEmpty()) {
            savePendingSearch(userId, PendingNearbySearch.locationChoices(output.candidates(), keyword, limit));
        }
        replySender.sendReply(client, userId, result.output());
    }

    static String normalizeLocation(String text) {
        String value = text == null ? "" : text.trim()
                .replaceFirst("^(我现在)?(在|位于)", "")
                .replaceFirst("^(我的)?(位置|地址|收货地址)(是|在)?", "")
                .replaceAll("^[，,：: ]+", "").trim();
        value = value.replaceFirst("(?:附近|周边|这里|这边)$", "").trim();
        return value.replaceAll("^[，,：: ]+|[，,：: ]+$", "").trim();
    }

    static String explicitLocation(String text) {
        if (text == null || text.isBlank()) return "";
        Matcher current = CURRENT_LOCATION.matcher(text);
        if (current.find()) return normalizeLocation(current.group(1));
        Matcher nearby = NEARBY_LOCATION.matcher(text);
        while (nearby.find()) {
            String location = normalizeNearbyLocation(nearby.group(1));
            if (!location.isBlank()) return location;
        }
        return "";
    }

    /** 去掉“查找”“中午找”等动作词，只保留“西湖附近”中的地点。 */
    private static String normalizeNearbyLocation(String value) {
        String location = normalizeLocation(value)
                .replaceFirst("^(?:查找|搜索|推荐|找)(?:一下)?", "")
                .replaceFirst("^(?:今天|明天|早上|上午|中午|下午|晚上|今晚)(?:要|想)?(?:查找|搜索|推荐|找)?", "")
                .trim();
        return location.matches("(?:查找|搜索|推荐|找)?") ? "" : location;
    }

    /** 模型偶尔会把完整餐饮需求填入关键词；地图检索只应接收菜品或餐厅类型。 */
    static String resolveMealKeyword(String text) {
        String keyword = text == null ? "" : text.trim()
                .replaceFirst("^.*?(?:附近|周边)", "")
                .replaceFirst("^(?:的|今天|明天|早上|上午|中午|下午|晚上|今晚)+", "")
                .replaceFirst("^(?:适合)?(?:用餐|吃饭)?(?:的)?", "")
                .replaceFirst("(?:适合)?(?:用餐|吃饭)?(?:的)?(?:餐厅|饭店|美食)$", "")
                .trim();
        return keyword;
    }

    static String resolveLocation(String originalText, String routeLocation, String rememberedLocation) {
        String explicit = explicitLocation(originalText);
        if (!explicit.isBlank()) return explicit;
        String routed = normalizeLocation(routeLocation);
        return routed.isBlank() ? normalizeLocation(rememberedLocation) : routed;
    }

    static int requestedLimit(String text) {
        if (text == null) return DEFAULT_RESULT_LIMIT;
        Matcher matcher = RESULT_LIMIT.matcher(text);
        if (!matcher.find()) return DEFAULT_RESULT_LIMIT;
        int value = DateTimeParser.parseChineseNumber(matcher.group(1));
        return value <= 0 ? DEFAULT_RESULT_LIMIT : Math.min(value, MAX_RESULT_LIMIT);
    }

    private enum Stage { LOCATION_TEXT, LOCATION_SELECTION }

    private record PendingNearbySearch(
            Stage stage,
            List<com.example.ilink.capabilities.travel.AmapService.Place> candidates,
            String keyword,
            int limit) {
        private PendingNearbySearch {
            candidates = List.copyOf(candidates);
            keyword = keyword == null ? "" : keyword.trim();
            limit = limit <= 0 ? DEFAULT_RESULT_LIMIT : Math.min(limit, MAX_RESULT_LIMIT);
        }

        static PendingNearbySearch waitingLocation(String keyword, int limit) {
            return new PendingNearbySearch(Stage.LOCATION_TEXT, List.of(), keyword, limit);
        }

        static PendingNearbySearch locationChoices(
                List<com.example.ilink.capabilities.travel.AmapService.Place> candidates,
                String keyword, int limit) {
            return new PendingNearbySearch(Stage.LOCATION_SELECTION, candidates, keyword, limit);
        }
    }

    private void savePendingSearch(String userId, PendingNearbySearch search) {
        loadedUsers.add(userId);
        pendingLocations.put(userId, search);
        database.saveUserState(userId, PENDING_KEY,
                gson.toJson(new PendingNearbyState(search, System.currentTimeMillis() + TTL_MILLIS)));
    }

    private PendingNearbySearch pendingSearch(String userId) {
        ensureLoaded(userId);
        return pendingLocations.get(userId);
    }

    private void clearPendingSearch(String userId) {
        loadedUsers.add(userId);
        pendingLocations.remove(userId);
        database.deleteUserState(userId, PENDING_KEY);
    }

    private void ensureLoaded(String userId) {
        if (userId == null || userId.isBlank() || !loadedUsers.add(userId)) return;
        String value = database.loadUserState(userId, PENDING_KEY);
        if (value.isBlank()) return;
        try {
            PendingNearbyState state = gson.fromJson(value, PendingNearbyState.class);
            if (state != null && state.expiresAtMillis() > System.currentTimeMillis()) {
                pendingLocations.put(userId, state.search());
            } else {
                database.deleteUserState(userId, PENDING_KEY);
            }
        } catch (JsonSyntaxException error) {
            database.deleteUserState(userId, PENDING_KEY);
        }
    }

    private record PendingNearbyState(PendingNearbySearch search, long expiresAtMillis) { }
}
