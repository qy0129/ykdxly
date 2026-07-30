package com.example.ilink.capabilities.dashboard;

import com.example.ilink.bootstrap.Config;
import com.example.ilink.application.conversation.PlanSessionStore;
import com.example.ilink.application.conversation.UserSessionStore;
import com.example.ilink.capabilities.calendar.CalendarService;
import com.example.ilink.capabilities.memory.MemoryService;
import com.example.ilink.capabilities.planning.TodoService;
import com.example.ilink.capabilities.life.TaskCheckinService;
import com.example.ilink.capabilities.weather.WeatherLocation;
import com.example.ilink.capabilities.weather.WeatherSnapshot;
import com.example.ilink.capabilities.weather.WeatherService;
import com.example.ilink.capabilities.weather.WeatherVisualState;
import com.example.ilink.capabilities.calendar.CalendarEvent;
import com.example.ilink.capabilities.planning.PlanTask;
import com.example.ilink.capabilities.planning.TaskPlan;
import com.example.ilink.capabilities.planning.TodoItem;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;
import java.util.UUID;

/** 聚合待办、日历、计划和天气，生成日报页面需要的结构化数据。 */
public final class DailyDashboardService {

    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+");

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter UPDATED_AT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String[] QUOTES = {
            "把今天过得具体一点", "缓慢坚定，也是一种速度", "先完成，再完美",
            "专注当下，不追赶噪音", "所有积累都不会白费", "保持清醒，也保留热爱",
            "今天结束前，再向前一步"
    };

    private final TodoService todoService;
    private final CalendarService calendarService;
    private final PlanSessionStore planSessions;
    private final WeatherService weatherService;
    private final UserSessionStore userSessions;
    private final MemoryService memoryService;
    private final TaskCheckinService taskCheckins;
    private volatile CachedWeather cachedWeather;
    private volatile String weatherLoadingLocation = "";

    public DailyDashboardService(TodoService todoService, CalendarService calendarService,
                                 PlanSessionStore planSessions, WeatherService weatherService,
                                 UserSessionStore userSessions, MemoryService memoryService,
                                 TaskCheckinService taskCheckins) {
        this.todoService = todoService;
        this.calendarService = calendarService;
        this.planSessions = planSessions;
        this.weatherService = weatherService;
        this.userSessions = userSessions;
        this.memoryService = memoryService;
        this.taskCheckins = taskCheckins;
    }

    /** 返回未来七天的完整日报快照。 */
    public JsonObject snapshot(String userId) {
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();
        LocalDate lastDay = today.plusDays(6);
        TaskPlan plan = userId == null || userId.isBlank() ? null : planSessions.get(userId);
        List<TodoItem> todos = userId == null || userId.isBlank() ? List.of() : todoService.items(userId);
        List<CalendarEvent> events = userId == null || userId.isBlank()
                ? List.of() : calendarService.eventsBetween(userId, today, lastDay);

        JsonObject root = new JsonObject();
        root.addProperty("generatedAt", now.format(UPDATED_AT));
        root.addProperty("date", today.toString());
        root.addProperty("dayNumber", today.getDayOfMonth());
        root.addProperty("monthYear", today.format(DateTimeFormatter.ofPattern("yyyy.MM")));
        root.addProperty("monthName", today.format(DateTimeFormatter.ofPattern("MMMM", Locale.ENGLISH)).toUpperCase());
        root.addProperty("weekday", weekday(today.getDayOfWeek()));
        root.addProperty("weekNumber", today.get(WeekFields.ISO.weekOfWeekBasedYear()));
        root.addProperty("theme", plan == null ? theme(now.getHour()) : plan.goal());
        root.addProperty("keyword", "清醒 / 专注 / 完成");
        root.add("quotes", quotes());
        root.add("summary", summary(today, todos, plan));
        root.add("todos", todoJson(today, todos));
        root.add("days", days(today, todos, events, plan));
        root.add("weather", weather(userId));
        root.add("trend", trend(today, todos, plan));
        return root;
    }

