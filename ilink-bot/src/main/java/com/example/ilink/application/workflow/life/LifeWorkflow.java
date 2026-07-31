package com.example.ilink.application.workflow.life;

import com.example.ilink.application.conversation.PlanSessionStore;
import com.example.ilink.application.messaging.ReplyChannel;
import com.example.ilink.application.messaging.ReplySender;
import com.example.ilink.application.routing.IntentResult;
import com.example.ilink.capabilities.life.DailyReflectionService;
import com.example.ilink.capabilities.life.LifeStateStore;
import com.example.ilink.capabilities.life.PlanReminderService;
import com.example.ilink.capabilities.life.StudyPlanBuilder;
import com.example.ilink.capabilities.life.StudyPlanDraft;
import com.example.ilink.capabilities.life.StudyPlanProfile;
import com.example.ilink.capabilities.life.TaskCheckinService;
import com.example.ilink.capabilities.calendar.CalendarEvent;
import com.example.ilink.capabilities.planning.PlanTask;
import com.example.ilink.capabilities.planning.TaskPlan;
import com.example.ilink.capabilities.planning.TaskPlanningService;
import com.example.ilink.capabilities.web.SearchResult;
import com.example.ilink.capabilities.web.WebSearchService;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 学习计划、任务反馈、多计划和每日复盘的统一业务工作流。 */
public final class LifeWorkflow {

    private static final Pattern PERIOD = Pattern.compile("(\\d+)\\s*(天|周|个月|月)");
    private static final Pattern DURATION = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(小时|分钟)");
    private static final Pattern CLOCK = Pattern.compile("(?:(上午|中午|下午|晚上|今晚))?\\s*(\\d{1,2})(?:点|时)(?:(\\d{1,2})分?)?");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final PlanSessionStore planSessions;
    private final TaskPlanningService planningService;
    private final WebSearchService webSearchService;
    private final StudyPlanBuilder studyPlanBuilder;
    private final PlanReminderService reminders;
    private final TaskCheckinService checkins;
    private final DailyReflectionService reflections;
    private final LifeStateStore lifeStates;
    private final ReplySender replySender;

    public LifeWorkflow(PlanSessionStore planSessions, TaskPlanningService planningService,
                        WebSearchService webSearchService, StudyPlanBuilder studyPlanBuilder,
                        PlanReminderService reminders, TaskCheckinService checkins,
                        DailyReflectionService reflections, LifeStateStore lifeStates,
                        ReplySender replySender) {
        this.planSessions = planSessions;
        this.planningService = planningService;
        this.webSearchService = webSearchService;
        this.studyPlanBuilder = studyPlanBuilder;
        this.reminders = reminders;
        this.checkins = checkins;
        this.reflections = reflections;
        this.lifeStates = lifeStates;
        this.replySender = replySender;
    }

    public void startStudyPlan(ReplyChannel client, String userId, String text, IntentResult route) throws Exception {
        String subject = route.planGoal().isBlank() ? extractSubject(text) : route.planGoal();
        if (subject.isBlank()) subject = text.trim();
        StudyPlanDraft draft = applyInlineFields(new StudyPlanDraft(subject, 0, 0, "", "", ""), text);
        lifeStates.saveDraft(userId, draft);
        if (complete(draft)) {
            createStudyPlan(client, userId, draft);
        } else {
            replySender.sendReply(client, userId, nextQuestion(draft));
        }
    }

    public boolean hasPendingStudyPlan(String userId) {
        return lifeStates.draft(userId) != null;
    }

    /** 草稿不阻塞其他功能，只接收看起来像当前问题答案的短回复。 */
    public boolean acceptsPendingReply(String userId, String text) {
        StudyPlanDraft draft = lifeStates.draft(userId);
        if (draft == null) return false;
        String value = text == null ? "" : text.trim();
        if (value.equals("取消")) return true;
        if (value.length() > 80 || value.matches(".*(天气|搜索|查一下|新闻|快递|画|生成文件|打车|外卖|待办|今天学什么|今日学习|复盘|查看计划|切换计划|完成了|延期).*")) return false;
        if (draft.periodDays() == 0) return parsePeriod(value) > 0;
        if (draft.dailyMinutes() == 0) return parseMinutes(value) > 0;
        if (draft.level().isBlank() || draft.target().isBlank()) return !value.isBlank();
        return parseTime(value) != null;
    }

