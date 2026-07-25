package com.example.ilink.app;

import com.example.ilink.feature.travel.DidiMcpClient;
import com.example.ilink.routing.IntentResult;
import com.example.ilink.storage.MySqlStore;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 滴滴叫车状态机；确认车型后仅生成 App 下单链接，不直接创建订单。 */
public final class TaxiWorkflow {
    private static final String PENDING_KEY = "pending_taxi";
    private static final long TTL_MILLIS = 24L * 60 * 60 * 1000;

    private final DidiMcpClient didi;
    private final ReplySender replySender;
    private final Map<String, PendingTaxi> pendingTaxis = new ConcurrentHashMap<>();
    private final Set<String> loadedUsers = ConcurrentHashMap.newKeySet();
    private final MySqlStore database = MySqlStore.getInstance();
    private final Gson gson = new Gson();

    public TaxiWorkflow(DidiMcpClient didi, ReplySender replySender) {
        this.didi = didi;
        this.replySender = replySender;
    }

    public boolean hasPending(String userId) {
        PendingTaxi pending = pendingTaxi(userId);
        return pending != null && pending.stage() != Stage.ACTIVE_ORDER;
    }

    public boolean acceptsPendingReply(String userId, String text) {
        String value = text == null ? "" : text.trim();
        if ("取消".equals(value)) return true;
        PendingTaxi pending = pendingTaxi(userId);
        if (pending == null) return false;
        return switch (pending.stage()) {
            case CITY -> !value.isBlank();
            case ORIGIN_SELECTION, DESTINATION_SELECTION -> value.matches("\\d+");
            case CONFIRM_ORDER -> value.matches("\\d+") || value.matches("(确认|下单|叫车).*" );
            case CONFIRM_CANCEL -> "确认取消".equals(value);
            case ACTIVE_ORDER -> false;
        };
    }

    public void clearPending(String userId) { clear(userId); }

    public void handle(ILinkClient client, String userId, IntentResult route, String requestText) throws Exception {
        if (!didi.isConfigured()) {
            replySender.sendReply(client, userId, "尚未配置滴滴 MCP Key。请在启动环境设置 DIDI_MCP_KEY 后重试。");
            return;
        }
        String origin = trim(route.travelOrigin());
        String destination = trim(route.travelDestination());
        if (!origin.isBlank() && !destination.isBlank()) {
            start(client, userId, origin, destination, route.originCity(), route.destinationCity());
            return;
        }
        handleOrderCommand(client, userId, requestText);
    }

    public void handlePending(ILinkClient client, String userId, String text) throws Exception {
        PendingTaxi pending = pendingTaxi(userId);
        if (pending == null) return;
        String answer = trim(text);
        if ("取消".equals(answer)) {
            clear(userId);
            replySender.sendReply(client, userId, "已取消本次滴滴叫车流程。");
            return;
        }
        switch (pending.stage()) {
            case CITY -> start(client, userId, pending.origin(), pending.destination(), answer, answer);
            case ORIGIN_SELECTION -> selectOrigin(client, userId, pending, answer);
            case DESTINATION_SELECTION -> selectDestination(client, userId, pending, answer);
            case CONFIRM_ORDER -> createOrder(client, userId, pending, answer);
            case CONFIRM_CANCEL -> cancelOrder(client, userId, pending, answer);
            case ACTIVE_ORDER -> handleOrderCommand(client, userId, answer);
        }
    }

