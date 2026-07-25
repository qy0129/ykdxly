package com.example.ilink.app;

import com.example.ilink.conversation.CalendarSessionStore;
import com.example.ilink.feature.calendar.CalendarDraft;
import com.example.ilink.feature.calendar.CalendarService;
import com.example.ilink.feature.calendar.CalendarTimeResolver;
import com.example.ilink.feature.calendar.CalendarTimeResolver.ResolvedCalendarTime;
import com.example.ilink.model.CalendarEvent;
import com.example.ilink.routing.IntentResult;
import com.example.ilink.tools.planning.DateTimeParser;
import com.github.wechat.ilink.sdk.ILinkClient;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** 统一处理日历动作、时间补充和事件持久化。 */
public final class CalendarWorkflow {

    private static final DateTimeFormatter MINUTE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter SECOND_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final CalendarService calendarService;
    private final CalendarSessionStore sessions;
    private final ReplySender replySender;
    private final CalendarTimeResolver timeResolver = new CalendarTimeResolver();

    public CalendarWorkflow(CalendarService calendarService, CalendarSessionStore sessions, ReplySender replySender) {
        this.calendarService = calendarService;
        this.sessions = sessions;
        this.replySender = replySender;
    }

    public boolean hasPending(String userId) {
        return sessions.hasPending(userId);
    }

    public void clearPending(String userId) {
        sessions.clearPending(userId);
        sessions.clearPendingOperation(userId);
    }

    /** 执行模型已经识别出的日历动作。 */
    public void handle(ILinkClient client, String userId, String text, IntentResult route) throws Exception {
        switch (route.calendarAction()) {
            case "list" -> {
                LocalDateTime time = DateTimeParser.parse(route.calendarTime());
                replySender.sendReply(client, userId, calendarService.listForDay(userId,
                        time == null ? LocalDate.now() : time.toLocalDate()), route.replyMode(), route.voiceStyle());
            }
            case "complete", "cancel", "snooze" -> handleOperation(client, userId, text, route);
            default -> createEvent(client, userId, text, route);
        }
    }

    /** 合并模型补充字段与用户原话；模型不可用时仍由本地统一解析器兜底。 */
    public void completePending(ILinkClient client, String userId, String text, IntentResult route) throws Exception {
        CalendarSessionStore.PendingOperation operation = sessions.getPendingOperation(userId);
        if (operation != null) {
            completePendingOperation(client, userId, text, operation, route);
            return;
        }
        if ("取消".equals(text.trim())) {
            sessions.clearPending(userId);
            replySender.sendReply(client, userId, "好的，这次日历记录已取消。");
            return;
        }

        CalendarSessionStore.PendingEvent pending = sessions.getPending(userId);
        if (pending == null) return;
        CalendarDraft merged = merge(pending.draft(), text, route);
        ResolvedCalendarTime resolved = timeResolver.resolve(merged, merged.timeExpression(), LocalDateTime.now());
        if (!resolved.resolved()) {
            sessions.setPending(userId, new CalendarSessionStore.PendingEvent(merged));
            sendMissingTimeReply(client, userId, route);
            return;
        }

        sessions.clearPending(userId);
        saveAndReply(client, userId, merged, resolved, route);
    }

