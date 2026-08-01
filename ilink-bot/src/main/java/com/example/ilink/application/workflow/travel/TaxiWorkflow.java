package com.example.ilink.application.workflow.travel;

import com.example.ilink.application.messaging.ReplyChannel;
import com.example.ilink.application.messaging.ReplySender;
import com.example.ilink.application.messaging.AgentContext;

import com.example.ilink.capabilities.travel.DidiMcpClient;
import com.example.ilink.capabilities.location.LocationService;
import com.example.ilink.capabilities.travel.AmapService;
import com.example.ilink.application.routing.IntentResult;
import com.example.ilink.application.conversation.UserSessionStore;
import com.example.ilink.platform.persistence.MySqlStore;
import com.google.gson.Gson;

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
    private final UserSessionStore sessions;
    private final LocationService locationService;
    private final Map<String, PendingTaxi> pendingTaxis = new ConcurrentHashMap<>();
    private final Set<String> loadedUsers = ConcurrentHashMap.newKeySet();
    private final MySqlStore database = MySqlStore.getInstance();
    private final Gson gson = new Gson();

    public TaxiWorkflow(DidiMcpClient didi, ReplySender replySender) {
        this(didi, replySender, null, null);
    }

    public TaxiWorkflow(DidiMcpClient didi, ReplySender replySender, UserSessionStore sessions) {
        this(didi, replySender, sessions, null);
    }

    public TaxiWorkflow(DidiMcpClient didi, ReplySender replySender,
                        UserSessionStore sessions, LocationService locationService) {
        this.didi = didi;
        this.replySender = replySender;
        this.sessions = sessions;
        this.locationService = locationService;
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
            case CITY -> isCityReply(value);
            case ORIGIN_SELECTION, DESTINATION_SELECTION -> value.matches("\\d+");
            case CONFIRM_ORDER -> value.matches("\\d+") || value.matches("(确认|下单|叫车).*" );
            case CONFIRM_CANCEL -> "确认取消".equals(value);
            case ACTIVE_ORDER -> false;
        };
    }

    public void clearPending(String userId) { clear(userId); }

    public void handle(AgentContext context, IntentResult route, String requestText) throws Exception {
        handle(context.replyChannel(), context.principalId(), route, requestText);
    }

    public void handle(ReplyChannel client, String userId, IntentResult route, String requestText) throws Exception {
        if (!didi.isConfigured()) {
            replySender.sendReply(client, userId, "尚未配置滴滴 MCP Key。请在启动环境设置 DIDI_MCP_KEY 后重试。");
            return;
        }
        boolean explicitOrigin = !trim(route.travelOrigin()).isBlank();
        AmapService.Place preciseOrigin = explicitOrigin ? null : currentPreciseLocation(userId);
        OriginSource originSource = explicitOrigin ? OriginSource.EXPLICIT
                : (preciseOrigin != null || !resolveOrigin(userId, route.travelOrigin()).isBlank()
                ? OriginSource.CURRENT_LOCATION : OriginSource.UNKNOWN);
        String origin = preciseOrigin == null
                ? resolveOrigin(userId, route.travelOrigin())
                : trim(preciseOrigin.name());
        String destination = trim(route.travelDestination());
        String originCity = trim(route.originCity());
        String destinationCity = trim(route.destinationCity());
        if (trim(route.travelOrigin()).isBlank() && !origin.isBlank() && sessions != null) {
            String currentLocation = origin;
            if (!currentLocation.isBlank()) {
                if (originCity.isBlank()) originCity = trim(sessions.getCurrentCity(userId));
                if (destinationCity.isBlank()) destinationCity = originCity;
                System.out.println("[Taxi] 使用会话当前位置作为起点：" + currentLocation);
            }
        }
        if (!origin.isBlank() && !destination.isBlank()) {
            start(client, userId, origin, destination, originCity, destinationCity, preciseOrigin, originSource);
            return;
        }
        handleOrderCommand(client, userId, requestText);
    }

    String resolveOrigin(String userId, String routeOrigin) {
        String explicit = trim(routeOrigin);
        if (!explicit.isBlank() || sessions == null) return explicit;
        return trim(sessions.getCurrentLocation(userId));
    }

    private AmapService.Place currentPreciseLocation(String userId) {
        if (locationService == null) return null;
        try {
            AmapService.Place place = locationService.currentPlace(userId);
            return place == null || trim(place.name()).isBlank()
                    || trim(place.longitude()).isBlank() || trim(place.latitude()).isBlank()
                    ? null : place;
        } catch (RuntimeException error) {
            System.err.println("[Taxi] 读取精确定位失败，退回地址搜索：" + error.getMessage());
            return null;
        }
    }

    public void handlePending(AgentContext context, String text) throws Exception {
        handlePending(context.replyChannel(), context.principalId(), text);
    }

    public void handlePending(ReplyChannel client, String userId, String text) throws Exception {
        try {
            PendingTaxi pending = pendingTaxi(userId);
            if (pending == null) return;
            String answer = trim(text);
            if ("取消".equals(answer)) {
                clear(userId);
                replySender.sendReply(client, userId, "已取消本次滴滴叫车流程。");
                return;
            }
            switch (pending.stage()) {
                case CITY -> start(client, userId, pending.origin(), pending.destination(), answer, answer,
                        pending.originSource() == OriginSource.CURRENT_LOCATION
                                ? currentPreciseLocation(userId) : null, pending.originSource());
                case ORIGIN_SELECTION -> selectOrigin(client, userId, pending, answer);
                case DESTINATION_SELECTION -> selectDestination(client, userId, pending, answer);
                case CONFIRM_ORDER -> createOrder(client, userId, pending, answer);
                case CONFIRM_CANCEL -> cancelOrder(client, userId, pending, answer);
                case ACTIVE_ORDER -> handleOrderCommand(client, userId, answer);
            }
        } catch (Exception error) {
            System.err.println("[Taxi] Pending workflow failed: " + error.getMessage());
            clear(userId);
            replySender.sendReply(client, userId,
                    "叫车服务暂时不可用，已结束本次流程。请稍后重新发送完整的出发地和目的地。");
        }
    }

    static boolean isCityReply(String value) {
        if (value == null || value.isBlank() || value.length() > 30) return false;
        if (value.matches("(?i)^(你好|您好|嗨|哈喽|hi|hello|在吗|谢谢|再见|你是谁|帮助|help)[！!。,.， ]*$")) {
            return false;
        }
        return !value.matches(".*(天气|快递|物流|待办|新闻|路线|导航|日历|提醒|计划|查询|搜索|画图|图片|文件|外卖).*" );
    }

    private void start(ReplyChannel client, String userId, String origin, String destination,
                       String originCity, String destinationCity) throws Exception {
        start(client, userId, origin, destination, originCity, destinationCity, null, OriginSource.EXPLICIT);
    }

    private void start(ReplyChannel client, String userId, String origin, String destination,
                       String originCity, String destinationCity, AmapService.Place preciseOrigin,
                       OriginSource originSource) throws Exception {
        String fromCity = city(originCity);
        String toCity = city(destinationCity);
        if (fromCity.isBlank() && toCity.isBlank()) {
            save(userId, PendingTaxi.city(origin, destination, originSource));
            replySender.sendReply(client, userId, "请补充出发城市，例如“杭州市”。");
            return;
        }
        if (fromCity.isBlank()) fromCity = toCity;
        if (toCity.isBlank()) toCity = fromCity;
        List<DidiMcpClient.Place> originCandidates = preciseOrigin == null
                ? didi.textSearch(origin, fromCity)
                : List.of(new DidiMcpClient.Place(
                        preciseOrigin.name(), "", preciseOrigin.name(), fromCity,
                        preciseOrigin.longitude(), preciseOrigin.latitude()));
        if (originCandidates.isEmpty()) {
            save(userId, PendingTaxi.city(origin, destination, originSource));
            replySender.sendReply(client, userId, "没有找到出发地“" + origin + "”，请补充更完整的地点或城市。");
            return;
        }
        resolveDestination(client, userId, origin, destination, fromCity, toCity, originCandidates.getFirst());
    }

    private void selectOrigin(ReplyChannel client, String userId, PendingTaxi pending, String text) throws Exception {
        int choice = choice(text, pending.candidates().size());
        if (choice < 0) { replySender.sendReply(client, userId, "请回复出发地序号，或回复“取消”。"); return; }
        resolveDestination(client, userId, pending.origin(), pending.destination(), pending.originCity(),
                pending.destinationCity(), pending.candidates().get(choice));
    }

    private void resolveDestination(ReplyChannel client, String userId, String origin, String destination,
                                    String originCity, String destinationCity, DidiMcpClient.Place from) throws Exception {
        List<DidiMcpClient.Place> candidates = didi.textSearch(destination, destinationCity);
        if (candidates.isEmpty()) {
            clear(userId);
            replySender.sendReply(client, userId, "没有找到目的地“" + destination + "”，请重新发起叫车并补充更完整的地点。");
            return;
        }
        // 地图搜索结果已按相关度排序。新流程自动采用首项，把地址和车型合并成一次确认。
        estimate(client, userId, from, candidates.getFirst());
    }

    private void selectDestination(ReplyChannel client, String userId, PendingTaxi pending, String text) throws Exception {
        int choice = choice(text, pending.candidates().size());
        if (choice < 0) { replySender.sendReply(client, userId, "请回复目的地序号，或回复“取消”。"); return; }
        estimate(client, userId, pending.from(), pending.candidates().get(choice));
    }

    private void estimate(ReplyChannel client, String userId, DidiMcpClient.Place from, DidiMcpClient.Place to) throws Exception {
        DidiMcpClient.Estimate estimate = didi.estimate(from, to);
        if (estimate.traceId().isBlank() || estimate.items().isEmpty()) {
            clear(userId);
            replySender.sendReply(client, userId, "当前没有可用车型，请稍后再试。");
            return;
        }
        save(userId, PendingTaxi.confirm(from, to, estimate.traceId(), estimate.items()));
        StringBuilder reply = new StringBuilder("滴滴").append(didi.isSandbox() ? " Sandbox 模拟" : "")
                .append("价格预估\n已自动匹配路线：\n")
                .append(from.label()).append(" -> ").append(to.label()).append('\n');
        for (int index = 0; index < estimate.items().size(); index++) {
            DidiMcpClient.EstimateItem item = estimate.items().get(index);
            reply.append(index + 1).append(". ").append(item.productName()).append("：")
                    .append(item.priceText()).append('\n');
        }
        reply.append("请回复一个序号确认地址和车型并生成下单链接，例如“1”；地址不对请回复“取消”后重新说明。");
        replySender.sendReply(client, userId, reply.toString());
    }

    private void createOrder(ReplyChannel client, String userId, PendingTaxi pending, String text) throws Exception {
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

    private void handleOrderCommand(ReplyChannel client, String userId, String text) throws Exception {
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

    private void cancelOrder(ReplyChannel client, String userId, PendingTaxi pending, String text) throws Exception {
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
            if (value != null && !value.isBlank()) try {
                TaxiState state = gson.fromJson(value, TaxiState.class);
                if (isUsable(state)) pendingTaxis.put(userId, state.pending());
                else database.deleteUserState(userId, PENDING_KEY);
            } catch (RuntimeException error) { database.deleteUserState(userId, PENDING_KEY); }
        }
        return pendingTaxis.get(userId);
    }
    private boolean isUsable(TaxiState state) {
        if (state == null || state.pending() == null || state.pending().stage() == null
                || state.expiresAtMillis() <= System.currentTimeMillis()) return false;
        PendingTaxi pending = state.pending();
        return switch (pending.stage()) {
            case CITY -> !trim(pending.destination()).isBlank();
            case ORIGIN_SELECTION -> pending.candidates() != null && !pending.candidates().isEmpty();
            case DESTINATION_SELECTION -> pending.from() != null && pending.candidates() != null && !pending.candidates().isEmpty();
            case CONFIRM_ORDER -> pending.from() != null && pending.to() != null && !trim(pending.traceId()).isBlank()
                    && pending.items() != null && !pending.items().isEmpty();
            case CONFIRM_CANCEL, ACTIVE_ORDER -> pending.from() != null && pending.to() != null;
        };
    }
    private void clear(String userId) { loadedUsers.add(userId); pendingTaxis.remove(userId); database.deleteUserState(userId, PENDING_KEY); }

    private enum Stage { CITY, ORIGIN_SELECTION, DESTINATION_SELECTION, CONFIRM_ORDER, CONFIRM_CANCEL, ACTIVE_ORDER }
    private enum OriginSource { EXPLICIT, CURRENT_LOCATION, UNKNOWN }
    private record PendingTaxi(Stage stage, String origin, String destination, String originCity, String destinationCity,
                               List<DidiMcpClient.Place> candidates, DidiMcpClient.Place from, DidiMcpClient.Place to,
                               String traceId, List<DidiMcpClient.EstimateItem> items, String orderId,
                               OriginSource originSource) {
        private PendingTaxi { originSource = originSource == null ? OriginSource.EXPLICIT : originSource; }
        static PendingTaxi city(String origin, String destination, OriginSource originSource) { return new PendingTaxi(Stage.CITY, origin, destination, "", "", List.of(), null, null, "", List.of(), "", originSource); }
        static PendingTaxi originChoices(String origin, String destination, String originCity, String destinationCity, List<DidiMcpClient.Place> values) { return new PendingTaxi(Stage.ORIGIN_SELECTION, origin, destination, originCity, destinationCity, List.copyOf(values), null, null, "", List.of(), "", OriginSource.EXPLICIT); }
        static PendingTaxi destinationChoices(String origin, String destination, String originCity, String destinationCity, DidiMcpClient.Place from, List<DidiMcpClient.Place> values) { return new PendingTaxi(Stage.DESTINATION_SELECTION, origin, destination, originCity, destinationCity, List.copyOf(values), from, null, "", List.of(), "", OriginSource.EXPLICIT); }
        static PendingTaxi confirm(DidiMcpClient.Place from, DidiMcpClient.Place to, String traceId, List<DidiMcpClient.EstimateItem> items) { return new PendingTaxi(Stage.CONFIRM_ORDER, "", "", "", "", List.of(), from, to, traceId, List.copyOf(items), "", OriginSource.EXPLICIT); }
        static PendingTaxi active(String orderId, DidiMcpClient.Place from, DidiMcpClient.Place to) { return new PendingTaxi(Stage.ACTIVE_ORDER, "", "", "", "", List.of(), from, to, "", List.of(), orderId, OriginSource.EXPLICIT); }
        PendingTaxi withStage(Stage next) { return new PendingTaxi(next, origin, destination, originCity, destinationCity, candidates, from, to, traceId, items, orderId, originSource); }
        PendingTaxi withOrderId(String value) { return new PendingTaxi(stage, origin, destination, originCity, destinationCity, candidates, from, to, traceId, items, value, originSource); }
    }
    private record TaxiState(PendingTaxi pending, long expiresAtMillis) { }
}
