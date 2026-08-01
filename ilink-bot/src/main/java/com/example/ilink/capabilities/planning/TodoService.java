package com.example.ilink.capabilities.planning;

import com.example.ilink.capabilities.calendar.CalendarService;
import com.example.ilink.capabilities.calendar.CalendarEvent;
import com.example.ilink.capabilities.planning.TodoItem;
import com.example.ilink.capabilities.planning.TodoStore;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Pattern;

/** 待办创建、查询、完成和取消服务。 */
public final class TodoService {

    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("M月d日 HH:mm");
    private static final Pattern DATE_QUALIFIER = Pattern.compile(
            "(?:今天|今日|明天|明日|后天|今晚|今早|明早|本周|这周|下周|下下周|周[一二三四五六日天]|"
                    + "星期[一二三四五六日天]|\\d{4}[-年]\\d{1,2}(?:[-月]\\d{1,2})?)");
    private static final Pattern CLOCK_TIME = Pattern.compile(
            "(?:凌晨|早上|上午|中午|下午|晚上|傍晚)?[零一二三四五六七八九十两\\d]{1,3}"
                    + "(?:点|时)(?:[零一二三四五六七八九十两\\d]{1,2}分?)?");
    private final TodoStore store;
    private final CalendarService calendarService;

    public TodoService(TodoStore store, CalendarService calendarService) {
        this.store = store;
        this.calendarService = calendarService;
    }

    public TodoItem create(String userId, String title, LocalDateTime dueAt, int reminderMinutes) {
        CalendarEvent event = dueAt == null ? null : calendarService.create(
                userId, title, "待办", dueAt, "none", reminderMinutes);
        LocalDateTime now = LocalDateTime.now();
        TodoItem todo = new TodoItem(UUID.randomUUID().toString(), userId, title, dueAt, "pending",
                event == null ? "" : event.id(), now, now);
        store.save(todo);
        return todo;
    }

    /** 批量创建同一条消息拆出的待办；逐项保留独立标题和时间。 */
    public List<TodoItem> createBatch(String userId, List<TodoDraft> drafts, int reminderMinutes) {
        if (drafts == null || drafts.isEmpty()) return List.of();
        List<TodoItem> created = new ArrayList<>();
        for (TodoDraft draft : drafts) {
            if (draft == null || draft.title().isBlank()) {
                throw new IllegalArgumentException("待办标题不能为空");
            }
            if (draft.title().length() > 200) {
                throw new IllegalArgumentException("待办标题不能超过 200 字");
            }
            created.add(create(userId, draft.title(), draft.dueAt(), reminderMinutes));
        }
        return List.copyOf(created);
    }

    public String list(String userId) {
        List<TodoItem> active = activeItems(userId);
        if (active.isEmpty()) return "你目前没有待完成事项。";
        StringBuilder text = new StringBuilder("你的待办：\n");
        for (int index = 0; index < active.size(); index++) {
            TodoItem todo = active.get(index);
            text.append(index + 1).append(". ").append(todo.title());
            if (todo.dueAt() != null) text.append("（").append(todo.dueAt().format(DISPLAY_TIME)).append("）");
            text.append('\n');
        }
        return text.toString().trim();
    }

    public String listWithReminders(String userId) {
        return formatWithReminders(activeItems(userId), "你的待办和提醒安排：");
    }

    public String listRecentWithReminders(String userId, List<String> todoIds) {
        if (todoIds == null || todoIds.isEmpty()) return "当前没有可查询的最近一批待办。";
        List<TodoItem> all = items(userId);
        List<TodoItem> recent = new ArrayList<>();
        for (String todoId : todoIds) {
            all.stream().filter(todo -> todo.id().equals(todoId)).findFirst().ifPresent(recent::add);
        }
        return formatWithReminders(recent, "你刚才创建的待办和提醒安排：");
    }

    private String formatWithReminders(List<TodoItem> todos, String heading) {
        if (todos.isEmpty()) return "你目前没有待完成事项。";
        StringBuilder text = new StringBuilder(heading);
        for (int index = 0; index < todos.size(); index++) {
            TodoItem todo = todos.get(index);
            CalendarEvent event = todo.calendarEventId().isBlank()
                    ? null : calendarService.findById(todo.calendarEventId());
            text.append('\n').append(index + 1).append(". ").append(todo.title())
                    .append("\n   截止时间：")
                    .append(todo.dueAt() == null ? "未设置" : todo.dueAt().format(DISPLAY_TIME))
                    .append("\n   提醒时间：")
                    .append(event == null || event.nextReminderAt() == null
                            ? "未设置" : event.nextReminderAt().format(DISPLAY_TIME))
                    .append("\n   状态：").append(statusLabel(todo.status()));
        }
        return text.toString();
    }

    private static String statusLabel(String status) {
        return switch (status) {
            case "completed" -> "已完成";
            case "cancelled" -> "已取消";
            default -> "待完成";
        };
    }

