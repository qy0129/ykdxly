package com.example.ilink.app;

import com.example.ilink.feature.calendar.CalendarService;
import com.example.ilink.feature.travel.AmapService;
import com.example.ilink.feature.travel.RouteMealPlanner;
import com.example.ilink.routing.IntentResult;
import com.example.ilink.storage.MySqlStore;
import com.example.ilink.tools.planning.DateTimeParser;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 负责多段出行路线、地图输出、顺路餐饮和出发提醒的衔接。 */
public final class TravelWorkflow {

    private final AmapService amapService;
    private final RouteMealPlanner routeMealPlanner;
    private final CalendarService calendarService;
    private final ReplySender replySender;
    private final Map<String, PendingTravel> pendingTravels = new ConcurrentHashMap<>();
    private final Set<String> loadedPendingUsers = ConcurrentHashMap.newKeySet();
    private final MySqlStore database = MySqlStore.getInstance();
    private final Gson gson = new Gson();
    private static final String PENDING_TRAVEL_KEY = "pending_travel";
    private static final long PENDING_TTL_MILLIS = 24L * 60 * 60 * 1000;

    /** 注入路线、日历和回复服务。 */
    public TravelWorkflow(AmapService amapService, CalendarService calendarService, ReplySender replySender) {
        this.amapService = amapService;
        this.routeMealPlanner = new RouteMealPlanner(amapService);
        this.calendarService = calendarService;
        this.replySender = replySender;
    }

    /** 判断用户是否正在确认起点、途经点或终点。 */
    public boolean hasPendingLocation(String userId) {
        return pendingTravel(userId) != null;
    }

    /** 把模型提取的起点、途经点和终点组成有序地点链并开始逐个确认。 */
    public void handle(ILinkClient client, String userId, IntentResult route) throws Exception {
        String origin = route.travelOrigin().trim();
        String destination = route.travelDestination().trim();
        if (origin.isBlank() || destination.isBlank()) {
            replySender.sendReply(client, userId,
                    "请告诉我完整的起点和最终终点，例如“从高桥云港园区先去西湖，再到杭州西站”。");
            return;
        }

        List<String> locationNames = new ArrayList<>();
        locationNames.add(origin);
        if (route.travelStops() != null) {
            for (String stop : route.travelStops()) {
                if (stop != null && !stop.isBlank()) locationNames.add(stop.trim());
            }
        }
        locationNames.add(destination);

        if (!amapService.isConfigured()) {
            replySender.sendReply(client, userId, "我已识别行程：“" + String.join(" → ", locationNames)
                    + "”。当前还没有配置高德地图 Key，暂时不能生成路线和导航链接。");
            return;
        }
        try {
            PendingTravel travel = new PendingTravel(locationNames, List.of(), 0,
                    route.travelDepartureTime(), Math.max(0, route.timeBudgetMinutes()),
                    route.mealKeyword().trim(), route.originCity(), route.destinationCity(), List.of());
            askForCurrentLocation(client, userId, travel);
        } catch (Exception error) {
            clearPendingTravel(userId);
            replySender.sendReply(client, userId,
                    "地点服务暂时不可用。请稍后再试，或补充更完整的城市、园区或车站名称。");
            System.err.println("[出行规划] 地点解析失败: " + error.getMessage());
        }
    }

    /** 处理用户对当前地点候选项的序号选择。 */
    public void handleLocationSelection(ILinkClient client, String userId, String text) throws Exception {
        PendingTravel pending = pendingTravel(userId);
        if (pending == null) return;
        if ("取消".equals(text.trim())) {
            clearPendingTravel(userId);
            replySender.sendReply(client, userId, "已取消本次出行规划。");
            return;
        }
        try {
            int choice = Integer.parseInt(text.trim());
            if (choice < 1 || choice > pending.candidates().size()) throw new NumberFormatException();
            continueWithLocation(client, userId, pending, pending.candidates().get(choice - 1));
        } catch (NumberFormatException error) {
            replySender.sendReply(client, userId, "请回复地点序号，或回复“取消”。");
        } catch (Exception error) {
            clearPendingTravel(userId);
            replySender.sendReply(client, userId, "地点确认失败，请重新发送完整的出行需求。");
            System.err.println("[出行规划] 地点确认失败: " + error.getMessage());
        }
    }

    public boolean acceptsPendingReply(String text) {
        String value = text == null ? "" : text.trim();
        return "取消".equals(value) || value.matches("\\d+");
    }

    public void clearPending(String userId) {
        clearPendingTravel(userId);
    }

