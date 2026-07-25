package com.example.ilink.app;

import com.example.ilink.conversation.UserSessionStore;
import com.example.ilink.feature.food.FoodOrderService;
import com.example.ilink.feature.travel.AmapService;
import com.example.ilink.routing.IntentResult;
import com.example.ilink.storage.MySqlStore;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 处理点餐所需的位置补充、同名地点确认和具体分店选择。 */
public final class FoodOrderWorkflow {

    private static final String PENDING_KEY = "pending_food_order";
    private static final long TTL_MILLIS = 24L * 60 * 60 * 1000;

    private final UserSessionStore sessions;
    private final AmapService amapService;
    private final FoodOrderService foodOrderService;
    private final ReplySender replySender;
    private final Map<String, PendingOrder> pendingOrders = new ConcurrentHashMap<>();
    private final Set<String> loadedUsers = ConcurrentHashMap.newKeySet();
    private final MySqlStore database = MySqlStore.getInstance();
    private final Gson gson = new Gson();

    public FoodOrderWorkflow(UserSessionStore sessions, AmapService amapService,
                             FoodOrderService foodOrderService, ReplySender replySender) {
        this.sessions = sessions;
        this.amapService = amapService;
        this.foodOrderService = foodOrderService;
        this.replySender = replySender;
    }

    public boolean hasPending(String userId) {
        ensureLoaded(userId);
        return pendingOrders.containsKey(userId);
    }

    public boolean acceptsPendingReply(String text) {
        String value = text == null ? "" : text.trim();
        if ("取消".equals(value) || value.matches("\\d+")) return true;
        return !value.matches(".*(天气|快递|物流|待办|新闻|路线|导航|日历|提醒|查一下|搜索).*" );
    }

    public void clearPending(String userId) {
        clearPendingOrder(userId);
    }

    public void handle(ILinkClient client, String userId, IntentResult route) throws Exception {
        String query = firstRestaurant(route.foodOrderRestaurants());
        if (query.isBlank()) {
            replySender.sendReply(client, userId, "请告诉我你想点哪个餐厅，例如“帮我点外婆家”。");
            return;
        }

        String routeLocation = route.nearbyLocation() == null ? "" : route.nearbyLocation().trim();
        if (!routeLocation.isBlank()) sessions.setCurrentLocation(userId, routeLocation);
        String location = sessions.getCurrentLocation(userId);
        if (location == null || location.isBlank()) {
            savePendingOrder(userId, PendingOrder.waitingLocation(query));
            replySender.sendReply(client, userId, "请告诉我你现在的位置或收货地址，我才能确定具体分店。");
            return;
        }
        findLocation(client, userId, query, location);
    }

    public void handlePending(ILinkClient client, String userId, String text) throws Exception {
        PendingOrder pending = pendingOrder(userId);
        if (pending == null) return;
        String answer = text.trim();
        if ("取消".equals(answer)) {
            clearPendingOrder(userId);
            replySender.sendReply(client, userId, "已取消本次点餐查询。");
            return;
        }

        switch (pending.stage()) {
            case LOCATION_TEXT -> {
                String location = cleanLocation(answer);
                if (location.isBlank()) {
                    replySender.sendReply(client, userId, "请提供具体位置，例如“杭州市武林广场”。");
                    return;
                }
                sessions.setCurrentLocation(userId, location);
                findLocation(client, userId, pending.query(), location);
            }
            case LOCATION_SELECTION -> selectLocation(client, userId, pending, answer);
            case STORE_SELECTION -> selectStore(client, userId, pending, answer);
        }
    }

    private void findLocation(ILinkClient client, String userId, String query, String location) throws Exception {
        if (!amapService.isConfigured()) {
            clearPendingOrder(userId);
            replySender.sendReply(client, userId,
                    "当前没有配置高德 Key，暂时不能判断具体分店。\n\n" + foodOrderService.generateLinks(query));
            return;
        }
        List<AmapService.Place> locations = amapService.searchPlaceCandidates(location);
        if (locations.isEmpty()) {
            savePendingOrder(userId, PendingOrder.waitingLocation(query));
            replySender.sendReply(client, userId, "没有找到这个位置，请补充城市和更完整的地址。");
            return;
        }
        if (locations.size() > 1) {
            savePendingOrder(userId, PendingOrder.locations(query, locations));
            replySender.sendReply(client, userId, locationChoices(locations));
            return;
        }
        findStores(client, userId, query, locations.getFirst());
    }

    private void selectLocation(ILinkClient client, String userId,
                                PendingOrder pending, String answer) throws Exception {
        int choice = choice(answer, pending.locations().size());
        if (choice < 0) {
            replySender.sendReply(client, userId, "请回复地点序号，或回复“取消”。");
            return;
        }
        AmapService.Place selected = pending.locations().get(choice);
        sessions.setCurrentLocation(userId, selected.name());
        findStores(client, userId, pending.query(), selected);
    }

