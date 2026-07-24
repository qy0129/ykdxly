package com.example.ilink.feature.express;

import com.example.ilink.feature.express.ExpressPageService.PageSnapshot;
import com.example.ilink.feature.express.ExpressService.ExpressResult;
import com.example.ilink.feature.express.ExpressService.TrackingItem;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** 将真实快递结果渲染为移动端物流详情页。 */
public final class ExpressPageRenderer {

    private static final String TEMPLATE = load("/templates/express/detail.html");
    private static final DateTimeFormatter EXPIRES = DateTimeFormatter.ofPattern("M月d日 HH:mm")
            .withZone(ZoneId.systemDefault());

    public String render(String token, PageSnapshot page) {
        if (TEMPLATE.isBlank()) return errorPage("快递页面模板缺失");
        ExpressResult result = page.result();
        return TEMPLATE
                .replace("{{company}}", escape(result.courierName()))
                .replace("{{expressNo}}", escape(result.trackingNo()))
                .replace("{{status}}", escape(stateText(result.state())))
                .replace("{{statusClass}}", escape(statusClass(result.state())))
                .replace("{{progress}}", Integer.toString(progress(result.state())))
                .replace("{{estimate}}", estimate(result))
                .replace("{{timeline}}", timeline(result.items()))
                .replace("{{token}}", escape(token))
                .replace("{{mapEligible}}", Boolean.toString(page.mapEligible()))
                .replace("{{expiresAt}}", escape(EXPIRES.format(page.expiresAt())));
    }

    public String errorPage(String message) {
        return "<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>快递页面</title><body style=\"background:#090b0f;color:#e9edf3;"
                + "font-family:sans-serif;padding:48px 24px;text-align:center\">"
                + "<h2>" + escape(message) + "</h2><p style=\"color:#8993a3\">请重新查询快递</p></body></html>";
    }

    public String stateText(String state) {
        return switch (state == null ? "" : state) {
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
            default -> "物流更新中";
        };
    }

    private String statusClass(String state) {
        return switch (state == null ? "" : state) {
            case "3", "12" -> "success";
            case "2", "13", "14" -> "danger";
            case "5" -> "active";
            default -> "moving";
        };
    }

    private int progress(String state) {
        return switch (state == null ? "" : state) {
            case "1" -> 18;
            case "0", "10", "11" -> 58;
            case "5" -> 84;
            case "3", "12" -> 100;
            case "4", "6", "14" -> 72;
            default -> 40;
        };
    }

    private String estimate(ExpressResult result) {
        if (result.estimatedDeliveryAt() == null || result.estimatedDeliveryAt().isBlank()) return "";
        return "<div class=\"estimate\"><span>预计送达</span><strong>"
                + escape(result.estimatedDeliveryAt()) + "</strong></div>";
    }

    private String timeline(List<TrackingItem> items) {
        if (items == null || items.isEmpty()) {
            return "<div class=\"empty\">暂时没有物流轨迹</div>";
        }
        StringBuilder html = new StringBuilder();
        for (int index = 0; index < items.size(); index++) {
            TrackingItem item = items.get(index);
            html.append("<article class=\"event").append(index == 0 ? " current" : "").append("\">")
                    .append("<i></i><div class=\"event-body\">")
                    .append("<time>").append(escape(item.time())).append("</time>")
                    .append("<p>").append(escape(item.context())).append("</p>");
            if (item.areaName() != null && !item.areaName().isBlank()) {
                html.append("<span class=\"area\">").append(escape(item.areaName())).append("</span>");
            }
            html.append("</div></article>");
        }
        return html.toString();
    }

    private static String load(String resource) {
        try (InputStream input = ExpressPageRenderer.class.getResourceAsStream(resource)) {
            return input == null ? "" : new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