    public boolean completeTodo(String userId, String todoId) {
        return userId != null && !userId.isBlank() && todoService.completeById(userId, todoId);
    }

    public boolean completePlanTask(String userId, String taskId) {
        return userId != null && !userId.isBlank() && taskCheckins.completeById(userId, taskId);
    }

    public TodoItem createTodo(String userId, String title, LocalDate date, LocalTime time,
                               int reminderMinutes) {
        requireUser(userId);
        if (title == null || title.isBlank()) throw new IllegalArgumentException("待办标题不能为空");
        if (date == null) throw new IllegalArgumentException("待办日期不能为空");
        LocalTime dueTime = time == null ? LocalTime.of(9, 0) : time;
        return todoService.create(userId, title.trim(), date.atTime(dueTime), Math.max(0, reminderMinutes));
    }

    public PlanTask createPlanTask(String userId, String title, String description, LocalDate date,
                                   int estimatedMinutes, String priority) {
        requireUser(userId);
        if (title == null || title.isBlank()) throw new IllegalArgumentException("计划标题不能为空");
        if (date == null) throw new IllegalArgumentException("计划日期不能为空");
        TaskPlan plan = planSessions.get(userId);
        if (plan == null) {
            LocalDate deadline = date.plusDays(6);
            plan = new TaskPlan("PLAN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                    "我的七日计划", deadline.toString(), "", LocalDate.now().toString(), List.of());
        }
        PlanTask task = new PlanTask(
                "TASK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                title.trim(), description, Math.max(15, estimatedMinutes), priority,
                date.toString(), "pending");
        List<PlanTask> tasks = new ArrayList<>(plan.tasks());
        tasks.add(task);
        planSessions.set(userId, plan.withTasks(tasks));
        return task;
    }

    /** 只返回天气区域，供天气后台刷新使用，避免重新计算整张计划表。 */
    public JsonObject weatherSnapshot(String userId) {
        return weather(userId);
    }

    private JsonObject summary(LocalDate today, List<TodoItem> todos, TaskPlan plan) {
        List<TodoItem> relevantTodos = todos.stream()
                .filter(todo -> !"cancelled".equals(todo.status()))
                .toList();
        List<PlanTask> tasks = plan == null ? List.of() : plan.tasks();
        long total = relevantTodos.size() + tasks.size();
        long completed = relevantTodos.stream().filter(todo -> "completed".equals(todo.status())).count()
                + tasks.stream().filter(task -> "completed".equals(task.status())).count();
        int focusMinutes = tasks.stream()
                .filter(task -> today.toString().equals(task.scheduledDate()))
                .mapToInt(PlanTask::estimatedMinutes)
                .sum();
        JsonObject summary = new JsonObject();
        summary.addProperty("total", total);
        summary.addProperty("completed", completed);
        summary.addProperty("remaining", Math.max(0, total - completed));
        summary.addProperty("completionRate", total == 0 ? 0 : Math.round(completed * 100f / total));
        summary.addProperty("focusMinutes", focusMinutes);
        summary.addProperty("focusLabel", formatDuration(focusMinutes));
        summary.addProperty("sleepLabel", "未记录");
        return summary;
    }

    private JsonArray todoJson(LocalDate today, List<TodoItem> todos) {
        JsonArray result = new JsonArray();
        todos.stream()
                .filter(todo -> "pending".equals(todo.status()))
                .limit(8)
                .forEach(todo -> {
                    JsonObject value = new JsonObject();
                    value.addProperty("id", todo.id());
                    value.addProperty("title", todo.title());
                    value.addProperty("dueAt", todo.dueAt() == null ? "" : todo.dueAt().toString());
                    value.addProperty("dueLabel", dueLabel(today, todo.dueAt()));
                    value.addProperty("overdue", todo.dueAt() != null && todo.dueAt().isBefore(LocalDateTime.now()));
                    result.add(value);
                });
        return result;
    }

    private JsonArray days(LocalDate today, List<TodoItem> todos,
                           List<CalendarEvent> events, TaskPlan plan) {
        JsonArray days = new JsonArray();
        Set<String> todoCalendarIds = new HashSet<>();
        for (TodoItem todo : todos) {
            if (!todo.calendarEventId().isBlank()) todoCalendarIds.add(todo.calendarEventId());
        }
        for (int offset = 0; offset < 7; offset++) {
            LocalDate date = today.plusDays(offset);
            List<DashboardItem> items = new ArrayList<>();
            for (TodoItem todo : todos) {
                if ("cancelled".equals(todo.status())) continue;
                LocalDate itemDate = todo.dueAt() == null
                        ? ("pending".equals(todo.status()) && offset == 0 ? today : null)
                        : todo.dueAt().toLocalDate();
                if (date.equals(itemDate)) {
                    items.add(new DashboardItem(todo.id(), todo.title(), "todo", "待办",
                            todo.dueAt() == null ? null : todo.dueAt().toLocalTime(), todo.status(),
                            "medium", 45, "pending".equals(todo.status()), ""));
                }
            }
            for (CalendarEvent event : events) {
                if (date.equals(event.startAt().toLocalDate()) && !todoCalendarIds.contains(event.id())) {
                    items.add(new DashboardItem(event.id(), event.title(), "calendar", event.type(),
                            event.startAt().toLocalTime(), event.status(), priorityForType(event.type()),
                            60, false, actionUrl(event.notes())));
                }
            }
            if (plan != null) {
                for (PlanTask task : plan.tasks()) {
                    if (date.toString().equals(task.scheduledDate())) {
                        items.add(new DashboardItem(task.id(), task.title(), "plan", "计划",
                                null, task.status(), task.priority(), task.estimatedMinutes(),
                                !"completed".equals(task.status()), ""));
                    }
                }
            }
            items.sort(Comparator.comparing(DashboardItem::time,
                    Comparator.nullsFirst(Comparator.naturalOrder())).thenComparing(DashboardItem::title));

            JsonObject day = new JsonObject();
            day.addProperty("date", date.format(DATE));
            day.addProperty("dayNumber", date.getDayOfMonth());
            day.addProperty("weekday", weekdayShort(date.getDayOfWeek()));
            day.addProperty("today", offset == 0);
            JsonArray itemValues = new JsonArray();
            for (DashboardItem item : items) itemValues.add(itemJson(item));
            day.add("items", itemValues);
            days.add(day);
        }
        return days;
    }

    private JsonObject itemJson(DashboardItem item) {
        JsonObject value = new JsonObject();
        value.addProperty("id", item.id());
        value.addProperty("title", item.title());
        value.addProperty("kind", item.kind());
        value.addProperty("label", item.label());
        value.addProperty("time", item.time() == null ? "" : item.time().format(TIME));
        value.addProperty("status", item.status());
        value.addProperty("priority", item.priority());
        value.addProperty("durationMinutes", item.durationMinutes());
        value.addProperty("interactive", item.interactive());
        value.addProperty("actionUrl", item.actionUrl());
        return value;
    }

    private String actionUrl(String notes) {
        if (notes == null) return "";
        var matcher = URL_PATTERN.matcher(notes);
        return matcher.find() ? matcher.group() : "";
    }

    private JsonObject weather(String userId) {
        String locationName = userId == null ? "" : userSessions.getCurrentCity(userId);
        if ((locationName == null || locationName.isBlank()) && userId != null) {
            locationName = userSessions.getCurrentLocation(userId);
        }
        if ((locationName == null || locationName.isBlank()) && userId != null) {
            locationName = memoryService.value(userId, "home_location");
        }
        if (locationName == null || locationName.isBlank()) locationName = Config.BRIEFING_DEFAULT_LOCATION;
        if (locationName == null || locationName.isBlank()) return unavailableWeather("尚未设置常用城市");

        CachedWeather cache = cachedWeather;
        long cacheMinutes = cache != null && cache.value().has("available")
                && cache.value().get("available").getAsBoolean() ? 15 : 1;
        if (cache != null && cache.location().equals(locationName)
                && cache.loadedAt().isAfter(LocalDateTime.now().minusMinutes(cacheMinutes))) {
            return cache.value();
        }
        String requestedLocation = locationName;
        if (beginWeatherRefresh(requestedLocation)) {
            CompletableFuture.runAsync(() -> refreshWeather(requestedLocation));
        }
        return cache != null && cache.location().equals(requestedLocation)
                ? cache.value() : loadingWeather(requestedLocation);
    }

    private boolean beginWeatherRefresh(String locationName) {
        synchronized (this) {
            if (locationName.equals(weatherLoadingLocation)) return false;
            weatherLoadingLocation = locationName;
            return true;
        }
    }

    private void requireUser(String userId) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("用户身份不能为空");
    }

