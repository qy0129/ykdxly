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
import java.util.List;
import java.util.HashSet;
import java.util.Set;

/** 根据真实任务状态生成每日复盘，并复用日历提供主动推送。 */
public final class DailyReflectionService {

    private final PlanSessionStore planSessions;
    private final TodoService todoService;
    private final CalendarService calendarService;
    private final LifeStateStore lifeStates;

    public DailyReflectionService(PlanSessionStore planSessions, TodoService todoService,
                                  CalendarService calendarService, LifeStateStore lifeStates) {
        this.planSessions = planSessions;
        this.todoService = todoService;
        this.calendarService = calendarService;
        this.lifeStates = lifeStates;
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
        int plannedTodos = (int) todos.stream().filter(todo -> todo.dueAt() != null
                && date.equals(todo.dueAt().toLocalDate()) && !"cancelled".equals(todo.status())).count();
        int completedTodos = (int) todos.stream().filter(todo -> todo.dueAt() != null
                && date.equals(todo.dueAt().toLocalDate()) && "completed".equals(todo.status())).count();
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

        DailyReflection reflection = new DailyReflection(day, planned, completed, delayed,
                overdue, pending, observation, advice);
        lifeStates.saveReflection(userId, reflection);
        return reflection;
    }

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

    public void ensureDailyReminder(String userId, LocalTime time) {
        String eventId = lifeStates.reflectionEventId(userId);
        CalendarEvent existing = eventId.isBlank() ? null : calendarService.getEvent(eventId);
        LocalDateTime startAt = LocalDateTime.of(LocalDate.now(), time);
        if (!startAt.isAfter(LocalDateTime.now())) startAt = startAt.plusDays(1);
        if (existing != null && "active".equals(existing.status())) {
            calendarService.reschedule(existing.id(), "每日执行复盘", startAt);
            return;
        }
        CalendarEvent event = calendarService.create(userId, "每日执行复盘", "复盘", startAt,
                "daily", 0, "系统会根据今日计划、完成、延期和逾期状态生成复盘。",
                "life-reflection", "life_reflection");
        lifeStates.setReflectionEventId(userId, event.id());
    }
}
