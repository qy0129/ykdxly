package com.example.ilink.feature.weather;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WeatherServiceTest {

    @Test
    void restoresRouteAndExplicitDates() {
        assertEquals(LocalDate.now().plusDays(1), WeatherService.date("tomorrow_evening"));
        assertEquals(LocalDate.of(2026, 7, 25), WeatherService.date("2026-07-25_afternoon"));
        assertEquals("afternoon", WeatherService.period("2026-07-25_afternoon"));
    }

    @Test
    void ranksAndSelectsClearlyPrimaryCity() {
        WeatherLocation village = new WeatherLocation(
                "和平", "某省", "某县", "中国", 1, 1, 10, 8_000);
        WeatherLocation city = new WeatherLocation(
                "和平", "某省", "某市", "中国", 2, 2, 80, 500_000);
        List<WeatherLocation> locations = new ArrayList<>(List.of(village, city));

        WeatherService.rankLocations(locations);

        assertEquals(city, locations.getFirst());
        assertEquals(city, WeatherService.clearlyPrimary(locations));
    }
}
