package com.example.ilink.application.workflow.food;

import com.example.ilink.application.messaging.ReplyChannel;
import com.example.ilink.application.messaging.ReplySender;

import com.example.ilink.application.conversation.UserSessionStore;
import com.example.ilink.application.messaging.AgentContext;
import com.example.ilink.capabilities.food.FoodOrderService;
import com.example.ilink.capabilities.travel.AmapService;
import com.example.ilink.application.routing.IntentResult;
import com.example.ilink.capabilities.location.LocationService;
import com.example.ilink.platform.persistence.MySqlStore;
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
    private final LocationService locationService;
    private final Map<String, PendingOrder> pendingOrders = new ConcurrentHashMap<>();
    private final Map<String, ReplyChannel> pendingChannels = new ConcurrentHashMap<>();
    private final Set<String> loadedUsers = ConcurrentHashMap.newKeySet();
    private final MySqlStore database = MySqlStore.getInstance();
    private final Gson gson = new Gson();

    public FoodOrderWorkflow(UserSessionStore sessions, AmapService amapService,
                             FoodOrderService foodOrderService, ReplySender replySender) {
        this(sessions, amapService, foodOrderService, replySender, null);
    }

    public FoodOrderWorkflow(UserSessionStore sessions, AmapService amapService,
                             FoodOrderService foodOrderService, ReplySender replySender,
                             LocationService locationService) {
        this.sessions = sessions;
        this.amapService = amapService;
        this.foodOrderService = foodOrderService;
        this.replySender = replySender;
        this.locationService = locationService;
    }

    public boolean hasPending(String userId) {
        ensureLoaded(userId);
        return pendingOrders.containsKey(userId);
    }

    public boolean acceptsPendingReply(String text) {
        String value = text == null ? "" : text.trim();
        if (com.example.ilink.application.routing.IntentPolicy.isExplicitFreshRequest(value)) return false;
        if ("取消".equals(value) || value.matches("\\d+")) return true;
        return !value.matches(".*(天气|快递|物流|待办|新闻|路线|导航|日历|提醒|查一下|搜索).*" );
    }

    public void clearPending(String userId) {
        clearPendingOrder(userId);
    }

    /** 由应用启动时注册，定位更新后自动恢复 Web 或微信原会话。 */
    public void enableLocationContinuation() {
        if (locationService != null) locationService.onLocationUpdated(this::onLocationUpdated);
    }

    public void handle(AgentContext context, IntentResult route) throws Exception {
        handle(context.replyChannel(), context.principalId(), route);
    }

    public void handle(ReplyChannel client, String userId, IntentResult route) throws Exception {
        pendingChannels.put(userId, client);
        String query = firstRestaurant(route.foodOrderRestaurants());
        String routeLocation = route.nearbyLocation() == null ? "" : route.nearbyLocation().trim();
        if (!routeLocation.isBlank()) sessions.setCurrentLocation(userId, routeLocation);
        String location = sessions.getCurrentLocation(userId);
        if (location == null || location.isBlank()) {
            savePendingOrder(userId, PendingOrder.waitingLocation(query));
            requestLocation(client, userId, query);
            return;
        }
        findLocationOrUseCurrentCoordinates(client, userId, query, location);
    }

    /** 定位授权完成后由消息分发器调用，自动恢复本次点餐或附近推荐。 */
    public void resumeAfterLocationUpdate(ReplyChannel client, String userId) throws Exception {
        PendingOrder pending = pendingOrder(userId);
        if (pending == null || pending.stage() != Stage.LOCATION_TEXT) return;
        String location = sessions.getCurrentLocation(userId);
        if (location == null || location.isBlank()) return;
        findLocationOrUseCurrentCoordinates(client, userId, pending.query(), location);
    }

    private void onLocationUpdated(String userId, String address) {
        ReplyChannel client = pendingChannels.get(userId);
        if (client == null) return;
        try {
            resumeAfterLocationUpdate(client, userId);
        } catch (Exception error) {
            System.err.println("[外卖] 定位完成后恢复推荐失败 user=" + userId + ": " + error.getMessage());
        }
    }

    public void handlePending(AgentContext context, String text) throws Exception {
        handlePending(context.replyChannel(), context.principalId(), text);
    }

    public void handlePending(ReplyChannel client, String userId, String text) throws Exception {
        pendingChannels.put(userId, client);
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

    private void findLocation(ReplyChannel client, String userId, String query, String location) throws Exception {
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

    /** GPS 已确认当前位置时直接使用其坐标，避免把同一园区再次拆成多个 POI 候选。 */
    private void findLocationOrUseCurrentCoordinates(ReplyChannel client, String userId,
                                                     String query, String location) throws Exception {
        AmapService.Place precisePlace = locationService == null ? null : locationService.currentPlace(userId);
        if (precisePlace != null && precisePlace.name().equals(location)) {
            findStores(client, userId, query, precisePlace);
            return;
        }
        findLocation(client, userId, query, location);
    }

    private void selectLocation(ReplyChannel client, String userId,
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

    private void findStores(ReplyChannel client, String userId, String query,
                            AmapService.Place center) throws Exception {
        String keyword = query == null || query.isBlank() ? "美食" : query;
        List<AmapService.Restaurant> stores = amapService.nearbyRestaurants(center, keyword);
        if (stores.isEmpty()) {
            clearPendingOrder(userId);
            replySender.sendReply(client, userId,
                    "附近没有找到可推荐的外卖餐厅，先告诉我想吃的类型或具体餐厅。\n\n"
                            + (query == null || query.isBlank() ? "" : foodOrderService.generateLinks(query)));
            return;
        }
        if (stores.size() == 1) {
            sendStoreLinks(client, userId, stores.getFirst());
            return;
        }
        savePendingOrder(userId, PendingOrder.stores(query, stores));
        replySender.sendReply(client, userId, storeChoices(query, stores));
    }

    private void selectStore(ReplyChannel client, String userId,
                             PendingOrder pending, String answer) throws Exception {
        int choice = choice(answer, pending.stores().size());
        if (choice < 0) {
            replySender.sendReply(client, userId, "请回复分店序号，或回复“取消”。");
            return;
        }
        sendStoreLinks(client, userId, pending.stores().get(choice));
    }

    private void sendStoreLinks(ReplyChannel client, String userId,
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
        String label = query == null || query.isBlank() ? "附近可点外卖的餐厅" : query;
        StringBuilder reply = new StringBuilder("找到以下“").append(label)
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

    private void requestLocation(ReplyChannel client, String userId, String query) throws Exception {
        if (locationService != null) {
            String url = locationService.createAuthorizationUrl(userId);
            if (!url.isBlank()) {
                replySender.sendReply(client, userId,
                        (query == null || query.isBlank()
                                ? "我可以按你当前位置推荐附近可点外卖的店。"
                                : "我可以按你当前位置查找“" + query + "”附近的门店。")
                                + "\n请打开下面的定位链接并允许定位，定位成功后我会自动继续：\n" + url);
                return;
            }
        }
        replySender.sendReply(client, userId,
                (query == null || query.isBlank()
                        ? "请告诉我你的当前位置或收货地址，我才能推荐附近的外卖店。"
                        : "请告诉我你现在的位置或收货地址，我才能确定具体分店。"));
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
        pendingChannels.remove(userId);
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
