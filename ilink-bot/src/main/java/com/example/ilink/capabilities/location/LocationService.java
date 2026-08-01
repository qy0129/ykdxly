package com.example.ilink.capabilities.location;

import com.example.ilink.application.conversation.UserSessionStore;
import com.example.ilink.bootstrap.Config;
import com.example.ilink.capabilities.travel.AmapService;
import com.example.ilink.platform.persistence.MySqlStore;
import com.google.gson.Gson;

import java.net.URI;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/** 管理一次性定位授权、逆地理编码和地图分享链接。 */
public final class LocationService {

    private static final String PRECISE_LOCATION_KEY = "precise_location";
    private static final long PRECISE_LOCATION_TTL_MILLIS = 30L * 60 * 1000;

    private final AmapService amapService;
    private final UserSessionStore sessions;
    private final LocationLinkParser linkParser;
    private final Clock clock;
    private final Map<String, PendingLocation> pending = new ConcurrentHashMap<>();
    private final Map<String, StoredPlace> currentPlaces = new ConcurrentHashMap<>();
    private final MySqlStore database = MySqlStore.getInstance();
    private final Gson gson = new Gson();
    private volatile String baseUrl = "";
    private final CopyOnWriteArrayList<BiConsumer<String, String>> updateListeners = new CopyOnWriteArrayList<>();

    public LocationService(AmapService amapService, UserSessionStore sessions, LocationLinkParser linkParser) {
        this(amapService, sessions, linkParser, Clock.systemUTC());
    }

    LocationService(AmapService amapService, UserSessionStore sessions,
                    LocationLinkParser linkParser, Clock clock) {
        this.amapService = amapService;
        this.sessions = sessions;
        this.linkParser = linkParser;
        this.clock = clock;
    }

    public void useBaseUrl(String value) {
        if (value == null || value.isBlank()) return;
        URI uri;
        try {
            uri = URI.create(value.trim());
        } catch (IllegalArgumentException error) {
            return;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) return;
        baseUrl = value.trim().replaceAll("/+$", "");
    }

    public boolean isReady() {
        return amapService.isConfigured() && !baseUrl.isBlank();
    }

    public String createAuthorizationUrl(String userId) {
        if (!isReady() || userId == null || userId.isBlank()) return "";
        pruneExpired();
        String token = UUID.randomUUID().toString().replace("-", "");
        pending.put(token, new PendingLocation(userId, clock.millis() + Config.LOCATION_TOKEN_TTL.toMillis()));
        return baseUrl + "/location/authorize/" + token;
    }

    public boolean isTokenActive(String token) {
        PendingLocation request = pending.get(token);
        if (request == null) return false;
        if (request.expiresAtMillis() > clock.millis()) return true;
        pending.remove(token, request);
        return false;
    }

    public LocationUpdate submitGps(String token, double latitude, double longitude, Double accuracy) throws Exception {
        PendingLocation request = pending.get(token);
        if (request == null || request.expiresAtMillis() <= clock.millis()) {
            if (request != null) pending.remove(token, request);
            throw new IllegalArgumentException("定位链接已失效，请回到微信重新获取");
        }
        validateCoordinate(longitude, latitude);
        if (accuracy != null && (!Double.isFinite(accuracy) || accuracy < 0 || accuracy > 100_000)) {
            throw new IllegalArgumentException("定位精度参数不合法");
        }
        if (!pending.remove(token, request)) throw new IllegalArgumentException("定位链接已使用");
        try {
            AmapService.Place place = amapService.reverseGeocode(longitude, latitude, "gps");
            if (place == null) throw new IllegalStateException("暂时无法识别这个位置");
            return save(request.userId(), place, true);
        } catch (Exception error) {
            if (request.expiresAtMillis() > clock.millis()) pending.putIfAbsent(token, request);
            throw error;
        }
    }