    /** 搜索当前待确认地点；唯一结果自动确认，多条结果必须让用户选择。 */
    private void askForCurrentLocation(ILinkClient client, String userId, PendingTravel travel) throws Exception {
        String locationName = travel.currentLocationName();
        List<AmapService.Place> candidates = locationCandidates(locationName, travel.cityForCurrentLocation());
        if (candidates.isEmpty()) {
            clearPendingTravel(userId);
            replySender.sendReply(client, userId,
                    "没有找到“" + locationName + "”，请补充城市或更完整的地点名称后重新发送。");
            return;
        }
        if (candidates.size() == 1) {
            continueWithLocation(client, userId, travel, candidates.get(0));
            return;
        }

        PendingTravel waiting = travel.withCandidates(candidates);
        savePendingTravel(userId, waiting);
        StringBuilder prompt = new StringBuilder("找到多个可能的").append(travel.currentLocationRole())
                .append("“").append(locationName).append("”，请回复序号确认：");
        for (int index = 0; index < candidates.size(); index++) {
            prompt.append("\n").append(index + 1).append(". ").append(candidates.get(index).name());
        }
        prompt.append("\n回复“取消”可结束本次出行规划。");
        try {
            byte[] image = amapService.candidateStaticMap(candidates);
            if (image != null) client.sendImage(userId, image, "location-candidates.png", "地点候选地图");
        } catch (Exception mapError) {
            System.err.println("[出行规划] 候选地点地图发送失败: " + mapError.getMessage());
        }
        replySender.sendReply(client, userId, prompt.toString());
    }

    /** 保存一个已确认地点；全部地点确认后开始生成多段路线。 */
    private void continueWithLocation(ILinkClient client, String userId, PendingTravel travel,
                                      AmapService.Place selected) throws Exception {
        PendingTravel next = travel.withConfirmedLocation(selected);
        if (!next.isComplete()) {
            askForCurrentLocation(client, userId, next);
            return;
        }
        clearPendingTravel(userId);
        createTravelReply(client, userId, next.confirmedLocations(), next);
    }

    /** 优先使用 POI 文本搜索；无 POI 时才使用地址地理编码兜底。 */
    private List<AmapService.Place> locationCandidates(String locationName, String city) throws Exception {
        List<AmapService.Place> candidates = new ArrayList<>(amapService.searchPlaceCandidates(locationName, city));
        if (candidates.isEmpty() && city != null && !city.isBlank()) {
            candidates.addAll(amapService.searchPlaceCandidates(locationName));
        }
        if (candidates.isEmpty()) {
            AmapService.Place fallback = amapService.geocode(locationName, city);
            if (fallback == null && city != null && !city.isBlank()) fallback = amapService.geocode(locationName);
            if (fallback != null) candidates.add(fallback);
        }
        return candidates;
    }

    /** 为地点链的每两个相邻地点生成一段路线，并汇总全程结果。 */
    private void createTravelReply(ILinkClient client, String userId,
                                   List<AmapService.Place> itinerary, PendingTravel pending) throws Exception {
        try {
            List<AmapService.Route> legRoutes = new ArrayList<>();
            int totalDistanceMeters = 0;
            int totalDurationSeconds = 0;
            for (int index = 0; index < itinerary.size() - 1; index++) {
                AmapService.Route legRoute = amapService.driving(itinerary.get(index), itinerary.get(index + 1));
                if (legRoute == null) {
                    replySender.sendReply(client, userId, "暂时无法规划第 " + (index + 1) + " 段路线，请稍后再试。");
                    return;
                }
                legRoutes.add(legRoute);
                totalDistanceMeters += legRoute.distanceMeters();
                totalDurationSeconds += legRoute.durationSeconds();
            }

            StringBuilder reply = new StringBuilder();
            if (legRoutes.size() > 1) {
                reply.append("已规划 ").append(legRoutes.size()).append(" 段行程：");
            } else {
                reply.append("路线规划：");
            }
            for (int index = 0; index < legRoutes.size(); index++) {
                AmapService.Place from = itinerary.get(index);
                AmapService.Place to = itinerary.get(index + 1);
                AmapService.Route legRoute = legRoutes.get(index);
                reply.append("\n\n第 ").append(index + 1).append(" 段：")
                        .append(from.name()).append(" → ").append(to.name())
                        .append("\n驾车约 ").append(formatDuration(legRoute.durationSeconds()))
                        .append("，约 ").append(formatDistance(legRoute.distanceMeters()));
            }
            if (legRoutes.size() > 1) {
                reply.append("\n\n全程行驶约 ").append(formatDuration(totalDurationSeconds))
                        .append("，约 ").append(formatDistance(totalDistanceMeters))
                        .append("，不包含途经点停留时间。");
            }
            String navigationUrl = amapService.navigationUrl(itinerary);
            reply.append("\n\n全程导航（高德地图）：\n")
                    .append(navigationUrl);

            appendMealRecommendations(reply, itinerary, legRoutes, pending.mealKeyword());
            appendTimeBudget(reply, totalDurationSeconds, pending.timeBudgetMinutes(), pending.mealKeyword());
            appendCalendar(client, userId, reply, itinerary, pending.departureText(), navigationUrl);
            sendRouteMap(client, userId, itinerary);
            replySender.sendReply(client, userId, reply.toString());
        } catch (Exception error) {
            replySender.sendReply(client, userId,
                    "路线服务暂时不可用。请稍后再试，或补充更完整的起点、途经点和终点。");
            System.err.println("[出行规划] 路线解析失败: " + error.getMessage());
        }
    }

