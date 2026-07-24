package com.example.ilink.tools.food;

import com.example.ilink.feature.travel.AmapService;
import com.example.ilink.tools.core.Tool;
import com.example.ilink.tools.core.ToolArguments;
import com.example.ilink.tools.core.ToolContext;
import com.example.ilink.tools.core.ToolDefinition;
import com.example.ilink.tools.core.ToolResult;
import com.google.gson.JsonObject;

import java.util.List;

/** 从高德 POI 数据检索用户当前位置附近的餐饮，并返回手机端店铺位置链接。 */
public final class NearbyFoodTool implements Tool {

    public static final String NAME = "nearby_food_search";
    private final AmapService amapService;
    private final ToolDefinition definition;

    public NearbyFoodTool(AmapService amapService) {
        this.amapService = amapService;
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
        String keyword = ToolArguments.string(arguments, "keyword", "").trim();
        if (keyword.isBlank()) keyword = "美食";
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
        List<AmapService.Restaurant> restaurants = amapService.nearbyRestaurants(center, keyword);
        if (restaurants.isEmpty()) {
            return ToolResult.failure("“" + location + "”附近暂时没有找到“" + keyword
                    + "”相关店铺。可以换一个品牌、餐品名称，或扩大搜索范围。");
        }
        return ToolResult.success(formatRestaurantTable(location, keyword, restaurants, amapService),
                new NearbyFoodOutput(List.of(), restaurants,
                        amapService.nearbyStaticMap(center, restaurants), List.of()));
    }

    private List<byte[]> candidateMapImages(List<AmapService.Place> candidates) throws Exception {
        List<byte[]> images = new java.util.ArrayList<>();
        for (int index = 0; index < Math.min(5, candidates.size()); index++) {
            images.add(amapService.candidateStaticMap(candidates.get(index), index + 1));
        }
        return List.copyOf(images);
    }

    static String formatRestaurantTable(String location, String keyword,
                                        List<AmapService.Restaurant> restaurants,
                                        AmapService amapService) {
        StringBuilder reply = new StringBuilder("**").append(cell(location)).append("**附近的**")
                .append(cell(keyword)).append("**：\n\n")
                .append("| 序号 | 店铺 | 地址 | 导航 |\n")
                .append("| --- | --- | --- | --- |\n");
        for (int index = 0; index < restaurants.size(); index++) {
            AmapService.Restaurant restaurant = restaurants.get(index);
            reply.append("| ").append(index + 1)
                    .append(" | ").append(cell(restaurant.name()))
                    .append(" | ").append(cell(restaurant.address().isBlank()
                            ? "以地图详情为准" : restaurant.address()))
                    .append(" | [点击此链接跳转](")
                    .append(amapService.restaurantUrl(restaurant)).append(") |\n");
        }
        return reply.append("\n> 以上为高德店铺位置链接，不是外卖下单链接。")
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