    public void completePendingStudyPlan(ReplyChannel client, String userId, String text) throws Exception {
        if ("取消".equals(text.trim())) {
            lifeStates.clearDraft(userId);
            replySender.sendReply(client, userId, "已取消这次学习计划创建。");
            return;
        }
        StudyPlanDraft draft = lifeStates.draft(userId);
        if (draft == null) return;
        if (draft.periodDays() == 0) {
            int days = parsePeriod(text);
            if (days <= 0) {
                replySender.sendReply(client, userId, "请给出学习周期，例如“30天”或“8周”。");
                return;
            }
            draft = draft.withPeriodDays(Math.min(180, days));
        } else if (draft.dailyMinutes() == 0) {
            int minutes = parseMinutes(text);
            if (minutes <= 0) {
                replySender.sendReply(client, userId, "请给出每天可投入的时间，例如“每天1小时”。");
                return;
            }
            draft = draft.withDailyMinutes(Math.min(480, minutes));
        } else if (draft.level().isBlank()) {
            draft = draft.withLevel(text);
        } else if (draft.target().isBlank()) {
            draft = draft.withTarget(text);
        } else if (draft.reminderTime().isBlank()) {
            LocalTime time = parseTime(text);
            if (time == null) {
                replySender.sendReply(client, userId, "请给出提醒时间，例如“晚上8点”。");
                return;
            }
            draft = draft.withReminderTime(time.format(TIME));
        }
        lifeStates.saveDraft(userId, draft);
        if (complete(draft)) createStudyPlan(client, userId, draft);
        else replySender.sendReply(client, userId, nextQuestion(draft));
    }

    public void listPlans(ReplyChannel client, String userId) throws Exception {
        List<TaskPlan> plans = planSessions.list(userId);
        if (plans.isEmpty()) {
            replySender.sendReply(client, userId, "目前还没有计划。");
            return;
        }
        TaskPlan active = planSessions.get(userId);
        StringBuilder text = new StringBuilder("你的计划：\n");
        for (TaskPlan plan : plans) {
            text.append(plan.id().equals(active.id()) ? "* " : "- ")
                    .append(plan.id()).append("  ").append(plan.goal()).append("  ")
                    .append(plan.completedCount()).append('/').append(plan.tasks().size()).append('\n');
        }
        text.append("\n带上计划编号或目标名称即可切换。");
        replySender.sendReply(client, userId, text.toString().trim());
    }

    public void selectPlan(ReplyChannel client, String userId, String text) throws Exception {
        String selector = text.replace("切换计划", "").replace("选择计划", "").trim();
        TaskPlan selected = planSessions.select(userId, selector);
        replySender.sendReply(client, userId, selected == null
                ? "没有找到对应计划，请先发送“查看所有计划”。"
                : "已切换到计划：" + selected.goal() + "（" + selected.id() + "）");
    }

    public void todayLearning(ReplyChannel client, String userId) throws Exception {
        TaskPlan plan = planSessions.get(userId);
        if (plan == null) {
            replySender.sendReply(client, userId, "当前没有学习计划。");
            return;
        }
        String today = LocalDate.now().toString();
        List<PlanTask> tasks = plan.tasks().stream()
                .filter(task -> today.equals(task.scheduledDate()) && !"completed".equals(task.status())).toList();
        if (tasks.isEmpty()) {
            replySender.sendReply(client, userId, "今天没有待完成的计划任务。");
            return;
        }
        StringBuilder text = new StringBuilder("今天要完成：\n");
        for (PlanTask task : tasks) text.append("- ").append(task.title()).append("（")
                .append(task.estimatedMinutes()).append("分钟）\n  ").append(task.description()).append('\n');
        replySender.sendReply(client, userId, text.toString().trim());
    }

    public void updateTask(ReplyChannel client, String userId, String text) throws Exception {
        TaskCheckinService.CheckinResult result = checkins.checkIn(userId, text);
        replySender.sendReply(client, userId, result.message());
    }

    public void reflectToday(ReplyChannel client, String userId) throws Exception {
        reflections.ensureDailyReminder(userId, LocalTime.of(21, 30));
        replySender.sendReply(client, userId, reflections.buildAndSave(userId, LocalDate.now()).toDisplayText());
    }

    public void reflectionHistory(ReplyChannel client, String userId) throws Exception {
        replySender.sendReply(client, userId, reflections.history(userId));
    }

    /** 开启或更新时间唯一的每日复盘事件，供待办监督流程复用。 */
    public CalendarEvent enableDailyReflection(String userId, LocalTime time) {
        return reflections.ensureDailyReminder(userId, time);
    }

    public boolean completePlanTaskById(String userId, String taskId) {
        return checkins.completeById(userId, taskId);
    }

