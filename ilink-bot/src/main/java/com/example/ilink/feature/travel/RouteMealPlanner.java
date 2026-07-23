package com.example.ilink.feature.travel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;

/**
 * 为驾车路线筛选真正顺路的餐厅。
 *
 * <p>本类先在每一段路线的多个采样点查询 POI，再通过带餐厅途经点的该段路线与原路线耗时差
 * 计算绕路时间。它不负责回复消息或解析用户意图。</p>
 */
public final class RouteMealPlanner {

    private static final int MAX_CANDIDATES_TO_ROUTE = 4;
    private static final int MAX_DETOUR_SECONDS = 15 * 60;
    private static final int MAX_POI_SEARCHES = 6;

    private final AmapService amapService;

    /** 注入高德服务，复用地点、POI 和驾车路线能力。 */
    public RouteMealPlanner(AmapService amapService) {
        this.amapService = amapService;
    }

    /**
     * 搜索并排序沿途餐厅。
     * 最多对四家 POI 计算真实绕路时间，控制高德接口调用数量和回复等待时间。
     */
    public List<RouteRestaurant> recommend(AmapService.Place from, AmapService.Place to,
                                           AmapService.Route baseRoute, String mealRequest) {
        return recommend(List.of(from, to), List.of(baseRoute), mealRequest);
    }

    /**
     * 为包含途经点的完整行程推荐餐厅。
     * 地点数量必须比路段数量多一个，每家餐厅只插入其所在的原始路段计算绕路时间。
     */
    public List<RouteRestaurant> recommend(List<AmapService.Place> itinerary,
                                           List<AmapService.Route> legRoutes,
                                           String mealRequest) {
        if (itinerary.size() != legRoutes.size() + 1) return List.of();
        List<RestaurantCandidate> candidates = findCandidates(legRoutes, mealRequest);
        List<RouteRestaurant> recommendations = new ArrayList<>();
        int routedCount = 0;
        for (RestaurantCandidate candidate : candidates) {
            if (routedCount++ >= MAX_CANDIDATES_TO_ROUTE) break;
            try {
                AmapService.Restaurant restaurant = candidate.restaurant();
                AmapService.Place restaurantPlace = new AmapService.Place(
                        restaurant.name(), restaurant.longitude(), restaurant.latitude());
                int legIndex = candidate.legIndex();
                AmapService.Route routeViaRestaurant = amapService.drivingVia(
                        itinerary.get(legIndex), restaurantPlace, itinerary.get(legIndex + 1));
                if (routeViaRestaurant == null) continue;
                int detourSeconds = Math.max(0,
                        routeViaRestaurant.durationSeconds() - legRoutes.get(legIndex).durationSeconds());
                if (detourSeconds <= MAX_DETOUR_SECONDS) {
                    recommendations.add(new RouteRestaurant(restaurant, detourSeconds, legIndex));
                }
            } catch (Exception error) {
                System.err.println("[顺路餐厅] 绕路时间计算失败: " + error.getMessage());
            }
        }
        recommendations.sort(Comparator.comparingInt(RouteRestaurant::detourSeconds));
        return recommendations.stream().limit(3).toList();
    }

    /**
     * 在路线前、中、后段分别检索餐厅，再按轮询顺序合并候选。
     * 这样有限的绕路计算名额不会全部被路线起段的餐厅占用。
     */
    private List<RestaurantCandidate> findCandidates(List<AmapService.Route> routes, String mealRequest) {
        List<List<RestaurantCandidate>> candidatesBySegment = new ArrayList<>();
        String keywordQuery = String.join("|", keywordsFor(mealRequest));
        int searchCount = 0;
        int maxSampleCount = routes.stream().mapToInt(route -> route.sampledLocations().size()).max().orElse(0);
        for (int sampleIndex = 0; sampleIndex < maxSampleCount && searchCount < MAX_POI_SEARCHES; sampleIndex++) {
            for (int legIndex = 0; legIndex < routes.size() && searchCount < MAX_POI_SEARCHES; legIndex++) {
                if (sampleIndex >= routes.get(legIndex).sampledLocations().size()) continue;
                String location = routes.get(legIndex).sampledLocations().get(sampleIndex);
                String[] point = location.split(",");
                if (point.length != 2) continue;
                AmapService.Place samplePoint = new AmapService.Place("路线沿途", point[0], point[1]);
                List<RestaurantCandidate> segmentCandidates = new ArrayList<>();
                Set<String> segmentLocations = new HashSet<>();
                searchCount++;
                try {
                    for (AmapService.Restaurant restaurant :
                            amapService.nearbyRestaurants(samplePoint, keywordQuery)) {
                        if (segmentLocations.add(restaurant.location())) {
                            segmentCandidates.add(new RestaurantCandidate(restaurant, legIndex));
                        }
                    }
                } catch (Exception error) {
                    System.err.println("[顺路餐厅] POI 搜索失败: " + error.getMessage());
                }
                if (!segmentCandidates.isEmpty()) candidatesBySegment.add(segmentCandidates);
            }
        }
        List<RestaurantCandidate> candidates = new ArrayList<>();
        Set<String> usedLocations = new HashSet<>();
        for (int position = 0; candidates.size() < MAX_CANDIDATES_TO_ROUTE; position++) {
            boolean found = false;
            for (List<RestaurantCandidate> segment : candidatesBySegment) {
                if (position >= segment.size()) continue;
                RestaurantCandidate candidate = segment.get(position);
                if (usedLocations.add(candidate.restaurant().location())) candidates.add(candidate);
                found = true;
                if (candidates.size() >= MAX_CANDIDATES_TO_ROUTE) break;
            }
            if (!found) break;
        }
        return candidates;
    }

    /** 将“清淡”等抽象偏好转换为高德能够识别的真实餐饮搜索词。 */
    private List<String> keywordsFor(String mealRequest) {
        String request = mealRequest == null ? "" : mealRequest.trim().toLowerCase(Locale.ROOT);
        if (request.contains("清淡") || request.contains("清谈") || request.contains("清爽")) {
            return List.of("轻食", "粥", "蒸菜");
        }
        if (request.contains("素") || request.contains("不吃肉")) return List.of("素食", "轻食", "蔬食");
        if (request.contains("面")) return List.of("面馆", "汤面", "馄饨");
        if (request.contains("咖啡")) return List.of("咖啡", "咖啡馆");
        return request.isBlank() ? List.of("轻食", "粥", "蒸菜") : List.of(request);
    }

    /** 带有真实绕路耗时的餐厅推荐结果。 */
    public record RouteRestaurant(AmapService.Restaurant restaurant, int detourSeconds, int legIndex) {
    }

    /** 餐厅候选及它位于完整行程中的路段序号。 */
    private record RestaurantCandidate(AmapService.Restaurant restaurant, int legIndex) {
    }
}