    private void findStores(ILinkClient client, String userId, String query,
                            AmapService.Place center) throws Exception {
        List<AmapService.Restaurant> stores = amapService.nearbyRestaurants(center, query);
        if (stores.isEmpty()) {
            clearPendingOrder(userId);
            replySender.sendReply(client, userId,
                    "附近没有找到“" + query + "”的具体分店，先给你平台搜索入口：\n\n"
                            + foodOrderService.generateLinks(query));
            return;
        }
        if (stores.size() == 1) {
            sendStoreLinks(client, userId, stores.getFirst());
            return;
        }
        savePendingOrder(userId, PendingOrder.stores(query, stores));
        replySender.sendReply(client, userId, storeChoices(query, stores));
    }

    private void selectStore(ILinkClient client, String userId,
                             PendingOrder pending, String answer) throws Exception {
        int choice = choice(answer, pending.stores().size());
        if (choice < 0) {
            replySender.sendReply(client, userId, "请回复分店序号，或回复“取消”。");
            return;
        }
        sendStoreLinks(client, userId, pending.stores().get(choice));
    }

    private void sendStoreLinks(ILinkClient client, String userId,
                                AmapService.Restaurant store) throws Exception {
        clearPendingOrder(userId);
        FoodOrderService.ResolvedStoreLinks links = foodOrderService.resolveStore(store);
        replySender.sendReply(client, userId, foodOrderService.formatStoreLinks(links));
    }

    private String locationChoices(List<AmapService.Place> locations) {
        StringBuilder reply = new StringBuilder("找到多个同名地点，请回复序号：\n");
        for (int index = 0; index < locations.size(); index++) {
            reply.append(index + 1).append(". ").append(locations.get(index).name()).append('\n');
        }
        return reply.append("回复“取消”可以结束查询。").toString();
    }

    private String storeChoices(String query, List<AmapService.Restaurant> stores) {
        StringBuilder reply = new StringBuilder("找到以下“").append(query)
                .append("”相关分店，请回复序号：\n");
        for (int index = 0; index < stores.size(); index++) {
            AmapService.Restaurant store = stores.get(index);
            reply.append(index + 1).append(". ").append(store.name()).append('\n')
                    .append("   ").append(store.address().isBlank() ? "地址以平台页面为准" : store.address())
                    .append('\n');
        }
        return reply.append("回复“取消”可以结束查询。").toString();
    }

    private int choice(String text, int size) {
        try {
            int value = Integer.parseInt(text) - 1;
            return value >= 0 && value < size ? value : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private String firstRestaurant(String restaurants) {
        if (restaurants == null) return "";
        return Arrays.stream(restaurants.split("[,，、]"))
                .map(String::trim).filter(value -> !value.isBlank()).findFirst().orElse("");
    }

    private String cleanLocation(String text) {
        return text.replaceFirst("^(我)?(现在)?(在|位于)|^(我的)?(位置|收货地址|地址)(是|在|为)", "")
                .replaceAll("^[：:，, ]+", "").trim();
    }

    private enum Stage { LOCATION_TEXT, LOCATION_SELECTION, STORE_SELECTION }

    private void savePendingOrder(String userId, PendingOrder order) {
        loadedUsers.add(userId);
        pendingOrders.put(userId, order);
        database.saveUserState(userId, PENDING_KEY,
                gson.toJson(new PendingOrderState(order, System.currentTimeMillis() + TTL_MILLIS)));
    }

    private PendingOrder pendingOrder(String userId) {
        ensureLoaded(userId);
        return pendingOrders.get(userId);
    }

    private void clearPendingOrder(String userId) {
        loadedUsers.add(userId);
        pendingOrders.remove(userId);
        database.deleteUserState(userId, PENDING_KEY);
    }

    private void ensureLoaded(String userId) {
        if (userId == null || userId.isBlank() || !loadedUsers.add(userId)) return;
        String value = database.loadUserState(userId, PENDING_KEY);
        if (value.isBlank()) return;
        try {
            PendingOrderState state = gson.fromJson(value, PendingOrderState.class);
            if (state != null && state.expiresAtMillis() > System.currentTimeMillis()) {
                pendingOrders.put(userId, state.order());
            } else {
                database.deleteUserState(userId, PENDING_KEY);
            }
        } catch (JsonSyntaxException error) {
            database.deleteUserState(userId, PENDING_KEY);
        }
    }

    private record PendingOrder(String query, Stage stage,
                                List<AmapService.Place> locations,
                                List<AmapService.Restaurant> stores) {
        static PendingOrder waitingLocation(String query) {
            return new PendingOrder(query, Stage.LOCATION_TEXT, List.of(), List.of());
        }

        static PendingOrder locations(String query, List<AmapService.Place> locations) {
            return new PendingOrder(query, Stage.LOCATION_SELECTION, List.copyOf(locations), List.of());
        }

        static PendingOrder stores(String query, List<AmapService.Restaurant> stores) {
            return new PendingOrder(query, Stage.STORE_SELECTION, List.of(), List.copyOf(stores));
        }
    }

    private record PendingOrderState(PendingOrder order, long expiresAtMillis) { }
}
