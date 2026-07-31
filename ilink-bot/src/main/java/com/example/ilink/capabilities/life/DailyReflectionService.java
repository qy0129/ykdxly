package com.example.ilink.capabilities.life;

import com.example.ilink.application.conversation.PlanSessionStore;
import com.example.ilink.capabilities.calendar.CalendarEvent;
import com.example.ilink.capabilities.calendar.CalendarService;
import com.example.ilink.capabilities.planning.PlanTask;
import com.example.ilink.capabilities.planning.TaskPlan;
import com.example.ilink.capabilities.planning.TodoItem;
import com.example.ilink.capabilities.planning.TodoService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 根据真实任务状态生成每日复盘，并复用日历提供主动推送。 */
public final class DailyReflectionService {

    private final PlanSessionStore planSessions;
    private final TodoService todoService;
    private final CalendarService calendarService;
    private final LifeStateStore lifeStates;
    private final ReflectionInsightService insightService;

    public DailyReflectionService(PlanSessionStore planSessions, TodoService todoService,
                                  CalendarService calendarService, LifeStateStore lifeStates) {
        this(planSessions, todoService, calendarService, lifeStates, ReflectionInsightService.disabled());
    }

    public DailyReflectionService(PlanSessionStore planSessions, TodoService todoService,
                                  CalendarService calendarService, LifeStateStore lifeStates,
                                  ReflectionInsightService insightService) {
        this.planSessions = planSessions;
        this.todoService = todoService;
        this.calendarService = calendarService;
        this.lifeStates = lifeStates;
        this.insightService = insightService == null ? ReflectionInsightService.disabled() : insightService;
    }

    public DailyReflection buildAndSave(String userId, LocalDate date) {
        List<TaskPlan> plans = planSessions.list(userId);
        List<PlanTask> tasks = plans.stream().flatMap(plan -> plan.tasks().stream()).toList();
        List<TodoItem> todos = todoService.items(userId);
        String day = date.toString();

        List<TaskActivity> dayActivities = lifeStates.activities(userId).stream()
                .filter(activity -> activity.occurredAt().startsWith(day)).toList();
        Set<String> plannedTaskIds = new HashSet<>();
        tasks.stream().filter(task -> day.equals(task.scheduledDate()))
                .map(PlanTask::id).forEach(plannedTaskIds::add);
        dayActivities.stream().map(TaskActivity::taskId).forEach(plannedTaskIds::add);
        Set<String> completedTaskIds = new HashSet<>();
        tasks.stream().filter(task -> day.equals(task.scheduledDate()) && "completed".equals(task.status()))
                .map(PlanTask::id).forEach(completedTaskIds::add);
        dayActivities.stream().filter(activity -> "completed".equals(activity.type()))
                .map(TaskActivity::taskId).forEach(completedTaskIds::add);
        List<TodoItem> dayTodos = todos.stream().filter(todo -> !"cancelled".equals(todo.status()))
                .filter(todo -> (todo.dueAt() != null && date.equals(todo.dueAt().toLocalDate()))
                        || ("completed".equals(todo.status()) && todo.updatedAt() != null
                        && date.equals(todo.updatedAt().toLocalDate())))
                .toList();
        int plannedTodos = dayTodos.size();
        int completedTodos = (int) dayTodos.stream()
                .filter(todo -> "completed".equals(todo.status())).count();
        int planned = plannedTaskIds.size() + plannedTodos;
        int completed = completedTaskIds.size() + completedTodos;
        int delayed = (int) dayActivities.stream()
                .filter(activity -> "delayed".equals(activity.type()) || "partial".equals(activity.type())
                        || "blocked".equals(activity.type()))
                .map(TaskActivity::taskId).distinct().count();
        int overdue = (int) tasks.stream().filter(task -> !"completed".equals(task.status())
                        && !task.scheduledDate().isBlank() && task.scheduledDate().compareTo(day) < 0).count()
                + (int) todos.stream().filter(todo -> "pending".equals(todo.status()) && todo.dueAt() != null
                        && todo.dueAt().toLocalDate().isBefore(date)).count();
        int pending = Math.max(0, planned - completed);

        String observation;
        if (planned == 0) observation = "今天没有明确排期，后续计划可以再具体一些。";
        else if (completed == planned) observation = "今天的计划全部完成，当前节奏可以继续保持。";
        else if (delayed > 0) observation = "今天出现延期或学习卡点，需要检查任务粒度和可用时间是否匹配。";
        else observation = "仍有计划未完成，建议明确是遗漏、时间不足还是内容不会。";

        String advice;
        if (overdue > 0) advice = "先处理最重要的一项逾期任务，再开始新任务，避免积压继续扩大。";
        else if (delayed > 0) advice = "把最难任务拆成 30 至 60 分钟的小块，并预留复习时间。";
        else if (planned == 0) advice = "为明天确定一项核心目标和一个可验收结果。";
        else advice = "延续当前安排，优先完成明天最重要且最早截止的任务。";

        ReflectionInput input = reflectionInput(userId, date, tasks, todos, dayActivities,
                plannedTaskIds, completedTaskIds, dayTodos, planned, completed, delayed, overdue, pending);
        ReflectionInsightService.Insight insight = insightService.generate(userId, input.facts());
        String summary = insight == null || insight.summary().isBlank() ? observation : insight.summary();
        List<String> suggestions = insight == null || insight.suggestions().isEmpty()
                ? List.of() : insight.suggestions();
        String tomorrowFocus = insight == null || insight.tomorrowFocus().isBlank()
                ? advice : insight.tomorrowFocus();
        DailyReflection reflection = new DailyReflection(day, planned, completed, delayed,
                overdue, pending, summary, advice, input.completedLabels(), input.unfinishedLabels(),
                insight == null ? List.of() : insight.highlights(),
                insight == null ? List.of() : insight.problems(),
                insight == null ? List.of() : insight.patterns(), suggestions, tomorrowFocus, insight != null);
        lifeStates.saveReflection(userId, reflection);
        return reflection;
    }