    private void createStudyPlan(ReplyChannel client, String userId, StudyPlanDraft draft) throws Exception {
        List<SearchResult> resources;
        try {
            resources = webSearchService.search(draft.subject() + " 系统教程 学习资料", 8).stream()
                    .filter(result -> relevant(result, draft.subject())).limit(5).toList();
        } catch (Exception error) {
            System.err.println("[学习计划] 资料搜索失败，使用基础学习模板: " + error.getMessage());
            resources = List.of();
        }
        List<PlanTask> tasks = studyPlanBuilder.build(draft, resources);
        LocalDate deadline = LocalDate.now().plusDays(draft.periodDays() - 1L);
        TaskPlan plan = planningService.createPlan(draft.subject() + "学习计划", deadline,
                "每天" + draft.dailyMinutes() + "分钟", tasks);
        planSessions.set(userId, plan);
        StudyPlanProfile profile = new StudyPlanProfile(plan.id(), draft.subject(), LocalDate.now().toString(),
                deadline.toString(), draft.dailyMinutes(), draft.level(), draft.target(), draft.reminderTime(),
                studyPlanBuilder.sourceUrls(resources));
        lifeStates.saveProfile(userId, profile);
        reminders.sync(userId, plan, LocalTime.parse(draft.reminderTime()));
        reflections.ensureDailyReminder(userId, LocalTime.of(21, 30));
        lifeStates.clearDraft(userId);

        StringBuilder reply = new StringBuilder("学习计划已创建：").append(plan.goal()).append('\n')
                .append("计划编号：").append(plan.id()).append('\n')
                .append("周期：").append(draft.periodDays()).append("天，每天 ")
                .append(draft.dailyMinutes()).append(" 分钟，").append(draft.reminderTime()).append(" 提醒。\n\n")
                .append("前几天安排：\n");
        plan.tasks().stream().limit(7).forEach(task -> reply.append("- ").append(task.scheduledDate())
                .append(" ").append(task.title()).append('\n'));
        if (plan.tasks().size() > 7) reply.append("- 其余 ").append(plan.tasks().size() - 7).append(" 天已保存到计划和七日页面。\n");
        if (!profile.sources().isEmpty()) {
            reply.append("\n学习来源：\n");
            profile.sources().forEach(source -> reply.append("- ").append(source).append('\n'));
        }
        reply.append("\n之后可以回复“今天学什么”“完成了”“延期”“不会”或“今日复盘”。");
        replySender.sendReply(client, userId, reply.toString().trim());
    }

    private StudyPlanDraft applyInlineFields(StudyPlanDraft draft, String text) {
        int days = parsePeriod(text);
        int minutes = parseMinutes(text);
        LocalTime time = parseTime(text);
        if (days > 0) draft = draft.withPeriodDays(Math.min(180, days));
        if (minutes > 0) draft = draft.withDailyMinutes(Math.min(480, minutes));
        if (time != null && (text.contains("提醒") || text.contains("推送"))) draft = draft.withReminderTime(time.format(TIME));
        return draft;
    }

    private String nextQuestion(StudyPlanDraft draft) {
        if (draft.periodDays() == 0) return "准备学习多久？例如“30天”或“8周”。";
        if (draft.dailyMinutes() == 0) return "每天能稳定投入多长时间？例如“每天1小时”。";
        if (draft.level().isBlank()) return "你目前的基础怎么样？例如“零基础”或“学过但题做得少”。";
        if (draft.target().isBlank()) return "希望达到什么结果？例如“期末80分”或“能独立做中等题”。";
        return "每天几点提醒你开始学习？例如“晚上8点”。";
    }

    private boolean complete(StudyPlanDraft draft) {
        return draft.periodDays() > 0 && draft.dailyMinutes() > 0 && !draft.level().isBlank()
                && !draft.target().isBlank() && !draft.reminderTime().isBlank();
    }

    private String extractSubject(String text) {
        return text.replaceAll("^(请|帮我|我想|我要|准备)?", "")
                .replaceAll("(制定|创建|安排)?(一个|一份)?学习计划", "")
                .replaceAll("(学习|学)(多久)?", "").replaceAll("[，,。.!！?？].*$", "").trim();
    }

    private static int parsePeriod(String text) {
        Matcher matcher = PERIOD.matcher(text == null ? "" : text);
        if (!matcher.find()) {
            if (text != null && text.contains("一个月")) return 30;
            return 0;
        }
        int amount = Integer.parseInt(matcher.group(1));
        return switch (matcher.group(2)) {
            case "周" -> amount * 7;
            case "个月", "月" -> amount * 30;
            default -> amount;
        };
    }

    private static int parseMinutes(String text) {
        Matcher matcher = DURATION.matcher(text == null ? "" : text);
        if (!matcher.find()) return 0;
        double amount = Double.parseDouble(matcher.group(1));
        return "小时".equals(matcher.group(2)) ? (int) Math.round(amount * 60) : (int) Math.round(amount);
    }

    private static LocalTime parseTime(String text) {
        Matcher matcher = CLOCK.matcher(text == null ? "" : text);
        if (!matcher.find()) return null;
        int hour = Integer.parseInt(matcher.group(2));
        int minute = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));
        String period = matcher.group(1);
        if (("下午".equals(period) || "晚上".equals(period) || "今晚".equals(period)) && hour < 12) hour += 12;
        if ("中午".equals(period) && hour < 11) hour += 12;
        try {
            return LocalTime.of(hour, minute);
        } catch (RuntimeException error) {
            return null;
        }
    }

    private boolean relevant(SearchResult result, String subject) {
        String keyword = subject == null ? "" : subject.replaceAll("学习|课程|计划", "").trim();
        if (keyword.isBlank()) return false;
        String content = (result.title() + " " + result.summary()).toLowerCase();
        return content.contains(keyword.toLowerCase());
    }
}