    private void handleOperation(ILinkClient client, String userId, String requestText,
                                 IntentResult route) throws Exception {
        String action = route.calendarAction();
        int minutes = snoozeMinutes(route);
        String title = route.calendarTitle() == null ? "" : route.calendarTitle().trim();
        if ("cancel".equals(action) && (title.contains("饮食")
                || requestText.contains("饮食") || requestText.contains("三餐"))) {
            int count = calendarService.cancelLatestGroupBySource(userId, "diet");
            String reply = count == 0 ? "当前没有有效的饮食提醒。" : "已取消本组 " + count + " 条三餐提醒。";
            replySender.sendReply(client, userId, reply, route.replyMode(), route.voiceStyle());
            return;
        }
        LocalDateTime parsed = DateTimeParser.parse(route.calendarTime());
        LocalDate day = parsed == null ? null : parsed.toLocalDate();
        if (title.isBlank() && day == null) {
            String reply = switch (action) {
                case "complete" -> calendarService.completeLatest(userId);
                case "cancel" -> calendarService.cancelLatest(userId);
                default -> calendarService.postponeLatest(userId, minutes);
            };
            replySender.sendReply(client, userId, reply, route.replyMode(), route.voiceStyle());
            return;
        }

        List<CalendarEvent> matches = calendarService.findActive(userId, title, day);
        if (matches.isEmpty()) {
            replySender.sendReply(client, userId, "没有找到符合名称和日期的有效日程。",
                    route.replyMode(), route.voiceStyle());
            return;
        }
        if (matches.size() == 1) {
            replySender.sendReply(client, userId, executeOperation(action, matches.getFirst().id(), minutes),
                    route.replyMode(), route.voiceStyle());
            return;
        }
        sessions.setPendingOperation(userId, new CalendarSessionStore.PendingOperation(
                action, matches.stream().map(CalendarEvent::id).toList(), minutes));
        StringBuilder prompt = new StringBuilder("找到多条符合条件的日程，请回复序号：");
        for (int index = 0; index < matches.size(); index++) {
            CalendarEvent event = matches.get(index);
            prompt.append('\n').append(index + 1).append(". ")
                    .append(event.startAt().format(MINUTE_FORMAT)).append(' ').append(event.title());
        }
        prompt.append("\n回复“取消”可结束操作。");
        replySender.sendReply(client, userId, prompt.toString(), route.replyMode(), route.voiceStyle());
    }

    private void completePendingOperation(ILinkClient client, String userId, String text,
                                          CalendarSessionStore.PendingOperation operation,
                                          IntentResult route) throws Exception {
        if ("取消".equals(text.trim())) {
            sessions.clearPendingOperation(userId);
            replySender.sendReply(client, userId, "已取消本次日历操作。");
            return;
        }
        int choice;
        try {
            choice = Integer.parseInt(text.trim());
        } catch (NumberFormatException error) {
            replySender.sendReply(client, userId, "请回复日程序号，或回复“取消”。");
            return;
        }
        if (choice < 1 || choice > operation.eventIds().size()) {
            replySender.sendReply(client, userId, "序号不在候选范围内，请重新选择。");
            return;
        }
        sessions.clearPendingOperation(userId);
        String reply = executeOperation(operation.action(), operation.eventIds().get(choice - 1),
                operation.postponeMinutes());
        if (route == null) replySender.sendReply(client, userId, reply);
        else replySender.sendReply(client, userId, reply, route.replyMode(), route.voiceStyle());
    }

    private String executeOperation(String action, String eventId, int minutes) {
        return switch (action) {
            case "complete" -> calendarService.completeById(eventId);
            case "cancel" -> calendarService.cancelById(eventId);
            default -> calendarService.postponeById(eventId, minutes);
        };
    }

    private int snoozeMinutes(IntentResult route) {
        if (route.calendarReminderMinutes() > 0) return route.calendarReminderMinutes();
        long amount = route.calendarTimeAmount();
        if (amount <= 0) return 30;
        long minutes = switch (route.calendarTimeUnit()) {
            case "second" -> Math.max(1, (amount + 59) / 60);
            case "hour" -> amount * 60;
            case "day" -> amount * 24 * 60;
            default -> amount;
        };
        return (int) Math.min(Integer.MAX_VALUE, minutes);
    }

    private void createEvent(ILinkClient client, String userId, String text, IntentResult route) throws Exception {
        String title = route.calendarTitle() == null ? "" : route.calendarTitle().trim();
        if (title.isBlank()) title = "日历提醒";
        String recurrence = normalizeRecurrence(route.calendarRecurrence());
        boolean userProvidedTime = DateTimeParser.hasTimeEvidence(text);
        int leadSeconds = route.calendarLeadTimeSeconds();
        if (leadSeconds <= 0 && text.contains("提前") && route.calendarReminderMinutes() > 0) {
            leadSeconds = Math.multiplyExact(route.calendarReminderMinutes(), 60);
        }
        CalendarDraft draft = new CalendarDraft(
                title,
                inferType(title),
                recurrence,
                userProvidedTime ? route.calendarTime() : "",
                userProvidedTime ? route.calendarTimeType() : "auto",
                userProvidedTime ? route.calendarTimeAmount() : 0,
                userProvidedTime ? route.calendarTimeUnit() : "",
                leadSeconds);

        ResolvedCalendarTime resolved = timeResolver.resolve(draft, text, LocalDateTime.now());
        if (!resolved.resolved()) {
            sessions.setPending(userId, new CalendarSessionStore.PendingEvent(draft));
            replySender.sendReply(client, userId,
                    "我先记下“" + title + "”。再告诉我时间就好，比如“30秒后”“下午四点三十三分”或“明天早上八点”。",
                    route.replyMode(), route.voiceStyle());
            return;
        }
        saveAndReply(client, userId, draft, resolved, route);
    }