    private ReflectionInput reflectionInput(String userId, LocalDate date, List<PlanTask> tasks, List<TodoItem> todos,
                                            List<TaskActivity> activities, Set<String> plannedTaskIds,
                                            Set<String> completedTaskIds, List<TodoItem> dayTodos,
                                            int planned, int completed, int delayed, int overdue, int pending) {
        String day = date.toString();
        Map<String, PlanTask> tasksById = new LinkedHashMap<>();
        tasks.forEach(task -> tasksById.put(task.id(), task));
        List<ReflectionInsightService.Item> completedItems = new ArrayList<>();
        List<ReflectionInsightService.Item> unfinishedItems = new ArrayList<>();

        for (PlanTask task : tasks) {
            boolean completedToday = completedTaskIds.contains(task.id());
            boolean relevantToday = plannedTaskIds.contains(task.id());
            boolean overdueTask = !"completed".equals(task.status()) && !task.scheduledDate().isBlank()
                    && task.scheduledDate().compareTo(day) < 0;
            ReflectionInsightService.Item item = new ReflectionInsightService.Item(
                    task.title(), "plan", task.scheduledDate(), task.status(), task.description());
            if (completedToday) completedItems.add(item);
            else if (relevantToday || overdueTask) unfinishedItems.add(item);
        }
        for (TodoItem todo : todos) {
            String scheduledAt = todo.dueAt() == null ? "" : todo.dueAt().format(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            ReflectionInsightService.Item item = new ReflectionInsightService.Item(
                    todo.title(), "todo", scheduledAt, todo.status(), "");
            if (dayTodos.contains(todo) && "completed".equals(todo.status())) completedItems.add(item);
            else if ("pending".equals(todo.status()) && todo.dueAt() != null
                    && !todo.dueAt().toLocalDate().isAfter(date)) unfinishedItems.add(item);
        }

        List<String> feedback = activities.stream().map(activity -> {
            PlanTask task = tasksById.get(activity.taskId());
            String title = task == null ? activity.taskId() : task.title();
            String detail = activity.detail() == null ? "" : activity.detail().trim();
            return feedbackType(activity.type()) + "：" + title + (detail.isBlank() ? "" : "；用户反馈：" + detail);
        }).toList();
        List<DailyReflection> history = lifeStates.reflections(userId);
        List<ReflectionInsightService.Trend> recentTrend = history.stream()
                .skip(Math.max(0, history.size() - 7L)).map(item ->
                new ReflectionInsightService.Trend(item.date(), item.planned(), item.completed(),
                        item.delayed(), item.overdue())).toList();
        List<ReflectionInsightService.Item> tomorrowItems = tomorrowItems(date.plusDays(1), tasks, todos);
        ReflectionInsightService.Facts facts = new ReflectionInsightService.Facts(day, planned, completed,
                delayed, overdue, pending, completedItems, unfinishedItems, feedback, recentTrend, tomorrowItems);
        return new ReflectionInput(facts,
                completedItems.stream().map(this::displayCompleted).toList(),
                unfinishedItems.stream().map(this::displayUnfinished).toList());
    }

    private List<ReflectionInsightService.Item> tomorrowItems(LocalDate tomorrow, List<PlanTask> tasks,
                                                               List<TodoItem> todos) {
        List<ReflectionInsightService.Item> values = new ArrayList<>();
        tasks.stream().filter(task -> tomorrow.toString().equals(task.scheduledDate()))
                .filter(task -> !"completed".equals(task.status()))
                .map(task -> new ReflectionInsightService.Item(task.title(), "plan", task.scheduledDate(),
                        task.status(), task.description()))
                .forEach(values::add);
        todos.stream().filter(todo -> "pending".equals(todo.status()) && todo.dueAt() != null)
                .filter(todo -> tomorrow.equals(todo.dueAt().toLocalDate()))
                .map(todo -> new ReflectionInsightService.Item(todo.title(), "todo",
                        todo.dueAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")), todo.status(), ""))
                .forEach(values::add);
        return List.copyOf(values);
    }

    private String displayCompleted(ReflectionInsightService.Item item) {
        return item.title() + (item.scheduledAt().isBlank() ? "" : "（计划：" + item.scheduledAt() + "）");
    }

    private String displayUnfinished(ReflectionInsightService.Item item) {
        return item.title() + "（状态：" + statusLabel(item.status())
                + (item.scheduledAt().isBlank() ? "" : "，计划：" + item.scheduledAt()) + "）";
    }

    private String statusLabel(String status) {
        return switch (status) {
            case "completed" -> "已完成";
            case "cancelled" -> "已取消";
            default -> "未完成";
        };
    }

    private String feedbackType(String type) {
        return switch (type) {
            case "partial" -> "部分完成";
            case "blocked" -> "遇到卡点";
            case "completed" -> "已完成";
            default -> "延期";
        };
    }

    private record ReflectionInput(ReflectionInsightService.Facts facts,
                                   List<String> completedLabels, List<String> unfinishedLabels) { }

    public String history(String userId) {
        List<DailyReflection> values = lifeStates.reflections(userId);
        if (values.isEmpty()) return "目前还没有复盘记录。";
        StringBuilder text = new StringBuilder("最近复盘：\n");
        values.stream().skip(Math.max(0, values.size() - 7)).forEach(item -> text
                .append(item.date()).append("：完成 ").append(item.completed()).append('/')
                .append(item.planned()).append("，延期 ").append(item.delayed())
                .append("，逾期 ").append(item.overdue()).append('\n'));
        return text.toString().trim();
    }

    public CalendarEvent ensureDailyReminder(String userId, LocalTime time) {
        String eventId = lifeStates.reflectionEventId(userId);
        CalendarEvent existing = eventId.isBlank() ? null : calendarService.getEvent(eventId);
        LocalDateTime startAt = LocalDateTime.of(LocalDate.now(), time);
        if (!startAt.isAfter(LocalDateTime.now())) startAt = startAt.plusDays(1);
        if (existing != null && "active".equals(existing.status())) {
            return calendarService.reschedule(existing.id(), "每日执行复盘", startAt);
        }
        CalendarEvent event = calendarService.create(userId, "每日执行复盘", "复盘", startAt,
                "daily", 0, "系统会根据今日计划、完成、延期和逾期状态生成复盘。",
                "life-reflection", "life_reflection");
        lifeStates.setReflectionEventId(userId, event.id());
        return event;
    }
}
