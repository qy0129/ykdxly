package com.example.ilink.capabilities.food;

import com.example.ilink.capabilities.food.LinkShortener;
import com.example.ilink.capabilities.food.FoodPreferenceMapper;
import com.example.ilink.capabilities.travel.AmapService;
import com.example.ilink.application.tooling.Tool;
import com.example.ilink.application.tooling.ToolArguments;
import com.example.ilink.application.tooling.ToolContext;
import com.example.ilink.application.tooling.ToolDefinition;
import com.example.ilink.application.tooling.ToolResult;
import com.google.gson.JsonObject;

import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 从高德 POI 数据检索用户当前位置附近的餐饮，并返回手机端店铺位置链接。 */
public final class NearbyFoodTool implements Tool {

    public static final String NAME = "nearby_food_search";
    private final AmapService amapService;
    private final FoodPreferenceMapper preferenceMapper;
    private final ToolDefinition definition;

    public NearbyFoodTool(AmapService amapService) {
        this(amapService, new FoodPreferenceMapper(HttpClient.newHttpClient()));
    }

    public NearbyFoodTool(AmapService amapService, FoodPreferenceMapper preferenceMapper) {
        this.amapService = amapService;
        this.preferenceMapper = preferenceMapper;
        JsonObject properties = new JsonObject();
        properties.add("location", ToolDefinition.stringProperty("用户当前所在位置，例如杭州市阿里高桥园区"));
        properties.add("longitude", ToolDefinition.stringProperty("用户确认地点的经度；首次搜索时传空字符串"));
        properties.add("latitude", ToolDefinition.stringProperty("用户确认地点的纬度；首次搜索时传空字符串"));
        properties.add("keyword", ToolDefinition.stringProperty("用户想吃的餐厅、品牌或餐品，例如麦当劳、面馆、咖啡"));
        definition = new ToolDefinition(NAME, "附近美食", "搜索用户当前位置附近的餐饮店铺，并生成手机端高德店铺链接。",
                ToolDefinition.objectParameters(properties, "location"), true);
    }

