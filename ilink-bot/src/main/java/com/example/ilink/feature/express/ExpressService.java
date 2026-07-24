package com.example.ilink.feature.express;

import com.example.ilink.config.Config;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 快递公司识别与快递100物流查询服务。 */
public final class ExpressService {

    private static final String AUTO_NUMBER_URL = "https://www.kuaidi100.com/autonumber/auto?num=";
    private static final String PUBLIC_QUERY_URL = "https://www.kuaidi100.com/query?type=";
    private static final URI ENTERPRISE_QUERY_URL = URI.create("https://poll.kuaidi100.com/poll/query.do");
    private static final Pattern TRACKING_PATTERN = Pattern.compile(
            "(?i)(?<![A-Z0-9])([A-Z]{2,6}[A-Z0-9-]{6,34}|\\d{12,30})(?![A-Z0-9])");

    private final HttpClient httpClient;
    private final String customer;
    private final String key;

    public ExpressService(HttpClient httpClient) {
        this(httpClient, Config.KUAIDI100_CUSTOMER, Config.KUAIDI100_KEY);
    }

    public ExpressService(HttpClient httpClient, String customer, String key) {
        this.httpClient = httpClient;
        this.customer = customer == null ? "" : customer.trim();
        this.key = key == null ? "" : key.trim();
    }

    public ExpressResult query(String rawTrackingNo) throws IOException, InterruptedException {
        String trackingNo = normalizeTrackingNo(rawTrackingNo);
        if (!isTrackingNo(trackingNo)) {
            return ExpressResult.failure("快递单号格式不正确，请检查后重新发送。");
        }

        List<CourierInfo> couriers = detectCouriers(trackingNo);
        if (couriers.isEmpty()) return ExpressResult.failure("暂时无法识别快递公司，请确认单号是否正确。");

        ExpressResult lastResult = null;
        IOException lastError = null;
        if (hasEnterpriseCredentials()) {
            for (CourierInfo courier : couriers) {
                try {
                    lastResult = queryEnterprise(trackingNo, courier.code());
                    if (hasTracking(lastResult)) return lastResult;
                } catch (IOException e) {
                    lastError = e;
                }
            }
        }
        for (CourierInfo courier : couriers) {
            try {
                lastResult = queryPublic(trackingNo, courier.code());
                if (hasTracking(lastResult)) return lastResult;
            } catch (IOException e) {
                lastError = e;
            }
        }
        if (lastResult != null) return lastResult;
        return ExpressResult.failure(lastError == null
                ? "暂时没有查到物流信息，请稍后再试。" : lastError.getMessage());
    }

    public List<CourierInfo> detectCouriers(String trackingNo) throws IOException, InterruptedException {
        String url = AUTO_NUMBER_URL + URLEncoder.encode(trackingNo, StandardCharsets.UTF_8);
        if (!key.isBlank()) url += "&key=" + URLEncoder.encode(key, StandardCharsets.UTF_8);
        try {
            String body = get(URI.create(url));
            JsonArray array = JsonParser.parseString(body).getAsJsonArray();
            List<CourierInfo> couriers = new ArrayList<>();
            for (JsonElement element : array) {
                JsonObject item = element.getAsJsonObject();
                String code = firstString(item, "comCode", "com");
                if (!code.isBlank() && couriers.stream().noneMatch(value -> value.code().equals(code))) {
                    String name = firstString(item, "name", "comName");
                    couriers.add(new CourierInfo(code, name.isBlank() ? courierName(code) : name));
                }
            }
            if (!couriers.isEmpty()) return List.copyOf(couriers);
        } catch (RuntimeException | IOException ignored) {
            // 公共识别接口偶发不可用时继续使用本地前缀规则。
        }
        return guessCouriers(trackingNo);
    }