    private void start(ILinkClient client, String userId, String origin, String destination,
                       String originCity, String destinationCity) throws Exception {
        String fromCity = city(originCity);
        String toCity = city(destinationCity);
        if (fromCity.isBlank() && toCity.isBlank()) {
            save(userId, PendingTaxi.city(origin, destination));
            replySender.sendReply(client, userId, "请补充出发城市，例如“杭州市”。");
            return;
        }
        if (fromCity.isBlank()) fromCity = toCity;
        if (toCity.isBlank()) toCity = fromCity;
        List<DidiMcpClient.Place> candidates = didi.textSearch(origin, fromCity);
        if (candidates.isEmpty()) {
            save(userId, PendingTaxi.city(origin, destination));
            replySender.sendReply(client, userId, "没有找到出发地“" + origin + "”，请补充更完整的地点或城市。");
            return;
        }
        if (candidates.size() == 1) {
            resolveDestination(client, userId, origin, destination, fromCity, toCity, candidates.getFirst());
            return;
        }
        save(userId, PendingTaxi.originChoices(origin, destination, fromCity, toCity, candidates));
        replySender.sendReply(client, userId, choices("出发地", candidates));
    }

    private void selectOrigin(ILinkClient client, String userId, PendingTaxi pending, String text) throws Exception {
        int choice = choice(text, pending.candidates().size());
        if (choice < 0) { replySender.sendReply(client, userId, "请回复出发地序号，或回复“取消”。"); return; }
        resolveDestination(client, userId, pending.origin(), pending.destination(), pending.originCity(),
                pending.destinationCity(), pending.candidates().get(choice));
    }

    private void resolveDestination(ILinkClient client, String userId, String origin, String destination,
                                    String originCity, String destinationCity, DidiMcpClient.Place from) throws Exception {
        List<DidiMcpClient.Place> candidates = didi.textSearch(destination, destinationCity);
        if (candidates.isEmpty()) {
            clear(userId);
            replySender.sendReply(client, userId, "没有找到目的地“" + destination + "”，请重新发起叫车并补充更完整的地点。");
            return;
        }
        if (candidates.size() == 1) { estimate(client, userId, from, candidates.getFirst()); return; }
        save(userId, PendingTaxi.destinationChoices(origin, destination, originCity, destinationCity, from, candidates));
        replySender.sendReply(client, userId, choices("目的地", candidates));
    }

    private void selectDestination(ILinkClient client, String userId, PendingTaxi pending, String text) throws Exception {
        int choice = choice(text, pending.candidates().size());
        if (choice < 0) { replySender.sendReply(client, userId, "请回复目的地序号，或回复“取消”。"); return; }
        estimate(client, userId, pending.from(), pending.candidates().get(choice));
    }

    private void estimate(ILinkClient client, String userId, DidiMcpClient.Place from, DidiMcpClient.Place to) throws Exception {
        DidiMcpClient.Estimate estimate = didi.estimate(from, to);
        if (estimate.traceId().isBlank() || estimate.items().isEmpty()) {
            clear(userId);
            replySender.sendReply(client, userId, "当前没有可用车型，请稍后再试。");
            return;
        }
        save(userId, PendingTaxi.confirm(from, to, estimate.traceId(), estimate.items()));
        StringBuilder reply = new StringBuilder("滴滴").append(didi.isSandbox() ? " Sandbox 模拟" : "")
                .append("价格预估\n")
                .append(from.displayName()).append(" -> ").append(to.displayName()).append('\n');
        for (int index = 0; index < estimate.items().size(); index++) {
            DidiMcpClient.EstimateItem item = estimate.items().get(index);
            reply.append(index + 1).append(". ").append(item.productName()).append("：")
                    .append(item.priceText()).append('\n');
        }
        reply.append("请回复车型序号生成滴滴 App 下单链接，例如“1”。");
        replySender.sendReply(client, userId, reply.toString());
    }