    private void refreshWeather(String locationName) {
        try {
            List<WeatherLocation> locations = weatherService.searchLocations(locationName);
            JsonObject value;
            if (locations.isEmpty()) {
                value = unavailableWeather("暂时没有找到天气地点");
            } else {
                WeatherSnapshot snapshot = weatherService.queryWeatherSnapshot(locations.getFirst());
                value = parseWeather(locationName, snapshot.text());
                value.add("visual", visualJson(snapshot.visual(), true));
            }
            cachedWeather = new CachedWeather(locationName, LocalDateTime.now(), value);
        } catch (Exception error) {
            System.err.println("[日报页面] 天气查询失败: " + error.getMessage());
            cachedWeather = new CachedWeather(locationName, LocalDateTime.now(),
                    unavailableWeather("天气服务暂时未响应"));
        } finally {
            synchronized (this) {
                if (locationName.equals(weatherLoadingLocation)) weatherLoadingLocation = "";
            }
        }
    }

    private JsonObject loadingWeather(String locationName) {
        JsonObject value = unavailableWeather("正在获取天气");
        value.addProperty("location", locationName);
        value.addProperty("details", "天气数据正在后台更新，请稍后刷新页面");
        value.add("visual", visualJson(null, false));
        return value;
    }

    private JsonObject parseWeather(String location, String text) {
        String headline = "天气待更新";
        String temperature = "--";
        String rain = "--";
        String details = "";
        for (String line : text.split("\\R")) {
            if (line.contains("天气：")) headline = line.substring(line.indexOf("天气：") + 3).trim();
            else if (line.startsWith("温度：")) temperature = line.substring(3).trim();
            else if (line.contains("降水概率：")) rain = line.substring(line.indexOf("降水概率：") + 5).trim();
            else if (line.startsWith("当前温度：") || line.startsWith("湿度：")) {
                details = details.isBlank() ? line : details + " · " + line;
            }
        }
        JsonObject value = new JsonObject();
        value.addProperty("available", true);
        value.addProperty("location", location);
        value.addProperty("headline", headline);
        value.addProperty("temperature", temperature);
        value.addProperty("rain", rain);
        value.addProperty("details", details);
        return value;
    }

