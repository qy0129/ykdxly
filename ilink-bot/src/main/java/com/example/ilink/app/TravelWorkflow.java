package com.example.ilink.app;

import com.example.ilink.feature.calendar.CalendarService;
import com.example.ilink.feature.travel.AmapService;
import com.example.ilink.routing.IntentResult;
import com.example.ilink.tools.planning.DateTimeParser;
import com.github.wechat.ilink.sdk.ILinkClient;

import java.time.LocalDateTime;

/** 负责出行路线、地图输出和出发提醒的衔接。 */
public final class TravelWorkflow {

    private final AmapService amapService;
    private final CalendarService calendarService;
    private final ReplySender replySender;

    public TravelWorkflow(AmapService amapService, CalendarService calendarService, ReplySender replySender) {
        this.amapService = amapService;
        this.calendarService = calendarService;
        this.replySender = replySender;
    }

    /** 使用路由模型已提取的起终点、餐饮需求和时间预算执行出行工具链。 */
    public void handle(ILinkClient client, String userId, IntentResult route) throws Exception {
        String fromName = route.travelOrigin().trim();
        String toName = route.travelDestination().trim();
        if (fromName.isBlank() || toName.isBlank()) {
            replySender.sendReply(client, userId, "请告诉我完整的起点和终点，例如“从阿里高桥园区去杭州西站”。");
            return;
        }
        if (!amapService.isConfigured()) {
            replySender.sendReply(client, userId, "我已识别到你要从“" + fromName + "”前往“" + toName
                    + "”。当前还没有配置高德地图 Key，暂时不能生成地图；配置后我会发送地点标记图和动态导航链接。\n"
                    + "你也可以补充出发时间，我会先帮你记入日历。");
            return;
        }
        try {
            AmapService.Place from = amapService.geocode(fromName);
            AmapService.Place to = amapService.geocode(toName);
            if (from == null || to == null) {
                replySender.sendReply(client, userId, "没有准确找到起点或终点。请补充城市或更完整的地点名称。");
                return;
            }
            AmapService.Route travelRoute = amapService.driving(from, to);
            if (travelRoute == null) {
                replySender.sendReply(client, userId, "暂时无法规划这条路线，请稍后再试。");
                return;
            }
            LocalDateTime departure = route.travelDepartureTime().isBlank()
                    ? null : DateTimeParser.parse(route.travelDepartureTime());
            int timeBudgetMinutes = Math.max(0, route.timeBudgetMinutes());
            String meal = route.mealKeyword().trim();
            StringBuilder reply = new StringBuilder("从“").append(from.name()).append("”到“").append(to.name()).append("”\n")
                    .append("驾车约 ").append(formatDuration(travelRoute.durationSeconds())).append("，约 ")
                    .append(formatDistance(travelRoute.distanceMeters())).append("。\n")
                    .append("动态查看与导航：").append(amapService.navigationUrl(to));
            if (!meal.isBlank()) {
                // 餐饮 POI 是路线的增值信息，接口波动时不能阻断已有的路线和导航结果。
                try {
                    AmapService.Place midpoint = placeAt(travelRoute.midpoint());
                    var restaurants = amapService.nearbyRestaurants(midpoint, meal + "馆");
                    if (!restaurants.isEmpty()) {
                        reply.append("\n\n中途吃").append(meal).append("可以看看：");
                        for (int index = 0; index < Math.min(3, restaurants.size()); index++) {
                            AmapService.Restaurant restaurant = restaurants.get(index);
                            reply.append("\n").append(index + 1).append(". ").append(restaurant.name())
                                    .append("\n   高德：").append(amapService.restaurantUrl(restaurant));
                        }
                    } else {
                        reply.append("\n\n路线中点附近暂未找到合适的").append(meal).append("馆。");
                    }
                } catch (Exception mealError) {
                    System.err.println("[出行规划] 中途餐饮搜索失败: " + mealError.getMessage());
                    reply.append("\n\n中途餐饮暂时无法查询，你仍可先打开导航后查看沿途店铺。");
                }
            }
            if (timeBudgetMinutes > 0) {
                int estimatedTotal = (int) Math.ceil(travelRoute.durationSeconds() / 60.0) + (meal.isBlank() ? 0 : 20);
                reply.append("\n\n你可用 ").append(timeBudgetMinutes).append(" 分钟；路线加")
                        .append(meal.isBlank() ? "缓冲" : "中途用餐").append("预计约 ").append(estimatedTotal).append(" 分钟。");
                reply.append(estimatedTotal <= timeBudgetMinutes ? "时间基本够用，建议预留 10 分钟缓冲。" : "时间偏紧，建议改为在出发地或车站附近快速用餐。");
            }
            if (departure != null) {
                calendarService.create(userId, "从" + from.name() + "前往" + to.name(), "出行", departure, "none", 15);
                reply.append("\n我已记入日历，并会在出发前 15 分钟提醒你。");
            } else {
                reply.append("\n告诉我出发时间，我可以顺便帮你加入日历并提前提醒。");
            }
            // 静态图发送失败仅影响图片展示，文字路线和动态导航仍然有效。
            try {
                byte[] image = amapService.staticMap(from, to);
                if (image != null) client.sendImage(userId, image, "route-map.png", "起点和终点标记地图");
            } catch (Exception mapError) {
                System.err.println("[出行规划] 静态地图发送失败: " + mapError.getMessage());
            }
            replySender.sendReply(client, userId, reply.toString());
        } catch (Exception e) {
            replySender.sendReply(client, userId, "路线服务暂时不可用。请稍后再试，或补充更完整的起点和终点。");
            System.err.println("[出行规划] 路线解析失败: " + e.getMessage());
        }
    }

    private String formatDuration(int seconds) {
        int minutes = Math.max(1, (int) Math.ceil(seconds / 60.0));
        return minutes >= 60 ? (minutes / 60) + "小时" + (minutes % 60) + "分钟" : minutes + "分钟";
    }

    private String formatDistance(int meters) {
        return meters >= 1000 ? String.format("%.1f 公里", meters / 1000.0) : meters + "米";
    }

    private AmapService.Place placeAt(String location) {
        String[] point = location.split(",");
        return new AmapService.Place("路线中点", point[0], point[1]);
    }
}
