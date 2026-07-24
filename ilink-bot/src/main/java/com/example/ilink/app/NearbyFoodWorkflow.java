package com.example.ilink.app;

import com.example.ilink.conversation.UserSessionStore;
import com.example.ilink.routing.IntentResult;
import com.example.ilink.tools.core.ToolContext;
import com.example.ilink.tools.core.ToolManager;
import com.example.ilink.tools.core.ToolResult;
import com.example.ilink.tools.food.NearbyFoodTool;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 处理位置记忆和“附近有什么好吃的”这类连续对话。 */
public final class NearbyFoodWorkflow {

    private final UserSessionStore sessions;
    private final ToolManager toolManager;
    private final ReplySender replySender;
    private final Map<String, PendingNearbySearch> pendingLocations = new ConcurrentHashMap<>();

    public NearbyFoodWorkflow(UserSessionStore sessions, ToolManager toolManager, ReplySender replySender) {
        this.sessions = sessions;
        this.toolManager = toolManager;
        this.replySender = replySender;
    }

    public boolean hasPendingLocation(String userId) { return pendingLocations.containsKey(userId); }

    /** 根据模型给出的地点和动作决定仅记住位置，还是立即搜索附近餐饮。 */
    public void handle(ILinkClient client, String userId, IntentResult route) throws Exception {
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
            replySender.sendReply(client, userId, "先告诉我你现在的位置，例如“我现在在杭州市阿里高桥园区”。");
            return;
        }
        searchAndReply(client, userId, currentLocation, "", "", route.mealKeyword());
    }

    /** 同名地点时必须由用户确认序号，不能把附近店铺搜索到错误城市。 */
    public void handleLocationSelection(ILinkClient client, String userId, String text) throws Exception {
        if ("取消".equals(text.trim())) {
            pendingLocations.remove(userId);
            replySender.sendReply(client, userId, "已取消附近餐饮搜索。");
            return;
        }
        PendingNearbySearch pending = pendingLocations.get(userId);
        List<com.example.ilink.feature.travel.AmapService.Place> candidates = pending.candidates();
        try {
            int choice = Integer.parseInt(text.trim());
            if (choice < 1 || choice > candidates.size()) throw new NumberFormatException();
            com.example.ilink.feature.travel.AmapService.Place selected = candidates.get(choice - 1);
            pendingLocations.remove(userId);
            sessions.setCurrentLocation(userId, selected.name());
            searchAndReply(client, userId, selected.name(), selected.longitude(), selected.latitude(),
                    pending.keyword());
        } catch (NumberFormatException e) {
            replySender.sendReply(client, userId, "请回复地点序号，或回复“取消”。");
        }
    }

    /** 先连续发送地图图片，再发送对应的 Markdown 表格。 */
    private void searchAndReply(ILinkClient client, String userId, String location,
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
                replySender.markSent(userId);
            }
        } else if (output != null && output.mapImage() != null) {
            client.sendImage(userId, output.mapImage(), "nearby-food-map.png", "");
            replySender.markSent(userId);
        }
        if (output != null && !output.candidates().isEmpty()) {
            pendingLocations.put(userId, new PendingNearbySearch(output.candidates(), keyword));
        }
        replySender.sendReply(client, userId, result.output());
    }

    private record PendingNearbySearch(
            List<com.example.ilink.feature.travel.AmapService.Place> candidates,
            String keyword) {
        private PendingNearbySearch {
            candidates = List.copyOf(candidates);
            keyword = keyword == null ? "" : keyword.trim();
        }
    }
}