    private JsonObject unavailableWeather(String message) {
        JsonObject value = new JsonObject();
        value.addProperty("available", false);
        value.addProperty("location", "天气");
        value.addProperty("headline", message);
        value.addProperty("temperature", "--");
        value.addProperty("rain", "--");
        value.addProperty("details", "告诉 Bot 你所在的城市后，这里会显示实时天气。" );
        value.add("visual", visualJson(null, false));
        return value;
    }

    private JsonObject visualJson(WeatherVisualState visual, boolean ready) {
        JsonObject value = new JsonObject();
        value.addProperty("ready", ready);
        if (visual == null) {
            value.addProperty("weatherCode", -1);
            value.addProperty("conditionGroup", "unknown");
            value.addProperty("conditionName", "天气待更新");
            value.addProperty("day", true);
            value.addProperty("cloudCover", 0.45);
            value.addProperty("precipitation", 0);
            value.addProperty("precipitationProbability", 0);
            value.addProperty("windSpeed", 0);
            value.addProperty("windDirection", 0);
            value.addProperty("temperature", 0);
            value.addProperty("feelsLike", 0);
            value.addProperty("timezone", "");
            return value;
        }
        value.addProperty("weatherCode", visual.weatherCode());
        value.addProperty("conditionGroup", visual.conditionGroup());
        value.addProperty("conditionName", visual.conditionName());
        value.addProperty("day", visual.day());
        value.addProperty("cloudCover", visual.cloudCover());
        value.addProperty("precipitation", visual.precipitation());
        value.addProperty("precipitationProbability", visual.precipitationProbability());
        value.addProperty("windSpeed", visual.windSpeed());
        value.addProperty("windDirection", visual.windDirection());
        value.addProperty("temperature", visual.temperature());
        value.addProperty("feelsLike", visual.feelsLike());
        value.addProperty("timezone", visual.timezone());
        return value;
    }

