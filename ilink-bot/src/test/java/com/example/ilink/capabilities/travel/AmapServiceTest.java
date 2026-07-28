package com.example.ilink.capabilities.travel;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AmapServiceTest {

    private final AmapService service = new AmapService(HttpClient.newHttpClient());

    @Test
    void buildsOneNavigationUrlWithOrderedWaypoints() {
        String url = service.navigationUrl(List.of(
                place("起点", "120.1", "30.1"),
                place("途经一", "120.2", "30.2"),
                place("途经二", "120.3", "30.3"),
                place("终点", "120.4", "30.4")));

        assertTrue(url.startsWith("https://uri.amap.com/navigation?from=120.1,30.1,"));
        assertTrue(url.contains("&to=120.4,30.4,"));
        assertTrue(url.indexOf("120.2,30.2") < url.indexOf("120.3,30.3"));
        assertTrue(url.contains("&via="));
        assertTrue(url.contains("%7C"));
        assertTrue(url.endsWith("&mode=car&coordinate=gaode&callnative=1"));
        assertDoesNotThrow(() -> URI.create(url));
    }

    @Test
    void insertsRestaurantIntoItsActualRouteLeg() {
        List<AmapService.Place> itinerary = List.of(
                place("起点", "120.1", "30.1"),
                place("西湖", "120.2", "30.2"),
                place("杭州西站", "120.3", "30.3"),
                place("终点", "120.4", "30.4"));
        AmapService.Restaurant restaurant = new AmapService.Restaurant(
                "面馆", "测试地址", "120.25", "30.25");

        String url = service.restaurantDetourUrl(itinerary, restaurant, 1);

        assertTrue(url.indexOf("120.2,30.2") < url.indexOf("120.25,30.25"));
        assertTrue(url.indexOf("120.25,30.25") < url.indexOf("120.3,30.3"));
    }

    @Test
    void rejectsIncompleteItinerary() {
        assertEquals("", service.navigationUrl(List.of(place("只有一点", "120.1", "30.1"))));
    }

    private AmapService.Place place(String name, String longitude, String latitude) {
        return new AmapService.Place(name, longitude, latitude);
    }
}