    /** 搜索整条地点链沿途的餐厅，并标明推荐餐厅所属的具体路段。 */
    private void appendMealRecommendations(StringBuilder reply, List<AmapService.Place> itinerary,
                                           List<AmapService.Route> legRoutes, String meal) {
        if (meal.isBlank()) return;
        try {
            List<RouteMealPlanner.RouteRestaurant> restaurants =
                    routeMealPlanner.recommend(itinerary, legRoutes, meal);
            if (restaurants.isEmpty()) {
                reply.append("\n\n沿途暂未找到绕路 15 分钟内的").append(meal).append("餐厅。");
                return;
            }
            reply.append("\n\n顺路").append(meal).append("餐推荐：");
            for (int index = 0; index < restaurants.size(); index++) {
                RouteMealPlanner.RouteRestaurant recommendation = restaurants.get(index);
                AmapService.Restaurant restaurant = recommendation.restaurant();
                int legIndex = recommendation.legIndex();
                reply.append("\n").append(index + 1).append(". ").append(restaurant.name())
                        .append("（绕路约").append(formatDuration(recommendation.detourSeconds())).append("）")
                        .append("\n   所在路段：").append(itinerary.get(legIndex).name())
                        .append(" → ").append(itinerary.get(legIndex + 1).name())
                        .append("\n   地址：")
                        .append(restaurant.address().isBlank() ? "地图服务未提供具体门牌" : restaurant.address())
                        .append("\n   顺路导航：")
                        .append(amapService.restaurantDetourUrl(itinerary, restaurant, legIndex));
            }
        } catch (Exception mealError) {
            System.err.println("[出行规划] 中途餐饮搜索失败: " + mealError.getMessage());
            reply.append("\n\n中途餐饮暂时无法查询，全程导航链接仍然可以正常使用。");
        }
    }

    /** 根据用户给出的总时间预算判断行驶和用餐时间是否够用。 */
    private void appendTimeBudget(StringBuilder reply, int totalDurationSeconds,
                                  int timeBudgetMinutes, String meal) {
        if (timeBudgetMinutes <= 0) return;
        int estimatedMinutes = (int) Math.ceil(totalDurationSeconds / 60.0) + (meal.isBlank() ? 0 : 20);
        reply.append("\n\n你可用 ").append(timeBudgetMinutes).append(" 分钟；行驶加")
                .append(meal.isBlank() ? "缓冲" : "中途用餐").append("预计约 ")
                .append(estimatedMinutes).append(" 分钟，尚未计算途经点停留时间。")
                .append(estimatedMinutes <= timeBudgetMinutes
                        ? "时间基本够用，建议另外预留停留和拥堵时间。"
                        : "时间偏紧，建议减少停留或调整路线。");
    }

    /** 用户提供出发时间时，把完整地点链写入日历并设置提前提醒。 */
    private void appendCalendar(ILinkClient client, String userId, StringBuilder reply,
                                List<AmapService.Place> itinerary, String departureText,
                                String navigationUrl) throws Exception {
        if (departureText.isBlank()) {
            reply.append("\n告诉我出发时间，我可以顺便帮你加入日历并提前提醒。");
            return;
        }
        LocalDateTime departure = DateTimeParser.parse(departureText);
        if (departure == null) {
            reply.append("\n出发时间未识别，路线已生成，暂未加入日历。请补充例如“今天20:00”。");
            return;
        }
        String title = itinerary.stream().map(AmapService.Place::name)
                .reduce((left, right) -> left + "→" + right).orElse("出行");
        calendarService.create(userId, title, "出行", departure, "none", 15,
                "导航链接：" + navigationUrl);
        reply.append("\n我已把完整行程记入日历，并会在出发前 15 分钟提醒你。");
    }

