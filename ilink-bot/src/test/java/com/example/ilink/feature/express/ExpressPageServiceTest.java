package com.example.ilink.feature.express;

import com.example.ilink.feature.express.ExpressPageService.PageSnapshot;
import com.example.ilink.feature.express.ExpressService.ExpressResult;
import com.example.ilink.feature.express.ExpressService.TrackingItem;
import com.example.ilink.feature.travel.AmapService;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpressPageServiceTest {

    @Test
    void createsPageTokenWithTwentyFourHourExpiry() {
        ExpressPageService service = new ExpressPageService(new AmapService(HttpClient.newHttpClient()));
        service.activate("https://bot.example.com/");
        Instant before = Instant.now();

        String url = service.createPage(result(List.of(
                new TrackingItem("2026-07-24 12:00:00", "运输中", "南京市", "320100")), ""));
        PageSnapshot page = service.get(url.substring(url.lastIndexOf('/') + 1));

        assertTrue(url.startsWith("https://bot.example.com/express/"));
        assertTrue(page.expiresAt().isAfter(before.plus(Duration.ofHours(23).plusMinutes(59))));
        assertTrue(page.expiresAt().isBefore(Instant.now().plus(Duration.ofHours(24).plusMinutes(1))));
        assertFalse(page.mapEligible());
    }

    @Test
    void removesExpiredPageToken() {
        ExpressPageService service = new ExpressPageService(
                new AmapService(HttpClient.newHttpClient()), Duration.ofSeconds(-1));
        service.activate("https://bot.example.com");

        String url = service.createPage(result(List.of(), ""));

        assertNull(service.get(url.substring(url.lastIndexOf('/') + 1)));
    }

    @Test
    void rendererOnlyShowsEtaAndLocationsFromApiResult() {
        ExpressResult result = result(List.of(
                new TrackingItem("2026-07-24 12:00:00", "到达转运中心", "南京市", "320100")), "");
        PageSnapshot page = new PageSnapshot(result, Instant.now().plus(Duration.ofHours(24)),
                false, true, false);

        String html = new ExpressPageRenderer().render("token", page);

        assertTrue(html.contains("南京市"));
        assertFalse(html.contains("预计12分钟到达"));
        assertFalse(html.contains("杭州"));
        assertFalse(html.contains("长沙"));
        assertFalse(html.contains("广州"));
        assertFalse(html.contains("<div class=\"estimate\">"));
    }

    private ExpressResult result(List<TrackingItem> items, String estimatedDeliveryAt) {
        return new ExpressResult(true, "", "0", "SF1234567890", "shunfeng",
                "顺丰速运", items, estimatedDeliveryAt);
    }
}
