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
        AmapService.Place center;
        if (longitude.isBlank() || latitude.isBlank()) {
            List<AmapService.Place> candidates = amapService.searchPlaceCandidates(location);
            if (candidates.isEmpty()) return ToolResult.failure("没有找到地点“" + location + "”，请补充城市或更完整的名称。");
            if (candidates.size() > 1) {
                return ToolResult.success("找到多个同名地点，请回复序号确认。",
                        new NearbyFoodOutput(candidates, List.of(), amapService.candidateStaticMap(candidates)));
            }
            center = candidates.get(0);
        } else {
            center = new AmapService.Place(location, longitude, latitude);
        }
        List<AmapService.Restaurant> restaurants = amapService.nearbyRestaurants(center);
        if (restaurants.isEmpty()) return ToolResult.failure("没有找到附近餐饮店铺，请补充更完整的位置名称。");
        StringBuilder reply = new StringBuilder("“").append(location).append("”附近可以看看：\n");
        for (int index = 0; index < restaurants.size(); index++) {
            AmapService.Restaurant restaurant = restaurants.get(index);
            reply.append(index + 1).append(". ").append(restaurant.name()).append('\n')
                    .append("   地址：").append(restaurant.address().isBlank() ? "以地图详情为准" : restaurant.address()).append('\n')
                    .append("   手机查看：").append(amapService.restaurantUrl(restaurant)).append('\n');
        }
        reply.append("\n这些是店铺位置直达链接，不是外卖下单链接；下单链接需要美团或饿了么的门店数据接口。");
        return ToolResult.success(reply.toString().trim(),
                new NearbyFoodOutput(List.of(), restaurants, amapService.nearbyStaticMap(center, restaurants)));
    }

    /** 工作流据此决定发送候选地点图还是附近餐饮地图。 */
    public record NearbyFoodOutput(List<AmapService.Place> candidates, List<AmapService.Restaurant> restaurants,
                                   byte[] mapImage) { }
}