    /** 发送标记全部行程地点的静态地图；图片失败不影响文字导航。 */
    private void sendRouteMap(ILinkClient client, String userId,
                              List<AmapService.Place> itinerary) {
        try {
            byte[] image = amapService.staticMap(itinerary);
            if (image != null) client.sendImage(userId, image, "route-map.png", "行程地点标记地图");
        } catch (Exception mapError) {
            System.err.println("[出行规划] 静态地图发送失败: " + mapError.getMessage());
        }
    }

    /** 把秒数转换成适合回复展示的小时和分钟。 */
    private String formatDuration(int seconds) {
        int minutes = Math.max(1, (int) Math.ceil(seconds / 60.0));
        return minutes >= 60 ? (minutes / 60) + "小时" + (minutes % 60) + "分钟" : minutes + "分钟";
    }

    /** 把米转换成米或公里。 */
    private String formatDistance(int meters) {
        return meters >= 1000 ? String.format("%.1f 公里", meters / 1000.0) : meters + "米";
    }

    private void savePendingTravel(String userId, PendingTravel travel) {
        loadedPendingUsers.add(userId);
        pendingTravels.put(userId, travel);
        database.saveUserState(userId, PENDING_TRAVEL_KEY,
                gson.toJson(new PendingTravelState(travel, System.currentTimeMillis() + PENDING_TTL_MILLIS)));
    }

    private PendingTravel pendingTravel(String userId) {
        if (userId == null || userId.isBlank()) return null;
        if (loadedPendingUsers.add(userId)) {
            String value = database.loadUserState(userId, PENDING_TRAVEL_KEY);
            if (!value.isBlank()) {
                try {
                    PendingTravelState state = gson.fromJson(value, PendingTravelState.class);
                    if (state != null && state.expiresAtMillis() > System.currentTimeMillis()) {
                        pendingTravels.put(userId, state.travel());
                    } else {
                        database.deleteUserState(userId, PENDING_TRAVEL_KEY);
                    }
                } catch (JsonSyntaxException error) {
                    database.deleteUserState(userId, PENDING_TRAVEL_KEY);
                }
            }
        }
        return pendingTravels.get(userId);
    }

    private void clearPendingTravel(String userId) {
        pendingTravels.remove(userId);
        database.deleteUserState(userId, PENDING_TRAVEL_KEY);
    }

    /** 一次多段出行在逐个地点确认期间保留的必要上下文。 */
    private record PendingTravel(List<String> locationNames,
                                 List<AmapService.Place> confirmedLocations,
                                 int currentLocationIndex,
                                 String departureText,
                                 int timeBudgetMinutes,
                                 String mealKeyword,
                                 String originCity,
                                 String destinationCity,
                                 List<AmapService.Place> candidates) {

        /** 复制列表，避免确认流程中的状态被外部修改。 */
        private PendingTravel {
            locationNames = List.copyOf(locationNames);
            confirmedLocations = List.copyOf(confirmedLocations);
            candidates = List.copyOf(candidates);
        }

        /** 返回当前需要查询和确认的地点名称。 */
        private String currentLocationName() {
            return locationNames.get(currentLocationIndex);
        }

        private String cityForCurrentLocation() {
            if (currentLocationIndex == 0) return originCity;
            if (currentLocationIndex == locationNames.size() - 1) return destinationCity;
            return "";
        }

        /** 根据地点在行程中的位置生成起点、途经点或终点说明。 */
        private String currentLocationRole() {
            if (currentLocationIndex == 0) return "起点";
            if (currentLocationIndex == locationNames.size() - 1) return "终点";
            return "第 " + currentLocationIndex + " 个途经点";
        }

        /** 保存当前地点候选项，等待用户回复序号。 */
        private PendingTravel withCandidates(List<AmapService.Place> selectedCandidates) {
            return new PendingTravel(locationNames, confirmedLocations, currentLocationIndex,
                    departureText, timeBudgetMinutes, mealKeyword,
                    originCity, destinationCity, selectedCandidates);
        }

        /** 保存一个确认地点，并把待确认位置推进到下一个地点。 */
        private PendingTravel withConfirmedLocation(AmapService.Place selected) {
            List<AmapService.Place> locations = new ArrayList<>(confirmedLocations);
            locations.add(selected);
            return new PendingTravel(locationNames, locations, currentLocationIndex + 1,
                    departureText, timeBudgetMinutes, mealKeyword,
                    originCity, destinationCity, List.of());
        }

        /** 判断地点链中的所有地点是否已经确认完成。 */
        private boolean isComplete() {
            return currentLocationIndex >= locationNames.size();
        }
    }

    private record PendingTravelState(PendingTravel travel, long expiresAtMillis) { }
}
