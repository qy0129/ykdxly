package com.example.ilink.feature.food;

import com.example.ilink.feature.travel.AmapService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FoodOrderServiceTest {

    private final FoodOrderService service = new FoodOrderService();

    @Test
    void generatesPlatformLinksForMultipleRestaurants() {
        String result = service.generateLinks("外婆家，肯德基");

        assertTrue(result.contains("外婆家："));
        assertTrue(result.contains("肯德基："));
        assertTrue(result.contains("https://h5.ele.me/search?keyword="));
        assertTrue(result.contains("https://h5.waimai.meituan.com/waimai/msearch/search?key="));
        assertTrue(result.contains("%E5%A4%96%E5%A9%86%E5%AE%B6"));
        assertTrue(result.contains("%E8%82%AF%E5%BE%B7%E5%9F%BA"));
    }

    @Test
    void returnsEmptyTextWhenRestaurantIsMissing() {
        assertEquals("", service.generateLinks("  "));
    }

    @Test
    void formatsDirectAndFallbackStoreLinksClearly() {
        AmapService.Restaurant store = new AmapService.Restaurant(
                "外婆家（武林店）", "武林路1号", "120.1", "30.1");
        FoodOrderService.ResolvedStoreLinks links = new FoodOrderService.ResolvedStoreLinks(
                store,
                new PlatformStoreLink("饿了么", "https://example.com/eleme", true),
                new PlatformStoreLink("美团", "https://example.com/meituan", false));

        String result = service.formatStoreLinks(links);

        assertTrue(result.contains("饿了么门店直达"));
        assertTrue(result.contains("美团分店精确搜索"));
        assertTrue(result.contains("武林路1号"));
    }
}