    private JsonArray trend(LocalDate today, List<TodoItem> todos, TaskPlan plan) {
        JsonArray values = new JsonArray();
        for (int offset = 0; offset < 7; offset++) {
            LocalDate date = today.plusDays(offset);
            List<PlanTask> tasks = plan == null ? List.of() : plan.tasks().stream()
                    .filter(task -> date.toString().equals(task.scheduledDate()))
                    .toList();
            int plannedMinutes = tasks.stream().mapToInt(PlanTask::estimatedMinutes).sum();
            long completed = tasks.stream().filter(task -> "completed".equals(task.status())).count();
            completed += todos.stream()
                    .filter(todo -> "completed".equals(todo.status()) && todo.dueAt() != null
                            && date.equals(todo.dueAt().toLocalDate()))
                    .count();
            JsonObject point = new JsonObject();
            point.addProperty("label", weekdayShort(date.getDayOfWeek()));
            point.addProperty("plannedMinutes", plannedMinutes);
            point.addProperty("completed", completed);
            values.add(point);
        }
        return values;
    }

    private JsonArray quotes() {
        JsonArray values = new JsonArray();
        for (String quote : QUOTES) values.add(quote);
        return values;
    }

    private String dueLabel(LocalDate today, LocalDateTime dueAt) {
        if (dueAt == null) return "未设置时间";
        LocalDate date = dueAt.toLocalDate();
        String day = date.equals(today) ? "今天"
                : date.equals(today.plusDays(1)) ? "明天"
                : date.format(DateTimeFormatter.ofPattern("M月d日"));
        return day + " " + dueAt.format(TIME);
    }

    private String formatDuration(int minutes) {
        if (minutes <= 0) return "未安排";
        int hours = minutes / 60;
        int rest = minutes % 60;
        if (hours == 0) return rest + "分钟";
        return rest == 0 ? hours + "小时" : hours + "小时" + rest + "分钟";
    }

    private String priorityForType(String type) {
        return switch (type) {
            case "工作", "学习", "待办" -> "high";
            case "休息", "生活" -> "low";
            default -> "medium";
        };
    }

    private String theme(int hour) {
        if (hour < 6) return "深夜收束计划";
        if (hour < 12) return "清醒启动计划";
        if (hour < 18) return "稳定推进计划";
        return "深夜专注计划";
    }

    private String weekday(DayOfWeek day) {
        return "星期" + weekdayShort(day);
    }

    private String weekdayShort(DayOfWeek day) {
        return switch (day) {
            case MONDAY -> "一";
            case TUESDAY -> "二";
            case WEDNESDAY -> "三";
            case THURSDAY -> "四";
            case FRIDAY -> "五";
            case SATURDAY -> "六";
            case SUNDAY -> "日";
        };
    }

    private record DashboardItem(String id, String title, String kind, String label,
                                   LocalTime time, String status, String priority,
                                   int durationMinutes, boolean interactive, String actionUrl) { }

    private record CachedWeather(String location, LocalDateTime loadedAt, JsonObject value) { }
}