    public LinkUpdate updateFromSharedLink(String userId, String text) {
        LocationLinkParser.ParseResult parsed = linkParser.parse(text);
        if (!parsed.recognized()) return LinkUpdate.ignored();
        if (parsed.location() == null) {
            return LinkUpdate.failed("已识别为地图位置链接，但没有读到具体坐标。请发送地图中的“分享位置”链接。");
        }
        if (!amapService.isConfigured()) {
            return LinkUpdate.failed("已识别位置链接，但尚未配置高德 Web 服务 Key，暂时无法转换为地址。");
        }
        try {
            LocationLinkParser.SharedLocation shared = parsed.location();
            AmapService.Place place = amapService.reverseGeocode(
                    shared.longitude(), shared.latitude(), shared.coordinateSystem());
            if (place == null) return LinkUpdate.failed("已读取位置坐标，但暂时无法识别具体地址。");
            LocationUpdate update = save(userId, place, false);
            return LinkUpdate.updated(update);
        } catch (Exception error) {
            return LinkUpdate.failed("位置链接解析失败，请稍后重试或使用定位授权链接。");
        }
    }

    public void onLocationUpdated(BiConsumer<String, String> listener) {
        if (listener != null) updateListeners.add(listener);
    }

    /** 返回本进程内由 GPS 或地图链接确认过的精确坐标。 */
    public AmapService.Place currentPlace(String userId) {
        if (userId == null || userId.isBlank()) return null;
        StoredPlace cached = currentPlaces.get(userId);
        if (cached != null) return restoreCurrentPlace(userId, cached);
        String value = database.loadUserState(userId, PRECISE_LOCATION_KEY);
        if (value.isBlank()) return null;
        try {
            StoredPlace stored = gson.fromJson(value, StoredPlace.class);
            return restoreCurrentPlace(userId, stored);
        } catch (RuntimeException error) {
            database.deleteUserState(userId, PRECISE_LOCATION_KEY);
            return null;
        }
    }

    private LocationUpdate save(String userId, AmapService.Place place, boolean notify) {
        sessions.setCurrentLocation(userId, place.name());
        StoredPlace stored = new StoredPlace(place.name(), place.longitude(), place.latitude(), clock.millis());
        currentPlaces.put(userId, stored);
        database.saveUserState(userId, PRECISE_LOCATION_KEY,
                gson.toJson(stored));
        LocationUpdate update = new LocationUpdate(userId, place.name(),
                Double.parseDouble(place.longitude()), Double.parseDouble(place.latitude()));
        if (notify) {
            for (BiConsumer<String, String> listener : updateListeners) {
                try {
                    listener.accept(userId, place.name());
                } catch (RuntimeException ignored) {
                }
            }
        }
        return update;
    }

    private void pruneExpired() {
        long now = clock.millis();
        pending.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
    }

    private AmapService.Place restoreCurrentPlace(String userId, StoredPlace stored) {
        long now = clock.millis();
        if (stored == null || stored.name() == null || stored.longitude() == null || stored.latitude() == null
                || stored.name().isBlank() || stored.longitude().isBlank() || stored.latitude().isBlank()
                || stored.savedAtMillis() <= 0 || stored.savedAtMillis() > now
                || now - stored.savedAtMillis() > PRECISE_LOCATION_TTL_MILLIS) {
            currentPlaces.remove(userId);
            if (stored != null && stored.name() != null && stored.name().equals(sessions.getCurrentLocation(userId))) {
                sessions.clearCurrentLocation(userId);
            }
            database.deleteUserState(userId, PRECISE_LOCATION_KEY);
            return null;
        }
        currentPlaces.put(userId, stored);
        return new AmapService.Place(stored.name(), stored.longitude(), stored.latitude());
    }

    private void validateCoordinate(double longitude, double latitude) {
        if (!Double.isFinite(longitude) || !Double.isFinite(latitude)
                || longitude < -180 || longitude > 180 || latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("经纬度不合法");
        }
    }

    private record PendingLocation(String userId, long expiresAtMillis) { }

    private record StoredPlace(String name, String longitude, String latitude, long savedAtMillis) { }

    public record LocationUpdate(String userId, String address, double longitude, double latitude) { }

    public record LinkUpdate(boolean recognized, boolean updated, String message, LocationUpdate location) {
        static LinkUpdate ignored() { return new LinkUpdate(false, false, "", null); }
        static LinkUpdate failed(String message) { return new LinkUpdate(true, false, message, null); }
        static LinkUpdate updated(LocationUpdate location) {
            return new LinkUpdate(true, true, "已更新当前位置：" + location.address(), location);
        }
    }
}