    /** 返回用户全部待办记录，供日报页面计算完成进度。 */
    public List<TodoItem> items(String userId) {
        return store.list(userId);
    }

    /** 返回按截止时间排序的未完成待办。 */
    public List<TodoItem> activeItems(String userId) {
        return store.list(userId).stream()
                .filter(todo -> "pending".equals(todo.status()))
                .toList();
    }

    /** 修改未完成待办的时间，并同步重排关联的日历提醒。 */
    public TodoItem reschedule(String userId, String todoId, LocalDateTime dueAt) {
        TodoItem todo = store.list(userId).stream()
                .filter(item -> item.id().equals(todoId) && "pending".equals(item.status()))
                .findFirst().orElse(null);
        if (todo == null || dueAt == null) return null;
        TodoItem updated = todo.withDueAt(dueAt);
        store.save(updated);
        if (!todo.calendarEventId().isBlank()) {
            calendarService.reschedule(todo.calendarEventId(), todo.title(), dueAt);
        }
        return updated;
    }

    public String reschedule(String userId, String request) {
        LocalDateTime dueAt = DateTimeParser.applyPeriodDefault(request, DateTimeParser.parse(request));
        if (dueAt == null) return "请告诉我新的待办时间。";
        String keyword = rescheduleTitle(request);
        TodoItem todo = store.list(userId).stream()
                .filter(item -> "pending".equals(item.status()))
                .filter(item -> keyword.isBlank()
                        || TodoSelection.normalizeForMatch(item.title()).contains(keyword))
                .findFirst().orElse(null);
        if (todo == null) return "当前没有找到需要改期的待办。";
        TodoItem updated = reschedule(userId, todo.id(), dueAt);
        return updated == null ? "待办改期失败。"
                : "已将待办“" + updated.title() + "”改期到 " + dueAt.format(DISPLAY_TIME) + "。";
    }

    private static String rescheduleTitle(String request) {
        if (request == null) return "";
        String value = request.trim()
                .replaceFirst("^(?:(?:请|麻烦|帮我|帮忙|请你|把|将|改期|延期|推迟|调整|修改)\\s*)+", "");
        value = value.split("(?:改到|改为|延期到|推迟到|调整到|修改到|到(?=(?:今天|明天|后天|今晚|明晚|周|星期|\\d)))", 2)[0];
        value = value.replaceFirst("(?:待办事项|待办|事项|任务)$", "").trim();
        return TodoSelection.normalizeForMatch(value);
    }

    /** 按稳定 ID 完成待办，避免网页上存在同名事项时误操作。 */
    public boolean completeById(String userId, String todoId) {
        TodoItem todo = store.list(userId).stream()
                .filter(item -> item.id().equals(todoId) && "pending".equals(item.status()))
                .findFirst().orElse(null);
        if (todo == null) return false;
        store.save(todo.withStatus("completed"));
        if (!todo.calendarEventId().isBlank()) calendarService.complete(todo.calendarEventId());
        return true;
    }

    public String completeLatest(String userId) {
        return complete(userId, "");
    }

    public String complete(String userId, String keyword) {
        CompletionResult result = tryComplete(userId, keyword);
        return result.matched() ? result.reply() : "当前没有可以完成的待办。";
    }

    /** 按用户原话匹配并完成独立待办；未匹配时允许执行层继续尝试长期计划任务。 */
    public CompletionResult tryComplete(String userId, String request) {
        List<TodoItem> matches = findActiveMatches(userId, request);
        if (matches.isEmpty()) return new CompletionResult(false, "");
        if (matches.size() > 1) {
            StringBuilder reply = new StringBuilder("找到多个符合条件的待办，请说明要完成哪一个：");
            for (TodoItem todo : matches) {
                reply.append("\n- ").append(todo.title());
                if (todo.dueAt() != null) reply.append("（").append(todo.dueAt().format(DISPLAY_TIME)).append("）");
            }
            return new CompletionResult(true, reply.toString());
        }
        TodoItem todo = matches.getFirst();
        store.save(todo.withStatus("completed"));
        if (!todo.calendarEventId().isBlank()) calendarService.complete(todo.calendarEventId());
        return new CompletionResult(true, "已记录完成：" + todo.title());
    }

    public String cancelLatest(String userId) {
        return cancel(userId, "");
    }

    public String cancel(String userId, String keyword) {
        TodoItem todo = findActive(userId, keyword);
        if (todo == null) return "当前没有可以取消的待办。";
        store.save(todo.withStatus("cancelled"));
        if (!todo.calendarEventId().isBlank()) calendarService.cancel(todo.calendarEventId());
        return "已取消待办：" + todo.title();
    }

    private TodoItem findActive(String userId, String keyword) {
        if (keyword == null || keyword.isBlank()) return store.latestActive(userId);
        return findActiveMatches(userId, keyword).stream().findFirst().orElse(null);
    }

