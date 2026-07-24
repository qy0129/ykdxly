package com.example.ilink.tools.food;

import com.example.ilink.feature.travel.AmapService;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NearbyFoodToolTest {

    @Test
    void formatsRestaurantsAsOneMarkdownTableWithoutImageDependency() {
        AmapService amap = new AmapService(HttpClient.newHttpClient());
        List<AmapService.Restaurant> restaurants = List.of(
                new AmapService.Restaurant("麦当劳（万和路店）", "万和路1号", "120.1", "30.2"),
                new AmapService.Restaurant("麦当劳（广场店）", "金家渡路2号", "120.2", "30.3"));

        String text = NearbyFoodTool.formatRestaurantTable("阿里高桥云港园区", "麦当劳", restaurants, amap);

        assertTrue(text.contains("| 序号 | 店铺 | 地址 | 导航 |"));
        assertTrue(text.contains("[点击此链接跳转](https://uri.amap.com/marker?"));
        assertTrue(text.contains("麦当劳（万和路店）"));
        assertFalse(text.contains("nearby-food-map.png"));
    }
}