    private CalendarDraft merge(CalendarDraft base, String text, IntentResult route) {
        if (route == null) {
            String expression = base.timeExpression().isBlank()
                    ? text : (base.timeExpression() + " " + text).trim();
            return base.withTime(expression, "auto", 0, "", base.leadTimeSeconds());
        }
        boolean userProvidedTime = DateTimeParser.hasTimeEvidence(text);
        String supplement = !userProvidedTime || route.calendarTime().isBlank() ? text : route.calendarTime();
        String expression = base.timeExpression().isBlank()
                ? supplement : (base.timeExpression() + " " + supplement).trim();
        String timeType = userProvidedTime && !route.calendarTimeType().isBlank()
                ? route.calendarTimeType() : "auto";
        long amount = userProvidedTime ? route.calendarTimeAmount() : 0;
        String unit = userProvidedTime ? route.calendarTimeUnit() : "";
        int leadSeconds = route.calendarLeadTimeSeconds() > 0
                ? route.calendarLeadTimeSeconds() : base.leadTimeSeconds();
        if (leadSeconds == 0 && text.contains("提前") && route.calendarReminderMinutes() > 0) {
            leadSeconds = Math.multiplyExact(route.calendarReminderMinutes(), 60);
        }
        String recurrence = "none".equals(route.calendarRecurrence())
                ? base.recurrence() : normalizeRecurrence(route.calendarRecurrence());
        return new CalendarDraft(base.title(), base.type(), recurrence, expression,
                timeType, amount, unit, leadSeconds);
    }

    private void saveAndReply(ILinkClient client, String userId, CalendarDraft draft,
                              ResolvedCalendarTime resolved, IntentResult route) throws Exception {
        CalendarEvent event = calendarService.create(userId, draft.title(), draft.type(),
                resolved.eventAt(), resolved.remindAt(), draft.recurrence());
        DateTimeFormatter format = "second".equals(resolved.precision()) ? SECOND_FORMAT : MINUTE_FORMAT;
        StringBuilder reply = new StringBuilder("好的，已经替你把“").append(event.title())
                .append("”记在日历里了。\n");
        if (resolved.leadTimeSeconds() > 0) {
            reply.append("事件时间：").append(event.startAt().format(format)).append('\n')
                    .append("提醒时间：").append(event.nextReminderAt().format(format)).append('\n');
        } else {
            reply.append("提醒时间：").append(event.nextReminderAt().format(format)).append('\n');
        }
        reply.append("重复：").append(recurrenceName(draft.recurrence()))
                .append("\n你放心，到时间我会轻轻提醒你，不让这件事被忙碌落下。");
        if (route == null) {
            replySender.sendReply(client, userId, reply.toString());
        } else {
            replySender.sendReply(client, userId, reply.toString(), route.replyMode(), route.voiceStyle());
        }
    }

    private void sendMissingTimeReply(ILinkClient client, String userId, IntentResult route) throws Exception {
        String message = "这个时间我还没完全确定。你可以直接说“30秒后”“四点三十三分”或“明天早上八点”；回复“取消”也可以。";
        if (route == null) replySender.sendReply(client, userId, message);
        else replySender.sendReply(client, userId, message, route.replyMode(), route.voiceStyle());
    }

    private String normalizeRecurrence(String recurrence) {
        return switch (recurrence) {
            case "daily", "weekly", "monthly", "yearly" -> recurrence;
            default -> "none";
        };
    }

    private String recurrenceName(String recurrence) {
        return switch (recurrence) {
            case "daily" -> "每天";
            case "weekly" -> "每周";
            case "monthly" -> "每月";
            case "yearly" -> "每年";
            default -> "一次";
        };
    }

    private String inferType(String title) {
        if (title.matches(".*(吃药|体检|健身|饮食).*")) return "健康";
        if (title.matches(".*(车|出发|航班|高铁|地铁).*")) return "出行";
        if (title.matches(".*(学习|阅读|课程|复习).*")) return "学习";
        if (title.matches(".*(还款|房租|水电|信用卡).*")) return "财务";
        if (title.matches(".*(生日|家庭|孩子|宠物).*")) return "家庭";
        return "生活";
    }
}
