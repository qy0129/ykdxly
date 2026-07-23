package com.example.ilink.app;

import com.example.ilink.conversation.CalendarSessionStore;
import com.example.ilink.feature.calendar.CalendarService;
import com.example.ilink.model.CalendarEvent;
import com.example.ilink.routing.IntentResult;
import com.example.ilink.tools.planning.DateTimeParser;
import com.github.wechat.ilink.sdk.ILinkClient;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 将用户的提醒语句转换为日历事件，并处理简短的后续操作。 */
public final class CalendarWorkflow {

    private static final Pattern POSTPONE_PATTERN = Pattern.compile("延后\\s*(\\d+)\\s*分");
    private static final Pattern MONTHLY_DAY_PATTERN = Pattern.compile("每月\\s*(\\d{1,2})\\s*(?:号|日)");
    private final CalendarService calendarService;
    private final CalendarSessionStore sessions;
    private final ReplySender replySender;

    public CalendarWorkflow(CalendarService calendarService, CalendarSessionStore sessions, ReplySender replySender) {
        this.calendarService = calendarService;
        this.sessions = sessions;
        this.replySender = replySender;
    }

    public boolean hasPending(String userId) {
        return sessions.hasPending(userId);
    }

    /** 执行模型已明确识别的日历动作；时间解析只负责把结构化时间转为本地日期。 */
    public void handle(ILinkClient client, String userId, String text, IntentResult route) throws Exception {
        String action = route.calendarAction();
        if ("list".equals(action)) {
            LocalDateTime time = parseEventTime(route.calendarTime());
            replySender.sendReply(client, userId, calendarService.listForDay(userId,
                    time == null ? LocalDate.now() : time.toLocalDate()), route.replyMode(), route.voiceStyle());
            return;
        }
        if ("complete".equals(action)) {
            replySender.sendReply(client, userId, calendarService.completeLatest(userId), route.replyMode(), route.voiceStyle());
            return;
        }
        if ("cancel".equals(action)) {
            replySender.sendReply(client, userId, calendarService.cancelLatest(userId), route.replyMode(), route.voiceStyle());
            return;
        }
        if ("snooze".equals(action)) {
            int minutes = route.calendarReminderMinutes() > 0 ? route.calendarReminderMinutes() : 30;
            replySender.sendReply(client, userId, calendarService.postponeLatest(userId, minutes),
                    route.replyMode(), route.voiceStyle());
            return;
        }
        createStructuredEvent(client, userId, route);
    }

    public void handle(ILinkClient client, String userId, String text) throws Exception {
        if (sessions.hasPending(userId)) {
            completePending(client, userId, text);
            return;
        }
        if (text.contains("今天有什么") || text.contains("查看今天") || text.equals("今日日程")) {
            replySender.sendReply(client, userId, calendarService.listForDay(userId, LocalDate.now()));
            return;
        }
        if (text.contains("明天有什么") || text.contains("查看明天") || text.equals("明日日程")) {
            replySender.sendReply(client, userId, calendarService.listForDay(userId, LocalDate.now().plusDays(1)));
            return;
        }
        Matcher postpone = POSTPONE_PATTERN.matcher(text);
        if (postpone.find()) {
            replySender.sendReply(client, userId, calendarService.postponeLatest(userId,
                    Integer.parseInt(postpone.group(1))));
            return;
        }
        if (text.equals("完成了") || text.startsWith("完成")) {
            replySender.sendReply(client, userId, calendarService.completeLatest(userId));
            return;
        }
        createEvent(client, userId, text);
    }

    /** 创建事件时使用模型字段，缺少时间才进入本地补充会话。 */
    private void createStructuredEvent(ILinkClient client, String userId, IntentResult route) throws Exception {
        String title = route.calendarTitle().trim();
        if (title.isBlank()) {
            replySender.sendReply(client, userId, "请告诉我需要记录的事项，例如“提醒我吃药”。",
                    route.replyMode(), route.voiceStyle());
            return;
        }
        String recurrence = switch (route.calendarRecurrence()) {
            case "daily", "weekly", "monthly", "yearly" -> route.calendarRecurrence();
            default -> "none";
        };
        LocalDateTime time = parseEventTime(route.calendarTime());
        if (time == null) {
            sessions.setPending(userId, new CalendarSessionStore.PendingEvent(title, inferType(title), recurrence,
                    Math.max(0, route.calendarReminderMinutes())));
            replySender.sendReply(client, userId, "我来帮你记录“" + title + "”。请告诉我具体提醒时间。",
                    route.replyMode(), route.voiceStyle());
            return;
        }
        saveAndReply(client, userId, title, inferType(title), normalizeEventTime(time, route.calendarTime()), recurrence,
                Math.max(0, route.calendarReminderMinutes()));
    }

