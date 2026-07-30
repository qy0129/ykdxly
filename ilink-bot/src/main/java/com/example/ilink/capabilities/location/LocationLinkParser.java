package com.example.ilink.capabilities.location;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 解析高德、百度地图分享链接中的地点坐标。 */
public final class LocationLinkParser {

    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\\\"']+", Pattern.CASE_INSENSITIVE);
    private static final Pattern PATH_COORDINATE = Pattern.compile(
            "@(-?\\d{1,3}(?:\\.\\d+)?),(-?\\d{1,2}(?:\\.\\d+)?)");
    private static final int MAX_REDIRECTS = 4;

    private final HttpClient client;

    public LocationLinkParser(HttpClient client) {
        this.client = client;
    }

    public ParseResult parse(String text) {
        URI original = extractTrustedUri(text);
        if (original == null) return ParseResult.ignored();
        try {
            SharedLocation direct = parseUri(original);
            if (direct != null) return ParseResult.resolved(direct);
            URI expanded = expand(original);
            SharedLocation location = parseUri(expanded);
            return location == null ? ParseResult.unresolved() : ParseResult.resolved(location);
        } catch (Exception error) {
            return ParseResult.unresolved();
        }
    }

    private URI extractTrustedUri(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher matcher = URL.matcher(text);
        while (matcher.find()) {
            String value = trimTrailingPunctuation(matcher.group());
            try {
                URI uri = URI.create(value);
                if (trusted(uri)) return uri;
            } catch (IllegalArgumentException ignored) {
            }
        }
        return null;
    }

    private URI expand(URI uri) throws Exception {
        URI current = uri;
        for (int index = 0; index < MAX_REDIRECTS; index++) {
            HttpRequest request = HttpRequest.newBuilder(current)
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "Mozilla/5.0 iLinkBot/1.0")
                    .GET().build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 300 || response.statusCode() >= 400) return current;
            String location = response.headers().firstValue("Location").orElse("");
            if (location.isBlank()) return current;
            URI next = current.resolve(location);
            if (!trusted(next)) return current;
            current = next;
        }
        return current;
    }

    private SharedLocation parseUri(URI uri) {
        Map<String, String> query = query(uri.getRawQuery());
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String label = first(query, "name", "title", "poiname", "content");

        if (isAmap(host)) {
            double[] point = coordinate(first(query, "position", "location", "center"), false);
            if (point == null) point = coordinatePair(query, false);
            if (point == null) point = coordinateFromPath(uri.getPath());
            return point == null ? null : new SharedLocation(label, point[0], point[1], "amap");
        }

        double[] point = coordinate(query.get("location"), true);
        if (point == null) point = coordinate(first(query, "center", "point"), false);
        if (point == null) point = coordinatePair(query, false);
        if (point == null) point = coordinateFromPath(uri.getPath());
        return point == null ? null : new SharedLocation(label, point[0], point[1], "baidu");
    }

    private double[] coordinatePair(Map<String, String> query, boolean latitudeFirst) {
        String longitude = first(query, "lng", "lon", "longitude");
        String latitude = first(query, "lat", "latitude");
        if (longitude.isBlank() || latitude.isBlank()) return null;
        return coordinate(latitudeFirst ? latitude + "," + longitude : longitude + "," + latitude,
                latitudeFirst);
    }

    private double[] coordinateFromPath(String path) {
        if (path == null) return null;
        Matcher matcher = PATH_COORDINATE.matcher(path);
        return matcher.find() ? checked(matcher.group(1), matcher.group(2)) : null;
    }

    private double[] coordinate(String value, boolean latitudeFirst) {
        if (value == null || value.isBlank()) return null;
        String[] values = value.split(",");
        if (values.length < 2) return null;
        return latitudeFirst ? checked(values[1], values[0]) : checked(values[0], values[1]);
    }

    private double[] checked(String longitudeText, String latitudeText) {
        try {
            double longitude = Double.parseDouble(longitudeText.trim());
            double latitude = Double.parseDouble(latitudeText.trim());
            if (!Double.isFinite(longitude) || !Double.isFinite(latitude)
                    || longitude < -180 || longitude > 180 || latitude < -90 || latitude > 90) return null;
            return new double[]{longitude, latitude};
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private Map<String, String> query(String rawQuery) {
        Map<String, String> values = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) return values;
        for (String item : rawQuery.split("&")) {
            int separator = item.indexOf('=');
            String key = separator < 0 ? item : item.substring(0, separator);
            String value = separator < 0 ? "" : item.substring(separator + 1);
            values.putIfAbsent(decode(key).toLowerCase(Locale.ROOT), decode(value));
        }
        return values;
    }

    private String first(Map<String, String> values, String... keys) {
        for (String key : keys) {
            String value = values.get(key);
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException error) {
            return value;
        }
    }

    private boolean trusted(URI uri) {
        if (uri == null || uri.getHost() == null) return false;
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return false;
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return isAmap(host) || host.equals("baidu.com") || host.endsWith(".baidu.com")
                || host.equals("baidu.cn") || host.endsWith(".baidu.cn");
    }

    private boolean isAmap(String host) {
        return host.equals("amap.com") || host.endsWith(".amap.com");
    }

    private String trimTrailingPunctuation(String value) {
        return value.replaceFirst("[，。！？、；：,.;:!?）)】\\]]+$", "");
    }

    public record SharedLocation(String name, double longitude, double latitude, String coordinateSystem) {
        public SharedLocation {
            name = name == null ? "" : name.trim();
        }
    }

    public record ParseResult(boolean recognized, SharedLocation location) {
        static ParseResult ignored() { return new ParseResult(false, null); }
        static ParseResult unresolved() { return new ParseResult(true, null); }
        static ParseResult resolved(SharedLocation location) { return new ParseResult(true, location); }
    }
}