    private void createOrder(ILinkClient client, String userId, PendingTaxi pending, String text) throws Exception {
        int choice = confirmedChoice(text, pending.items().size());
        if (choice < 0) choice = choice(text, pending.items().size());
        if (choice < 0) { replySender.sendReply(client, userId, "请回复车型序号，例如“1”。"); return; }
        DidiMcpClient.EstimateItem item = pending.items().get(choice);
        DidiMcpClient.RideAppLinks links = didi.generateRideAppLink(
                pending.from(), pending.to(), item.productCategory());
        if (links.miniprogramLink().isBlank()) {
            clear(userId);
            replySender.sendReply(client, userId, "滴滴本次没有返回小程序下单链接，请稍后重新报价后再试。");
            return;
        }
        PendingTaxi active = PendingTaxi.active("", pending.from(), pending.to());
        save(userId, active);
        replySender.sendReply(client, userId, "滴滴 App 下单链接已生成"
                + "\n路线：" + pending.from().displayName() + " -> " + pending.to().displayName()
                + "\n车型：" + item.productName() + " · " + item.priceText()
                + formatLinks(links)
                + "\n请任选一个入口继续。在滴滴内确认下单并支付后，订单才会出现在历史订单中。");
    }

    private void handleOrderCommand(ILinkClient client, String userId, String text) throws Exception {
        PendingTaxi active = pendingTaxi(userId);
        if (active == null || active.stage() != Stage.ACTIVE_ORDER) {
            replySender.sendReply(client, userId, "请告诉我起点和终点，例如“从杭州西站打车到西湖”。");
            return;
        }
        if (text.contains("取消")) {
            if (active.orderId().isBlank()) {
                replySender.sendReply(client, userId, "请先在滴滴 App 完成下单，再发送“查询打车订单”同步订单状态后取消。");
                return;
            }
            save(userId, active.withStage(Stage.CONFIRM_CANCEL));
            replySender.sendReply(client, userId, "将取消当前滴滴订单。请回复“确认取消”。");
        } else if (text.contains("司机") || text.contains("位置")) {
            if (active.orderId().isBlank()) {
                replySender.sendReply(client, userId, "请先在滴滴 App 完成下单，再查询司机位置。");
                return;
            }
            DidiMcpClient.DriverLocation location = didi.driverLocation(active.orderId());
            String address = didi.reverseGeocode(location);
            replySender.sendReply(client, userId, "司机模拟位置：" + address);
        } else {
            DidiMcpClient.OrderStatus status = didi.queryOrder(active.orderId());
            if (!status.orderId().isBlank() && active.orderId().isBlank()) {
                active = active.withOrderId(status.orderId());
                save(userId, active);
            }
            String driver = status.driverName().isBlank() ? "暂未匹配司机" : status.driverName()
                    + " · " + status.carModel() + " · " + status.carPlate();
            String eta = status.eta().isBlank() ? "" : "，预计 " + status.eta() + " 分钟到达";
            replySender.sendReply(client, userId, "订单状态：" + status.statusText() + "\n司机：" + driver + eta);
        }
    }

    private void cancelOrder(ILinkClient client, String userId, PendingTaxi pending, String text) throws Exception {
        if (!"确认取消".equals(trim(text))) { replySender.sendReply(client, userId, "请回复“确认取消”，或回复“取消”保留订单。"); return; }
        boolean success = didi.cancelOrder(pending.orderId(), "用户取消");
        clear(userId);
        replySender.sendReply(client, userId, success ? "滴滴订单已取消。" : "订单取消未成功，请稍后查询订单状态。");
    }

    private String choices(String title, List<DidiMcpClient.Place> candidates) {
        StringBuilder reply = new StringBuilder();
        if (didi.isSandbox()) reply.append("滴滴 Sandbox 返回的是模拟地点，不能代表真实地址。\n");
        reply.append("找到多个").append(title).append("，请回复序号：\n");
        for (int index = 0; index < candidates.size(); index++) reply.append(index + 1).append(". ").append(candidates.get(index).label()).append('\n');
        return reply.append("回复“取消”可以结束叫车。 ").toString();
    }

    private String formatLinks(DidiMcpClient.RideAppLinks links) {
        return "\n\n滴滴小程序：" + links.miniprogramLink();
    }

    private int choice(String text, int size) {
        try { int value = Integer.parseInt(text) - 1; return value >= 0 && value < size ? value : -1; }
        catch (NumberFormatException ignored) { return -1; }
    }