    private List<TodoItem> findActiveMatches(String userId, String request) {
        if (request == null || request.isBlank()) {
            TodoItem latest = store.latestActive(userId);
            return latest == null ? List.of() : List.of(latest);
        }
        TodoSelection selection = TodoSelection.from(request);
        return store.list(userId).stream()
                .filter(todo -> "pending".equals(todo.status()))
                .filter(todo -> selection.matches(todo))
                .toList();
    }

    public record CompletionResult(boolean matched, String reply) {
    }

    /** 将自然语言中的操作词、时间限定和待办标题拆开，避免把整句话当成标题匹配。 */
    private record TodoSelection(LocalDate dueDate, TimeWindow timeWindow, String titleKeyword) {

        static TodoSelection from(String request) {
            String value = request == null ? "" : request.trim();
            LocalDate dueDate = DATE_QUALIFIER.matcher(value).find()
                    ? DateTimeParser.parse(value) == null ? null : DateTimeParser.parse(value).toLocalDate()
                    : null;
            return new TodoSelection(dueDate, TimeWindow.from(value), normalizeTitle(value));
        }

        boolean matches(TodoItem todo) {
            if (dueDate != null && (todo.dueAt() == null || !dueDate.equals(todo.dueAt().toLocalDate()))) {
                return false;
            }
            if (!timeWindow.matches(todo.dueAt())) return false;
            String title = normalizeForMatch(todo.title());
            if (titleKeyword.isBlank() || title.contains(titleKeyword) || titleKeyword.contains(title)) return true;
            String requestCore = semanticCore(titleKeyword);
            String titleCore = semanticCore(title);
            return !requestCore.isBlank() && !titleCore.isBlank()
                    && (titleCore.contains(requestCore) || requestCore.contains(titleCore));
        }

        private static String normalizeTitle(String request) {
            String value = request.replaceFirst(
                    "^(?:(?:请|麻烦|帮我|帮忙|请你|我要|我想|我(?:已经|已|刚刚|刚才)?|已经|已|把|将|删除|取消|移除|作废|撤销|改期|延期|推迟|调整|修改|完成|做完|办完|搞定)\\s*)+",
                    "");
            value = value.replaceFirst("^(?:完成|做完|办完|搞定)(?:了)?\\s*", "");
            value = value.replaceFirst("[，,；;]?\\s*(?:请)?(?:帮我|替我)?(?:记录|登记|更新|标记)(?:一下)?(?:这次|本次|它的|该任务的|我的)?(?:完成情况|完成状态|状态)(?:[。！？!?]\\s*)?$", "");
            value = DATE_QUALIFIER.matcher(value).replaceAll("");
            value = value.replaceAll("(?:凌晨|早上|上午|中午|下午|晚上|傍晚)", "");
            value = CLOCK_TIME.matcher(value).replaceAll("");
            value = value.replaceFirst("(?:待办事项|待办|事项|任务)(?:[。！？!?，,；;]\\s*)?$", "");
            value = value.replaceFirst("^(?:的|这条|那个|这个|该|一条)\\s*", "");
            return normalizeForMatch(value);
        }

        private static String semanticCore(String value) {
            return value.replaceAll("(?:学习|课程|任务|待办|事项|计划|工作)", "")
                    .replaceAll("(?:[零一二三四五六七八九十两\\d]+)(?:个)?(?:小时|分钟)", "");
        }

        private static String normalizeForMatch(String value) {
            return value == null ? "" : value.replaceAll("[\\s，,。！？!?：:；;\\\"“”'‘’（）()【】\\[\\]]", "")
                    .toLowerCase(Locale.ROOT);
        }
    }

    private enum TimeWindow {
        ANY(LocalTime.MIN, LocalTime.MAX),
        MORNING(LocalTime.of(6, 0), LocalTime.NOON),
        NOON(LocalTime.NOON, LocalTime.of(14, 0)),
        AFTERNOON(LocalTime.of(14, 0), LocalTime.of(18, 0)),
        EVENING(LocalTime.of(18, 0), LocalTime.MAX),
        EARLY_MORNING(LocalTime.MIN, LocalTime.of(6, 0));

        private final LocalTime start;
        private final LocalTime end;

        TimeWindow(LocalTime start, LocalTime end) {
            this.start = start;
            this.end = end;
        }

        static TimeWindow from(String request) {
            if (request.contains("凌晨")) return EARLY_MORNING;
            if (request.contains("早上") || request.contains("上午") || request.contains("今早") || request.contains("明早")) return MORNING;
            if (request.contains("中午")) return NOON;
            if (request.contains("下午")) return AFTERNOON;
            if (request.contains("晚上") || request.contains("傍晚") || request.contains("今晚")) return EVENING;
            return ANY;
        }

        boolean matches(LocalDateTime dueAt) {
            if (this == ANY) return true;
            if (dueAt == null) return false;
            LocalTime time = dueAt.toLocalTime();
            return !time.isBefore(start) && (end.equals(LocalTime.MAX) || time.isBefore(end));
        }
    }
}