    private ExpressResult queryEnterprise(String trackingNo, String courierCode)
            throws IOException, InterruptedException {
        JsonObject parameterObject = new JsonObject();
        parameterObject.addProperty("com", courierCode);
        parameterObject.addProperty("num", trackingNo);
        parameterObject.addProperty("resultv2", "4");
        String parameter = parameterObject.toString();
        String body = "customer=" + URLEncoder.encode(customer, StandardCharsets.UTF_8)
                + "&sign=" + md5(parameter + key + customer)
                + "&param=" + URLEncoder.encode(parameter, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(ENTERPRISE_QUERY_URL)
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded;charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return parseResponse(send(request), trackingNo, courierCode);
    }

    private ExpressResult queryPublic(String trackingNo, String courierCode)
            throws IOException, InterruptedException {
        URI uri = URI.create(PUBLIC_QUERY_URL + URLEncoder.encode(courierCode, StandardCharsets.UTF_8)
                + "&postid=" + URLEncoder.encode(trackingNo, StandardCharsets.UTF_8));
        return parseResponse(get(uri), trackingNo, courierCode);
    }

    private String get(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "Mozilla/5.0 iLinkBot/1.0")
                .header("Accept", "application/json, text/plain, */*")
                .header("Referer", "https://www.kuaidi100.com/")
                .GET().build();
        return send(request);
    }

