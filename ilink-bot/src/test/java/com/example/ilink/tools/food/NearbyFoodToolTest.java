package com.example.ilink.tools.food;

import com.example.ilink.feature.travel.AmapService;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NearbyFoodToolTest {

    @Test
    void formatsRestaurantsAsOneMarkdownTableWithoutImageDependency() {
        AmapService amap = new AmapService(HttpClient.newHttpClient());
        List<AmapService.Restaurant> restaurants = List.of(
                new AmapService.Restaurant("麦当劳（万和路店）", "万和路1号", "120.1", "30.2"),
                new AmapService.Restaurant("麦当劳（广场店）", "金家渡路2号", "120.2", "30.3"));

        String text = NearbyFoodTool.formatRestaurantTable("阿里高桥云港园区", "麦当劳", restaurants, amap);

        assertTrue(text.contains("| 序号 | 店铺 | 地址 | 高德导航 | 饿了么 | 美团 |"));
        assertTrue(text.contains("[点击此链接跳转](https://uri.amap.com/marker?"));
        assertTrue(text.contains("[点击此链接跳转](https://h5.ele.me/search?keyword="));
        assertTrue(text.contains("[点击此链接跳转](https://h5.waimai.meituan.com/waimai/msearch/search?key="));
        assertTrue(text.contains("麦当劳（万和路店）"));
        assertTrue(text.contains("%E9%BA%A6%E5%BD%93%E5%8A%B3%EF%BC%88%E4%B8%87%E5%92%8C%E8%B7%AF%E5%BA%97%EF%BC%89"));
        assertFalse(text.contains("nearby-food-map.png"));
    }

    @Test
    void showsResolvedSearchKeywordsForPreferenceQuery() {
        assertEquals("清淡一点（已按：粥、汤面、馄饨、蒸菜）",
                NearbyFoodTool.displayKeyword(
                        "清淡一点", List.of("粥", "汤面", "馄饨", "蒸菜")));
    }
}
