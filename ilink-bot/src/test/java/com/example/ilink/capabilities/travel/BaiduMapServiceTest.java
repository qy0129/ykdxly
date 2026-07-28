package com.example.ilink.capabilities.travel;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaiduMapServiceTest {

    private final BaiduMapService service = new BaiduMapService(HttpClient.newHttpClient());

    @Test
    void buildsBaiduNavigationUrlWithOrderedWaypoints() {
        String url = service.navigationUrl(List.of(
                place("起点", "120.1", "30.1"),
                place("西湖", "120.2", "30.2"),
                place("终点", "120.3", "30.3")));
        String decoded = URLDecoder.decode(url, StandardCharsets.UTF_8);

        assertTrue(url.startsWith("https://api.map.baidu.com/direction?"));
        assertTrue(decoded.contains("origin=latlng:30.1,120.1|name:起点"));
        assertTrue(decoded.contains("waypoints=latlng:30.2,120.2|name:西湖"));
        assertTrue(decoded.contains("destination=latlng:30.3,120.3|name:终点"));
        assertDoesNotThrow(() -> URI.create(url));
    }

    @Test
    void rejectsIncompleteItinerary() {
        assertEquals("", service.navigationUrl(List.of(place("一点", "120.1", "30.1"))));
    }

    private AmapService.Place place(String name, String longitude, String latitude) {
        return new AmapService.Place(name, longitude, latitude);
    }
}