    private String send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("快递服务请求失败，HTTP " + response.statusCode());
        }
        return response.body();
    }

    static ExpressResult parseResponse(String body, String fallbackTrackingNo, String fallbackCourierCode) {
        try {
            JsonObject result = JsonParser.parseString(body).getAsJsonObject();
            String status = string(result, "status");
            String returnCode = string(result, "returnCode");
            boolean success = "200".equals(status) || "0".equals(returnCode)
                    || (result.has("result") && result.get("result").getAsBoolean());
            String message = firstString(result, "message", "reason");
            JsonArray data = result.getAsJsonArray("data");
            List<TrackingItem> items = new ArrayList<>();
            if (data != null) {
                for (JsonElement element : data) {
                    JsonObject item = element.getAsJsonObject();
                    items.add(new TrackingItem(firstString(item, "ftime", "time"), string(item, "context")));
                }
            }
            if (success && !items.isEmpty() && items.stream().noneMatch(ExpressService::isRealTrackingItem)) {
                success = false;
                if (message.isBlank() || "ok".equalsIgnoreCase(message)) {
                    message = "暂时没有查到这个单号的物流轨迹，请确认单号或稍后再试。";
                }
            }
            String trackingNo = string(result, "nu");
            if (trackingNo.isBlank()) trackingNo = fallbackTrackingNo;
            String courierCode = string(result, "com");
            if (courierCode.isBlank()) courierCode = fallbackCourierCode;
            if (!success && message.isBlank()) message = "暂时没有查到物流信息，请稍后再试。";
            return new ExpressResult(success, message, string(result, "state"), trackingNo,
                    courierCode, courierName(courierCode), List.copyOf(items));
        } catch (RuntimeException e) {
            return ExpressResult.failure("快递服务返回的数据无法解析，请稍后再试。");
        }
    }

    public static String extractTrackingNo(String text) {
        if (text == null || text.isBlank()) return "";
        Matcher matcher = TRACKING_PATTERN.matcher(text.replace(" ", ""));
        return matcher.find() ? normalizeTrackingNo(matcher.group(1)) : "";
    }

    static List<CourierInfo> guessCouriers(String trackingNo) {
        String value = normalizeTrackingNo(trackingNo);
        if (value.startsWith("SF")) return List.of(new CourierInfo("shunfeng", "顺丰速运"));
        if (value.startsWith("JD")) return List.of(new CourierInfo("jingdong", "京东快递"));
        if (value.matches("E[A-Z]\\d+|EMS.*")) return List.of(new CourierInfo("ems", "EMS"));
        if (value.startsWith("JT")) return List.of(new CourierInfo("jtexpress", "极兔速递"));
        if (value.startsWith("YT")) return List.of(new CourierInfo("yuantong", "圆通速递"));
        return List.of(
                new CourierInfo("zhongtong", "中通快递"),
                new CourierInfo("yuantong", "圆通速递"),
                new CourierInfo("yunda", "韵达快递"),
                new CourierInfo("shentong", "申通快递"),
                new CourierInfo("jtexpress", "极兔速递"));
    }

    public static String format(ExpressResult result) {
        if (result == null || !result.success()) {
            return result == null || result.message().isBlank()
                    ? "快递查询失败，请稍后再试。" : result.message();
        }
        StringBuilder text = new StringBuilder("快递：").append(result.courierName())
                .append("\n单号：").append(result.trackingNo());
        String state = stateName(result.state());
        if (!state.isBlank()) text.append("\n状态：").append(state);
        if (result.items().isEmpty()) return text.append("\n暂无物流轨迹。").toString();
        text.append("\n\n最新物流：\n");
        for (TrackingItem item : result.items().stream().limit(5).toList()) {
            text.append("- ").append(item.time()).append(' ').append(item.context()).append('\n');
        }
        return text.toString().trim();
    }

    private boolean hasEnterpriseCredentials() {
        return !customer.isBlank() && !key.isBlank();
    }

    private static boolean hasTracking(ExpressResult result) {
        return result != null && result.success() && result.items().stream()
                .anyMatch(ExpressService::isRealTrackingItem);
    }

    private static boolean isRealTrackingItem(TrackingItem item) {
        if (item == null || item.context() == null || item.context().isBlank()) return false;
        String context = item.context();
        return !context.contains("查无结果") && !context.contains("暂无物流")
                && !context.contains("暂无轨迹") && !context.contains("无物流信息");
    }

    private static boolean isTrackingNo(String value) {
        return value.matches("[A-Z]{2,6}[A-Z0-9-]{6,34}|\\d{12,30}");
    }

    private static String normalizeTrackingNo(String value) {
        return value == null ? "" : value.replaceAll("[\\s-]+", "").toUpperCase(Locale.ROOT);
    }

    private static String md5(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5")
                    .digest(input.getBytes(StandardCharsets.UTF_8))).toUpperCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 Java 环境不支持 MD5", e);
        }
    }

    private static String string(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static String firstString(JsonObject object, String... names) {
        for (String name : names) {
            String value = string(object, name);
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private static String stateName(String state) {
        return switch (state) {
            case "0" -> "运输中";
            case "1" -> "已揽收";
            case "2" -> "物流异常";
            case "3" -> "已签收";
            case "4", "6" -> "退回中";
            case "5" -> "派送中";
            case "10" -> "待清关";
            case "11" -> "清关中";
            case "12" -> "已清关";
            case "13" -> "清关异常";
            case "14" -> "已拒签";
            default -> "";
        };
    }

    private static String courierName(String code) {
        return switch (code == null ? "" : code) {
            case "shunfeng", "sf" -> "顺丰速运";
            case "jingdong" -> "京东快递";
            case "ems" -> "EMS";
            case "youzhengguonei" -> "中国邮政";
            case "zhongtong", "zt" -> "中通快递";
            case "yuantong", "yt" -> "圆通速递";
            case "yunda", "yd" -> "韵达快递";
            case "shentong", "sto" -> "申通快递";
            case "jtexpress" -> "极兔速递";
            case "debang" -> "德邦快递";
            default -> code == null || code.isBlank() ? "未知快递" : code;
        };
    }

    public record CourierInfo(String code, String name) {
    }

    public record TrackingItem(String time, String context) {
    }

    public record ExpressResult(boolean success, String message, String state, String trackingNo,
                                String courierCode, String courierName, List<TrackingItem> items) {
        public static ExpressResult failure(String message) {
            return new ExpressResult(false, message, "", "", "", "", List.of());
        }
    }
}