    private int confirmedChoice(String text, int size) {
        String number = text.replaceFirst("^(确认|下单|叫车)\\s*", "").trim();
        return text.matches("^(确认|下单|叫车).*") ? choice(number, size) : -1;
    }

    private String city(String value) {
        String city = trim(value);
        return city.isBlank() || city.endsWith("市") ? city : city + "市";
    }
    private String trim(String value) { return value == null ? "" : value.trim(); }

    private void save(String userId, PendingTaxi pending) {
        loadedUsers.add(userId);
        pendingTaxis.put(userId, pending);
        database.saveUserState(userId, PENDING_KEY, gson.toJson(new TaxiState(pending, System.currentTimeMillis() + TTL_MILLIS)));
    }
    private PendingTaxi pendingTaxi(String userId) {
        if (userId == null || userId.isBlank()) return null;
        if (loadedUsers.add(userId)) {
            String value = database.loadUserState(userId, PENDING_KEY);
            if (!value.isBlank()) try {
                TaxiState state = gson.fromJson(value, TaxiState.class);
                if (state != null && state.expiresAtMillis() > System.currentTimeMillis()) pendingTaxis.put(userId, state.pending());
                else database.deleteUserState(userId, PENDING_KEY);
            } catch (JsonSyntaxException error) { database.deleteUserState(userId, PENDING_KEY); }
        }
        return pendingTaxis.get(userId);
    }
    private void clear(String userId) { loadedUsers.add(userId); pendingTaxis.remove(userId); database.deleteUserState(userId, PENDING_KEY); }

    private enum Stage { CITY, ORIGIN_SELECTION, DESTINATION_SELECTION, CONFIRM_ORDER, CONFIRM_CANCEL, ACTIVE_ORDER }
    private record PendingTaxi(Stage stage, String origin, String destination, String originCity, String destinationCity,
                               List<DidiMcpClient.Place> candidates, DidiMcpClient.Place from, DidiMcpClient.Place to,
                               String traceId, List<DidiMcpClient.EstimateItem> items, String orderId) {
        static PendingTaxi city(String origin, String destination) { return new PendingTaxi(Stage.CITY, origin, destination, "", "", List.of(), null, null, "", List.of(), ""); }
        static PendingTaxi originChoices(String origin, String destination, String originCity, String destinationCity, List<DidiMcpClient.Place> values) { return new PendingTaxi(Stage.ORIGIN_SELECTION, origin, destination, originCity, destinationCity, List.copyOf(values), null, null, "", List.of(), ""); }
        static PendingTaxi destinationChoices(String origin, String destination, String originCity, String destinationCity, DidiMcpClient.Place from, List<DidiMcpClient.Place> values) { return new PendingTaxi(Stage.DESTINATION_SELECTION, origin, destination, originCity, destinationCity, List.copyOf(values), from, null, "", List.of(), ""); }
        static PendingTaxi confirm(DidiMcpClient.Place from, DidiMcpClient.Place to, String traceId, List<DidiMcpClient.EstimateItem> items) { return new PendingTaxi(Stage.CONFIRM_ORDER, "", "", "", "", List.of(), from, to, traceId, List.copyOf(items), ""); }
        static PendingTaxi active(String orderId, DidiMcpClient.Place from, DidiMcpClient.Place to) { return new PendingTaxi(Stage.ACTIVE_ORDER, "", "", "", "", List.of(), from, to, "", List.of(), orderId); }
        PendingTaxi withStage(Stage next) { return new PendingTaxi(next, origin, destination, originCity, destinationCity, candidates, from, to, traceId, items, orderId); }
        PendingTaxi withOrderId(String value) { return new PendingTaxi(stage, origin, destination, originCity, destinationCity, candidates, from, to, traceId, items, value); }
    }
    private record TaxiState(PendingTaxi pending, long expiresAtMillis) { }
}