    private void createEvent(ILinkClient client, String userId, String text) throws Exception {
        String title = extractTitle(text);
        String recurrence = extractRecurrence(text);
        LocalDateTime time = parseEventTime(text);
        if (time == null) {
            sessions.setPending(userId, new CalendarSessionStore.PendingEvent(
                    title, inferType(title), recurrence, extractReminderMinutes(text)));
            replySender.sendReply(client, userId, "我来帮你记录“" + title + "”。请告诉我具体提醒时间，例如“明天早上 8 点”。");
            return;
        }
        saveAndReply(client, userId, title, inferType(title), normalizeEventTime(time, text), recurrence,
                extractReminderMinutes(text));
    }

    private void completePending(ILinkClient client, String userId, String text) throws Exception {
        if ("取消".equals(text.trim())) {
            sessions.clearPending(userId);
            replySender.sendReply(client, userId, "已取消这次日历记录。");
            return;
        }
        LocalDateTime time = parseEventTime(text);
        if (time == null) {
            replySender.sendReply(client, userId, "我还没识别到时间，请直接说例如“明天早上 8 点”，或回复“取消”。");
            return;
        }
        CalendarSessionStore.PendingEvent pending = sessions.getPending(userId);
        sessions.clearPending(userId);
        saveAndReply(client, userId, pending.title(), pending.type(), normalizeEventTime(time, text),
                pending.recurrence(), pending.reminderMinutes());
    }

    private void saveAndReply(ILinkClient client, String userId, String title, String type,
                              LocalDateTime time, String recurrence, int reminderMinutes) throws Exception {
        CalendarEvent event = calendarService.create(userId, title, type, time, recurrence, reminderMinutes);
        String repeat = switch (recurrence) {
            case "daily" -> "每天";
            case "weekly" -> "每周";
            case "monthly" -> "每月";
            case "yearly" -> "每年";
            default -> "一次";
        };
        replySender.sendReply(client, userId, "已记入日历：" + event.title() + "\n"
                + "提醒时间：" + event.nextReminderAt().toLocalDate() + " " + event.nextReminderAt().toLocalTime().withSecond(0).withNano(0)
                + "\n重复：" + repeat + "\n到时间我会主动提醒你。");
    }

    private LocalDateTime normalizeEventTime(LocalDateTime time, String text) {
        return text.matches(".*(\\d{1,2}:\\d{2}|[早中下晚]?[午上]?\\d{1,2}点).*" )
                ? time : LocalDateTime.of(time.toLocalDate(), LocalTime.of(9, 0));
    }

    /** “每月 5 号”需要单独计算下一次日期，普通日期解析不会携带这种重复语义。 */
    private LocalDateTime parseEventTime(String text) {
        LocalDateTime parsed = DateTimeParser.parse(text);
        Matcher monthlyDay = MONTHLY_DAY_PATTERN.matcher(text);
        if (!monthlyDay.find()) return parsed;
        int day = Integer.parseInt(monthlyDay.group(1));
        LocalDate today = LocalDate.now();
        LocalTime time = parsed == null ? LocalTime.of(9, 0) : parsed.toLocalTime();
        YearMonth month = YearMonth.from(today);
        for (int offset = 0; offset < 13; offset++) {
            YearMonth candidate = month.plusMonths(offset);
            if (day <= candidate.lengthOfMonth()) {
                LocalDateTime value = candidate.atDay(day).atTime(time);
                if (!value.isBefore(LocalDateTime.now())) return value;
            }
        }
        return null;
    }

    private String extractTitle(String text) {
        String title = text.replaceFirst(".*提醒我", "").replaceFirst(".*提醒", "")
                .replaceAll("每(天|周|月|年).*", "").replaceAll("提前\\d+分.*", "").trim();
        return title.isBlank() ? "日历提醒" : title;
    }

    private String extractRecurrence(String text) {
        if (text.contains("每天") || text.contains("每日")) return "daily";
        if (text.contains("每周") || text.contains("每星期")) return "weekly";
        if (text.contains("每月")) return "monthly";
        if (text.contains("每年")) return "yearly";
        return "none";
    }

    private int extractReminderMinutes(String text) {
        Matcher matcher = Pattern.compile("提前\\s*(\\d+)\\s*分").matcher(text);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private String inferType(String title) {
        if (title.matches(".*(吃|药|体检|健身|饮食).*")) return "健康";
        if (title.matches(".*(车|出发|航班|高铁|地铁).*")) return "出行";
        if (title.matches(".*(学习|阅读|课程|复习).*")) return "学习";
        if (title.matches(".*(还款|房租|水电|信用卡).*")) return "财务";
        if (title.matches(".*(生日|家庭|孩子|宠物).*")) return "家庭";
        return "生活";
    }
}
