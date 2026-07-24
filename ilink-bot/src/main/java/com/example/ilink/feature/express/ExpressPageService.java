package com.example.ilink.feature.express;

import com.example.ilink.feature.express.ExpressService.ExpressResult;
import com.example.ilink.feature.express.ExpressService.TrackingItem;
import com.example.ilink.feature.travel.AmapService;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** 保存短期快递页面数据，并异步生成只包含真实物流地点的地图。 */
public final class ExpressPageService {

    private static final Duration DEFAULT_TTL = Duration.ofHours(24);

    private final AmapService amapService;
    private final Duration ttl;
    private final Map<String, PageRecord> pages = new ConcurrentHashMap<>();
    private volatile String baseUrl = "";

    public ExpressPageService(AmapService amapService) {
        this(amapService, DEFAULT_TTL);
    }

    ExpressPageService(AmapService amapService, Duration ttl) {
        this.amapService = amapService;
        this.ttl = ttl;
    }

    public void activate(String baseUrl) {
        this.baseUrl = trimTrailingSlash(baseUrl);
    }

    public void deactivate() {
        baseUrl = "";
    }

    public String createPage(ExpressResult result) {
        if (result == null || !result.success() || baseUrl.isBlank()) return "";
        removeExpired();
        String token = UUID.randomUUID().toString().replace("-", "");
        List<String> areas = realAreas(result.items());
        PageRecord record = new PageRecord(result, Instant.now().plus(ttl), areas.size() >= 2);
        pages.put(token, record);
        if (record.mapEligible() && amapService.isConfigured()) {
            CompletableFuture.runAsync(() -> buildMap(record, areas));
        } else {
            record.finishMap(null);
        }
        return baseUrl + "/express/" + token;
    }

    public PageSnapshot get(String token) {
        PageRecord record = validRecord(token);
        return record == null ? null : record.snapshot();
    }

    public byte[] mapImage(String token) {
        PageRecord record = validRecord(token);
        return record == null || record.mapImage == null ? new byte[0] : record.mapImage.clone();
    }

    private PageRecord validRecord(String token) {
        if (token == null || token.isBlank()) return null;
        PageRecord record = pages.get(token);
        if (record == null) return null;
        if (record.expiresAt.isBefore(Instant.now())) {
            pages.remove(token, record);
            return null;
        }
        return record;
    }

    private void buildMap(PageRecord record, List<String> areas) {
        try {
            List<AmapService.Place> places = new ArrayList<>();
            for (String area : areas.stream().limit(6).toList()) {
                AmapService.Place place = amapService.geocode(area);
                if (place != null) places.add(new AmapService.Place(area, place.longitude(), place.latitude()));
            }
            record.finishMap(places.size() >= 2 ? amapService.staticMap(places) : null);
        } catch (Exception ignored) {
            record.finishMap(null);
        }
    }

    private List<String> realAreas(List<TrackingItem> items) {
        if (items == null || items.isEmpty()) return List.of();
        List<TrackingItem> chronological = new ArrayList<>(items);
        Collections.reverse(chronological);
        LinkedHashSet<String> areas = new LinkedHashSet<>();
        for (TrackingItem item : chronological) {
            if (item != null && item.areaName() != null && !item.areaName().isBlank()) {
                areas.add(item.areaName().trim());
            }
        }
        return List.copyOf(areas);
    }

    private void removeExpired() {
        Instant now = Instant.now();
        pages.entrySet().removeIf(entry -> entry.getValue().expiresAt.isBefore(now));
    }

    private String trimTrailingSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    public record PageSnapshot(ExpressResult result, Instant expiresAt,
                               boolean mapEligible, boolean mapResolved, boolean mapAvailable) { }

    private static final class PageRecord {
        private final ExpressResult result;
        private final Instant expiresAt;
        private final boolean mapEligible;
        private volatile boolean mapResolved;
        private volatile byte[] mapImage;

        private PageRecord(ExpressResult result, Instant expiresAt, boolean mapEligible) {
            this.result = result;
            this.expiresAt = expiresAt;
            this.mapEligible = mapEligible;
        }

        private boolean mapEligible() {
            return mapEligible;
        }

        private void finishMap(byte[] value) {
            mapImage = value == null || value.length == 0 ? null : value.clone();
            mapResolved = true;
        }

        private PageSnapshot snapshot() {
            return new PageSnapshot(result, expiresAt, mapEligible,
                    mapResolved, mapImage != null && mapImage.length > 0);
        }
    }
}
