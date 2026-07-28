package com.example.ilink.application.workflow.food;

import com.example.ilink.application.messaging.ReplyChannel;
import com.example.ilink.application.messaging.ReplySender;

import com.example.ilink.application.conversation.UserSessionStore;
import com.example.ilink.application.messaging.AgentContext;
import com.example.ilink.application.routing.IntentResult;
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

/** 处理位置记忆和“附近有什么好吃的”这类连续对话。 */
public final class NearbyFoodWorkflow {

    private static final String PENDING_KEY = "pending_nearby_food";
    private static final long TTL_MILLIS = 24L * 60 * 60 * 1000;

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
        return pending != null && (pending.stage() == Stage.LOCATION_TEXT || value.matches("\\d+"));
    }

    public void clearPending(String userId) {
        clearPendingSearch(userId);
    }

    /** 根据模型给出的地点和动作决定仅记住位置，还是立即搜索附近餐饮。 */
    public void handle(AgentContext context, IntentResult route) throws Exception {
        handle(context.replyChannel(), context.principalId(), route);
    }

    public void handle(ReplyChannel client, String userId, IntentResult route) throws Exception {
        String name = route.nearbyLocation().trim();
        if (!name.isBlank()) {
            sessions.setCurrentLocation(userId, name);
            if ("remember".equals(route.nearbyAction())) {
                replySender.sendReply(client, userId, "已记住你现在在“" + name + "”。你可以问我“附近有什么好吃的”。");
                return;
            }
        }
        String currentLocation = sessions.getCurrentLocation(userId);
        if (currentLocation == null || currentLocation.isBlank()) {
            savePendingSearch(userId, PendingNearbySearch.waitingLocation(route.mealKeyword()));
            replySender.sendReply(client, userId, "先告诉我你现在的位置，例如“我现在在杭州市阿里高桥园区”。");
            return;
        }
        searchAndReply(client, userId, currentLocation, "", "", route.mealKeyword());
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
            String location = cleanLocation(text);
            if (location.isBlank()) {
                replySender.sendReply(client, userId, "请提供具体位置，例如“杭州市武林广场”。");
                return;
            }
            clearPendingSearch(userId);
            sessions.setCurrentLocation(userId, location);
            searchAndReply(client, userId, location, "", "", pending.keyword());
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
                    pending.keyword());
        } catch (NumberFormatException e) {
            replySender.sendReply(client, userId, "请回复地点序号，或回复“取消”。");
        }
    }

    /** 先连续发送地图图片，再发送对应的 Markdown 表格。 */
    private void searchAndReply(ReplyChannel client, String userId, String location,
                                String longitude, String latitude, String keyword) throws Exception {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("location", location);
        arguments.addProperty("longitude", longitude);
        arguments.addProperty("latitude", latitude);
        arguments.addProperty("keyword", keyword == null ? "" : keyword.trim());
        ToolResult result = toolManager.execute(NearbyFoodTool.NAME, new ToolContext(userId), arguments);
        NearbyFoodTool.NearbyFoodOutput output = result.dataAs(NearbyFoodTool.NearbyFoodOutput.class);
        if (output != null && !output.candidateMapImages().isEmpty()) {
            for (int index = 0; index < output.candidateMapImages().size(); index++) {
                client.sendImage(userId, output.candidateMapImages().get(index),
                        "nearby-location-" + (index + 1) + ".png", "");
                replySender.markSent();
            }
        } else if (output != null && output.mapImage() != null) {
            client.sendImage(userId, output.mapImage(), "nearby-food-map.png", "");
            replySender.markSent();
        }
        if (output != null && !output.candidates().isEmpty()) {
            savePendingSearch(userId, PendingNearbySearch.locationChoices(output.candidates(), keyword));
        }
        replySender.sendReply(client, userId, result.output());
    }

    private String cleanLocation(String text) {
        return text == null ? "" : text.trim()
                .replaceFirst("^(我现在)?(在|位于)", "")
                .replaceFirst("^(我的)?(位置|地址|收货地址)(是|在)?", "")
                .replaceAll("^[，,：: ]+", "").trim();
    }

    private enum Stage { LOCATION_TEXT, LOCATION_SELECTION }

    private record PendingNearbySearch(
            Stage stage,
            List<com.example.ilink.capabilities.travel.AmapService.Place> candidates,
            String keyword) {
        private PendingNearbySearch {
            candidates = List.copyOf(candidates);
            keyword = keyword == null ? "" : keyword.trim();
        }

        static PendingNearbySearch waitingLocation(String keyword) {
            return new PendingNearbySearch(Stage.LOCATION_TEXT, List.of(), keyword);
        }

        static PendingNearbySearch locationChoices(
                List<com.example.ilink.capabilities.travel.AmapService.Place> candidates, String keyword) {
            return new PendingNearbySearch(Stage.LOCATION_SELECTION, candidates, keyword);
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