    @Override
    public ToolDefinition definition() { return definition; }

    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) throws Exception {
        if (!amapService.isConfigured()) return ToolResult.failure("附近店铺搜索需要先配置 amap.api.key。");
        String location = ToolArguments.requireString(arguments, "location");
        String longitude = ToolArguments.string(arguments, "longitude", "");
        String latitude = ToolArguments.string(arguments, "latitude", "");
        String requestedKeyword = ToolArguments.string(arguments, "keyword", "").trim();
        if (requestedKeyword.isBlank()) requestedKeyword = "美食";
        List<String> searchKeywords = preferenceMapper.mapKeywords(requestedKeyword);
        AmapService.Place center;
        if (longitude.isBlank() || latitude.isBlank()) {
            List<AmapService.Place> candidates = amapService.searchPlaceCandidates(location);
            if (candidates.isEmpty()) return ToolResult.failure("没有找到地点“" + location + "”，请补充城市或更完整的名称。");
            if (candidates.size() > 1) {
                return ToolResult.success(formatLocationChoices(candidates),
                        new NearbyFoodOutput(candidates, List.of(), null,
                                candidateMapImages(candidates)));
            }
            center = candidates.get(0);
        } else {
            center = new AmapService.Place(location, longitude, latitude);
        }
        Map<String, AmapService.Restaurant> restaurantMatches = new LinkedHashMap<>();
        Exception lastError = null;
        int successfulSearches = 0;
        searchLoop:
        for (String searchKeyword : searchKeywords) {
            try {
                List<AmapService.Restaurant> matches = amapService.nearbyRestaurants(center, searchKeyword);
                successfulSearches++;
                for (AmapService.Restaurant restaurant : matches) {
                    restaurantMatches.putIfAbsent(
                            restaurant.name() + '|' + restaurant.location(), restaurant);
                    if (restaurantMatches.size() >= 8) break searchLoop;
                }
            } catch (Exception error) {
                lastError = error;
                System.err.println("[附近美食] 搜索“" + searchKeyword + "”失败: " + error.getMessage());
            }
        }
        if (successfulSearches == 0 && lastError != null) throw lastError;
        List<AmapService.Restaurant> restaurants = List.copyOf(restaurantMatches.values());
        if (restaurants.isEmpty()) {
            return ToolResult.failure("“" + location + "”附近暂时没有找到与“" + requestedKeyword
                    + "”匹配的店铺。已尝试：" + String.join("、", searchKeywords)
                    + "。可以换一个品牌、餐品名称，或扩大搜索范围。");
        }
        String displayKeyword = displayKeyword(requestedKeyword, searchKeywords);
        return ToolResult.success(formatRestaurantTable(location, displayKeyword, restaurants, amapService),
                new NearbyFoodOutput(List.of(), restaurants,
                        amapService.nearbyStaticMap(center, restaurants), List.of()));
    }

    static String displayKeyword(String requestedKeyword, List<String> searchKeywords) {
        if (searchKeywords.size() == 1 && searchKeywords.getFirst().equals(requestedKeyword)) {
            return requestedKeyword;
        }
        return requestedKeyword + "（已按：" + String.join("、", searchKeywords) + "）";
    }

    private List<byte[]> candidateMapImages(List<AmapService.Place> candidates) throws Exception {
        byte[] image = amapService.candidateStaticMap(candidates);
        return image == null ? List.of() : List.of(image);
    }

    static String formatRestaurantTable(String location, String keyword,
                                        List<AmapService.Restaurant> restaurants,
                                        AmapService amapService) {
        StringBuilder reply = new StringBuilder("**").append(cell(location)).append("**附近的**")
                .append(cell(keyword)).append("**：\n\n")
                .append("| 序号 | 店铺 | 地址 | 高德导航 | 饿了么 | 美团 |\n")
                .append("| --- | --- | --- | --- | --- | --- |\n");
        for (int index = 0; index < restaurants.size(); index++) {
            AmapService.Restaurant restaurant = restaurants.get(index);
            String restaurantName = restaurant.name();
            reply.append("| ").append(index + 1)
                    .append(" | ").append(cell(restaurantName))
                    .append(" | ").append(cell(restaurant.address().isBlank()
                            ? "以地图详情为准" : restaurant.address()))
                    .append(" | [点击此链接跳转](")
                    .append(amapService.restaurantUrl(restaurant)).append(")")
                    .append(" | [点击此链接跳转](")
                    .append(LinkShortener.elemeUrl(restaurantName)).append(")")
                    .append(" | [点击此链接跳转](")
                    .append(LinkShortener.meituanUrl(restaurantName)).append(") |\n");
        }
        return reply.append("\n> 高德链接用于导航；饿了么和美团链接使用完整分店名搜索，实际门店和配送范围以平台定位为准。")
                .toString().trim();
    }

    private static String formatLocationChoices(List<AmapService.Place> candidates) {
        StringBuilder text = new StringBuilder("找到多个同名地点，请回复序号确认：\n\n")
                .append("| 序号 | 地点 |\n")
                .append("| --- | --- |\n");
        for (int index = 0; index < candidates.size(); index++) {
            text.append("| ").append(index + 1).append(" | ")
                    .append(cell(candidates.get(index).name())).append(" |\n");
        }
        return text.append("\n回复“取消”可结束搜索。").toString();
    }

    private static String cell(String value) {
        return value == null ? "" : value.replace("|", "\\|")
                .replaceAll("[\\r\\n]+", " ").trim();
    }

    /** 工作流据此保存地点候选和餐厅选择结果。 */
    public record NearbyFoodOutput(List<AmapService.Place> candidates,
                                   List<AmapService.Restaurant> restaurants,
                                   byte[] mapImage,
                                   List<byte[]> candidateMapImages) {
        public NearbyFoodOutput {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            restaurants = restaurants == null ? List.of() : List.copyOf(restaurants);
            candidateMapImages = candidateMapImages == null ? List.of() : List.copyOf(candidateMapImages);
        }
    }
}
