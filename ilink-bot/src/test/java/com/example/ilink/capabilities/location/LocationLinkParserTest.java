package com.example.ilink.capabilities.location;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocationLinkParserTest {

    private final LocationLinkParser parser = new LocationLinkParser(HttpClient.newHttpClient());

    @Test
    void parsesAmapMarkerCoordinates() {
        LocationLinkParser.ParseResult result = parser.parse(
                "我的位置 https://uri.amap.com/marker?position=120.1551,30.2741&name=%E8%A5%BF%E6%B9%96");

        assertTrue(result.recognized());
        assertNotNull(result.location());
        assertEquals("西湖", result.location().name());
        assertEquals(120.1551, result.location().longitude());
        assertEquals(30.2741, result.location().latitude());
        assertEquals("amap", result.location().coordinateSystem());
    }

    @Test
    void parsesBaiduLatitudeFirstLocation() {
        LocationLinkParser.ParseResult result = parser.parse(
                "https://api.map.baidu.com/marker?location=30.2741,120.1551&title=%E8%A5%BF%E6%B9%96&output=html");

        assertTrue(result.recognized());
        assertNotNull(result.location());
        assertEquals(120.1551, result.location().longitude());
        assertEquals(30.2741, result.location().latitude());
        assertEquals("baidu", result.location().coordinateSystem());
    }

    @Test
    void ignoresUntrustedLookalikeDomain() {
        LocationLinkParser.ParseResult result = parser.parse(
                "https://evil-amap.com/marker?position=120.1551,30.2741");

        assertFalse(result.recognized());
    }
}
