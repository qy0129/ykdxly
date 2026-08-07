package com.changlu.planner.features.command;

import com.changlu.planner.features.learning.LearningService;
import com.changlu.planner.features.plan.PlanExecutionService;
import com.changlu.planner.shared.database.Database;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 微信 Bot 端自然语言命令：中文短句规则解析 + 确定性执行，识别不了才回退 AI 对话。
 *
 * 反误吞三护栏：动词必须句首；创建命令必须有对象名词；完成/删除按标题反查，查不到回退 AI。
 * 已识别为命令但执行失败（数据库异常、时间冲突等）会返回友好错误，不回退 AI。
 */
public final class BotCommandService {
  /** 命令结果契约：handled=true 直接回复 message；handled=false 交给 AI 对话。 */
  public record CommandResult(boolean handled, String message) {
    public static CommandResult ok(String message) { return new CommandResult(true, message); }
    public static CommandResult pass() { return new CommandResult(false, null); }
  }

  // 学习目标不在快捷创建里：学习目标必须经 AI 生成完整计划（阶段+每日任务+日程）并走确认草案，
  // 快捷裸插只写 learning_goals 一行空壳（无 plan/无日期），与用户预期"添加进计划"不符。
  private static final Pattern CREATE = Pattern.compile("^(添加|创建|新建|新增)\\s*(待办|日程|计划|任务)\\s*(.+)$");
  private static final Pattern REMIND = Pattern.compile("^提醒我\\s*(.+)$");
  private static final Pattern COMPLETE = Pattern.compile("^(完成|搞定|做完了|已完成)\\s*(.+)$");
  private static final Pattern DELETE = Pattern.compile("^(删除|移除|删掉|去掉)\\s*(.+)$");
  private static final Pattern VIEW = Pattern.compile(
      "^(查看|看看|列出|我的|显示|查一下)\\s*(今天|明天|后天|本周|最近)?\\s*的?\\s*"
          + "(计划|任务|待办|日程|学习目标|学习|复盘|饮食|提醒|全部)\\s*(今天|明天|后天|本周|最近)?\\s*$");
  private static final Pattern TARGET_PLAN = Pattern.compile("^(到|去)\\s*(\\S+)\\s*(.*)$");

  private static final Pattern DATE_TOKEN = Pattern.compile(
      "(今天|明天|后天|礼拜[一二三四五六日天]|周[一二三四五六日天]|\\d{1,2}月\\d{1,2}[日号]|\\d{1,2}号)");
  private static final Pattern TIME_TOKEN = Pattern.compile(
      "(凌晨|早晨|早上|上午|中午|下午|傍晚|晚上|今晚|夜里|深夜|晚间)?\\s*(\\d{1,2}点半|\\d{1,2}[:：点时](?:\\d{1,2})?分?)");

  private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
  private static final DateTimeFormatter MD_HM = DateTimeFormatter.ofPattern("M月d日 HH:mm");

  /** 从标题抽离出的时间信息；time 为 null 表示没有具体时刻。 */
  record When(LocalDate date, LocalTime time, Integer reminderMinutes, String title) {}

  private record ItemRef(String table, UUID id, String title, Integer version) {}
  private record PlanTarget(UUID planId, String planName, String title) {}

  private final Database database;
  private final PlanExecutionService plans;
  private final LearningService learning;

  public BotCommandService(Database database, PlanExecutionService plans, LearningService learning) {
    this.database = database;
    this.plans = plans;
    this.learning = learning;
  }

  public CommandResult handle(String text, Database.Context context) {
    if (text == null) return CommandResult.pass();
    String trimmed = text.trim();
    if (trimmed.isEmpty()) return CommandResult.pass();
    try {
      return dispatch(trimmed, context);
    } catch (IllegalArgumentException | IllegalStateException | SQLException error) {
      // 已识别为命令但执行失败：不回退 AI，直接给友好提示。
      return CommandResult.ok("这条操作暂时没成功：" + friendlyMessage(error));
    }
  }

  private CommandResult dispatch(String text, Database.Context context) throws SQLException {
    // 优先级 1：精确只读句（沿用现有行为）。
    String normalized = text.replaceAll("[\\s，。！？、,.!?]", "");
    if (normalized.equals("今天还有什么") || normalized.equals("今天还有哪些")) return CommandResult.ok(todayOpenItems(context));
    if (normalized.equals("计划完成得怎么样")) return CommandResult.ok(progressSummary(context));

    Matcher create = CREATE.matcher(text);
    if (create.matches()) return create(context, create.group(2), create.group(3));

    Matcher remind = REMIND.matcher(text);
    if (remind.matches()) return remind(context, remind.group(1));

    Matcher complete = COMPLETE.matcher(text);
    if (complete.matches()) {
      String result = completeByTitle(context, complete.group(2));
      return result == null ? CommandResult.pass() : CommandResult.ok(result);
    }

    Matcher delete = DELETE.matcher(text);
    if (delete.matches()) {
      String result = deleteByTitle(context, delete.group(2));
      return result == null ? CommandResult.pass() : CommandResult.ok(result);
    }

    Matcher view = VIEW.matcher(text);
    if (view.matches()) {
      // 时间词可能在对象前（"看看今天的日程"）或对象后（"查看日程今天"）。
      String object = view.group(3);
      String when = view.group(2) != null ? view.group(2) : view.group(4);
      return CommandResult.ok(view(context, object, when));
    }

    return CommandResult.pass();
  }

  // ==================== 创建 ====================

  private CommandResult create(Database.Context context, String type, String tail) throws SQLException {
    return switch (type) {
      case "待办" -> createTodo(context, tail);
      case "日程" -> createSchedule(context, tail);
      case "计划" -> createPlan(context, tail);
      case "任务" -> createTask(context, tail);
      default -> CommandResult.pass();
    };
  }

  private CommandResult createTodo(Database.Context context, String tail) throws SQLException {
    When when = parseWhen(tail, "todo");
    String title = when.title();
    if (title.isEmpty()) return CommandResult.ok("待办标题不能为空。");
    LocalDateTime dueAt = null;
    if (when.date() != null) dueAt = when.date().atTime(when.time() != null ? when.time() : LocalTime.of(23, 0));
    UUID id = UUID.randomUUID();
    insertTodo(context, id, title, dueAt, null);
    return CommandResult.ok("已添加待办：" + title + dueSuffix(when));
  }

  private CommandResult createSchedule(Database.Context context, String tail) throws SQLException {
    When when = parseWhen(tail, "schedule");
    String title = when.title();
    if (title.isEmpty()) return CommandResult.ok("日程标题不能为空。");
    LocalDateTime start = when.date().atTime(when.time() != null ? when.time() : LocalTime.of(9, 0));
    JsonObject fields = new JsonObject();
    fields.addProperty("title", title);
    fields.addProperty("startAt", start.toString());
    fields.addProperty("durationMinutes", 30);
    fields.addProperty("reason", "微信命令创建日程");
    plans.createSchedule(context, fields, "wechat");
    return CommandResult.ok("已添加日程：" + title + "（" + start.format(MD_HM) + "，30 分钟）");
  }

  private CommandResult createPlan(Database.Context context, String tail) throws SQLException {
    String title = tail.trim();
    if (title.isEmpty()) return CommandResult.ok("计划名称不能为空。");
    UUID id = UUID.randomUUID();
    insertPlan(context, id, title);
    return CommandResult.ok("已创建计划：" + title);
  }

  private CommandResult createTask(Database.Context context, String tail) throws SQLException {
    PlanTarget target = resolvePlanTarget(context, tail);
    if (target == null) return CommandResult.ok("还没有进行中的计划，暂时无法添加任务。");
    When when = parseWhen(target.title(), "task");
    String title = when.title();
    if (title.isEmpty()) return CommandResult.ok("任务标题不能为空。");
    UUID stageId = firstStage(context, target.planId());
    if (stageId == null) {
      return CommandResult.ok("计划“" + target.planName() + "”还没有阶段，请先在网页端添加阶段再创建任务。");
    }
    JsonObject fields = new JsonObject();
    fields.addProperty("title", title);
    fields.addProperty("stageId", stageId.toString());
    // 任务只有给了具体时刻才写截止时间；只给日期时保持 due_at 为空。
    if (when.time() != null) fields.addProperty("dueAt", when.date().atTime(when.time()).toString());
    plans.createTask(context, target.planId(), fields, "wechat");
    return CommandResult.ok("已在计划“" + target.planName() + "”中添加任务：" + title);
  }

  private CommandResult remind(Database.Context context, String tail) throws SQLException {
    When when = parseWhen(tail, "remind");
    String title = when.title();
    if (title.isEmpty()) return CommandResult.ok("要提醒什么？例如“提醒我 喝水”。");
    LocalDateTime due = when.time() != null ? when.date().atTime(when.time()) : LocalDateTime.now().plusHours(1);
    UUID id = UUID.randomUUID();
    insertTodo(context, id, title, due, when.reminderMinutes());
    return CommandResult.ok("好的，会提醒你：" + title + "（" + due.format(MD_HM) + "）");
  }

  // ==================== 完成 / 删除 ====================

  private String completeByTitle(Database.Context context, String title) throws SQLException {
    ItemRef item = findByTitle(context, title, null);
    if (item == null) return null;
    switch (item.table()) {
      case "todos" -> {
        try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
            "UPDATE todos SET status='done', completed_at=NOW(), version=version+1 WHERE id=? AND workspace_id=? AND deleted_at IS NULL AND status<>'done'")) {
          p.setBytes(1, Database.uuidBytes(item.id())); p.setBytes(2, Database.uuidBytes(context.workspaceId())); p.executeUpdate();
        }
      }
      case "schedule_items" -> {
        try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
            "UPDATE schedule_items SET status='done', completed_at=NOW(), version=version+1 WHERE id=? AND workspace_id=? AND deleted_at IS NULL AND status<>'done'")) {
          p.setBytes(1, Database.uuidBytes(item.id())); p.setBytes(2, Database.uuidBytes(context.workspaceId())); p.executeUpdate();
        }
      }
      case "plans" -> {
        try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
            "UPDATE plans SET status='completed', progress=100 WHERE id=? AND workspace_id=? AND deleted_at IS NULL AND status<>'completed'")) {
          p.setBytes(1, Database.uuidBytes(item.id())); p.setBytes(2, Database.uuidBytes(context.workspaceId())); p.executeUpdate();
        }
      }
      case "plan_tasks" -> {
        JsonObject fields = new JsonObject();
        fields.addProperty("status", "done");
        fields.addProperty("actionType", "done_task");
        fields.addProperty("reason", "微信命令完成任务");
        plans.updateTask(context, item.id(), fields, "wechat");
      }
      default -> { }
    }
    return "已完成：" + item.title();
  }

  private String deleteByTitle(Database.Context context, String title) throws SQLException {
    ItemRef item = findByTitle(context, title, null);
    if (item == null) return null;
    switch (item.table()) {
      case "todos", "schedule_items", "plans" -> softDeleteTable(context, item);
      case "plan_tasks" -> plans.softDeleteTask(context, item.id(), item.version() == null ? 0 : item.version(), "wechat");
      default -> { }
    }
    return "已删除：" + item.title();
  }

  private void softDeleteTable(Database.Context context, ItemRef item) throws SQLException {
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "UPDATE " + item.table() + " SET deleted_at=NOW(), purge_after=DATE_ADD(NOW(), INTERVAL 30 DAY), version=version+1 "
            + "WHERE id=? AND workspace_id=? AND deleted_at IS NULL")) {
      p.setBytes(1, Database.uuidBytes(item.id())); p.setBytes(2, Database.uuidBytes(context.workspaceId())); p.executeUpdate();
    }
  }

  // ==================== 查看 ====================

  private String view(Database.Context context, String object, String when) throws SQLException {
    return switch (object) {
      case "计划" -> viewPlans(context);
      case "任务" -> viewTasks(context, when);
      case "待办" -> viewTodos(context, when);
      case "日程" -> viewSchedules(context, when == null ? "今天" : when);
      case "学习目标", "学习" -> viewLearningGoals(context);
      case "复盘" -> viewReview(context);
      case "饮食" -> viewDiet(context);
      case "提醒" -> viewReminders(context);
      case "全部" -> viewAll(context);
      default -> "这个我还看不了。";
    };
  }

  private String viewPlans(Database.Context context) throws SQLException {
    List<String> lines = new ArrayList<>();
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "SELECT title, status, progress FROM plans WHERE workspace_id=? AND deleted_at IS NULL "
            + "ORDER BY status='active' DESC, updated_at DESC LIMIT 10")) {
      p.setBytes(1, Database.uuidBytes(context.workspaceId()));
      try (ResultSet rs = p.executeQuery()) {
        while (rs.next()) {
          String label = switch (rs.getString("status")) {
            case "active" -> "进行中，进度 " + Math.round(rs.getDouble("progress")) + "%";
            case "paused" -> "已暂停";
            default -> "已完成";
          };
          lines.add(rs.getString("title") + "（" + label + "）");
        }
      }
    }
    if (lines.isEmpty()) return "还没有计划。";
    return "你的计划：\n- " + String.join("\n- ", lines);
  }

  private String viewTodos(Database.Context context, String when) throws SQLException {
    String scope = dueScopeSql(when);
    List<String> lines = new ArrayList<>();
    String query = "SELECT title, due_at, status FROM todos WHERE workspace_id=? AND created_by=? AND deleted_at IS NULL "
        + scope + " ORDER BY due_at, updated_at DESC LIMIT 15";
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(query)) {
      p.setBytes(1, Database.uuidBytes(context.workspaceId())); p.setBytes(2, Database.uuidBytes(context.userId()));
      try (ResultSet rs = p.executeQuery()) {
        while (rs.next()) {
          String marker = "done".equals(rs.getString("status")) ? "[已完成] " : "";
          Timestamp due = rs.getTimestamp("due_at");
          String whenText = due == null ? "无截止时间" : due.toLocalDateTime().format(MD_HM);
          lines.add(marker + rs.getString("title") + "（" + whenText + "）");
        }
      }
    }
    if (lines.isEmpty()) return "没有找到相关待办。";
    return "待办（" + (when == null || when.isEmpty() ? "全部" : when) + "）：\n- " + String.join("\n- ", lines);
  }

  private String viewSchedules(Database.Context context, String when) throws SQLException {
    String scope = switch (when) {
      case "明天" -> " AND DATE(start_at) = CURDATE() + INTERVAL 1 DAY";
      case "后天" -> " AND DATE(start_at) = CURDATE() + INTERVAL 2 DAY";
      case "本周" -> " AND YEARWEEK(start_at, 1) = YEARWEEK(CURDATE(), 1)";
      case "最近" -> " AND start_at >= NOW() AND start_at <= CURDATE() + INTERVAL 7 DAY";
      default -> " AND DATE(start_at) = CURDATE()";
    };
    List<String> lines = new ArrayList<>();
    String query = "SELECT title, start_at FROM schedule_items WHERE workspace_id=? AND deleted_at IS NULL AND status<>'done' "
        + scope + " ORDER BY start_at LIMIT 15";
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(query)) {
      p.setBytes(1, Database.uuidBytes(context.workspaceId()));
      try (ResultSet rs = p.executeQuery()) {
        while (rs.next()) {
          LocalDateTime start = rs.getTimestamp("start_at").toLocalDateTime();
          lines.add(start.format(HM) + " " + rs.getString("title"));
        }
      }
    }
    if (lines.isEmpty()) return (when == null ? "今天" : when) + "没有日程安排。";
    return (when == null ? "今天" : when) + "的日程：\n- " + String.join("\n- ", lines);
  }

  private String viewTasks(Database.Context context, String when) throws SQLException {
    String scope = taskScopeSql(when);
    List<String> lines = new ArrayList<>();
    String query = "SELECT t.title, t.due_at, p.title AS plan_title FROM plan_tasks t JOIN plans p ON p.id=t.plan_id "
        + "WHERE p.workspace_id=? AND t.deleted_at IS NULL AND p.deleted_at IS NULL "
        + "AND t.status IN ('pending','in_progress') " + scope + " ORDER BY t.due_at, t.updated_at DESC LIMIT 15";
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(query)) {
      p.setBytes(1, Database.uuidBytes(context.workspaceId()));
      try (ResultSet rs = p.executeQuery()) {
        while (rs.next()) {
          Timestamp due = rs.getTimestamp("due_at");
          String whenText = due == null ? "" : "（" + due.toLocalDateTime().format(MD_HM) + "）";
          lines.add("[" + rs.getString("plan_title") + "] " + rs.getString("title") + whenText);
        }
      }
    }
    if (lines.isEmpty()) return "没有找到相关任务。";
    return "任务（" + (when == null || when.isEmpty() ? "全部" : when) + "）：\n- " + String.join("\n- ", lines);
  }

  private String viewLearningGoals(Database.Context context) throws SQLException {
    List<String> lines = new ArrayList<>();
    for (LearningService.LearningGoal goal : learning.listGoals(context)) {
      if (!"active".equals(goal.status())) continue;
      lines.add(goal.title() + "（进度 " + Math.round(goal.progress()) + "%）");
    }
    if (lines.isEmpty()) return "还没有进行中的学习目标。";
    return "学习目标：\n- " + String.join("\n- ", lines);
  }

  private String viewReview(Database.Context context) throws SQLException {
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "SELECT ai_summary FROM review_entries WHERE workspace_id=? AND user_id=? AND review_date=CURDATE() "
            + "AND ai_summary IS NOT NULL ORDER BY updated_at DESC LIMIT 1")) {
      p.setBytes(1, Database.uuidBytes(context.workspaceId())); p.setBytes(2, Database.uuidBytes(context.userId()));
      try (ResultSet rs = p.executeQuery()) {
        if (rs.next()) {
          String summary = rs.getString("ai_summary").trim();
          if (!summary.isEmpty()) {
            return "今日复盘：\n" + (summary.length() > 500 ? summary.substring(0, 497) + "..." : summary);
          }
        }
      }
    }
    return reviewStats(context);
  }

  /** 复盘兜底：纯 SQL 统计今天完成数 + 待处理数，不依赖模型。 */
  private String reviewStats(Database.Context context) throws SQLException {
    int doneTodos = 0, doneSchedules = 0, doneTasks = 0, overdueTodos = 0, blockedTasks = 0;
    try (Connection c = database.connection()) {
      try (PreparedStatement p = c.prepareStatement(
          "SELECT COUNT(*) FROM todos WHERE workspace_id=? AND created_by=? AND status='done' AND DATE(completed_at)=CURDATE()")) {
        p.setBytes(1, Database.uuidBytes(context.workspaceId())); p.setBytes(2, Database.uuidBytes(context.userId()));
        try (ResultSet rs = p.executeQuery()) { if (rs.next()) doneTodos = rs.getInt(1); }
      }
      try (PreparedStatement p = c.prepareStatement(
          "SELECT COUNT(*) FROM schedule_items WHERE workspace_id=? AND status='done' AND DATE(completed_at)=CURDATE()")) {
        p.setBytes(1, Database.uuidBytes(context.workspaceId()));
        try (ResultSet rs = p.executeQuery()) { if (rs.next()) doneSchedules = rs.getInt(1); }
      }
      try (PreparedStatement p = c.prepareStatement(
          "SELECT COUNT(*) FROM plan_tasks t JOIN plans p ON p.id=t.plan_id WHERE p.workspace_id=? AND t.status='done' AND DATE(t.completed_at)=CURDATE()")) {
        p.setBytes(1, Database.uuidBytes(context.workspaceId()));
        try (ResultSet rs = p.executeQuery()) { if (rs.next()) doneTasks = rs.getInt(1); }
      }
      try (PreparedStatement p = c.prepareStatement(
          "SELECT COUNT(*) FROM todos WHERE workspace_id=? AND created_by=? AND status<>'done' AND deleted_at IS NULL "
              + "AND due_at IS NOT NULL AND due_at < NOW()")) {
        p.setBytes(1, Database.uuidBytes(context.workspaceId())); p.setBytes(2, Database.uuidBytes(context.userId()));
        try (ResultSet rs = p.executeQuery()) { if (rs.next()) overdueTodos = rs.getInt(1); }
      }
      try (PreparedStatement p = c.prepareStatement(
          "SELECT COUNT(*) FROM plan_tasks t JOIN plans p ON p.id=t.plan_id WHERE p.workspace_id=? AND t.status='blocked' AND t.deleted_at IS NULL AND p.deleted_at IS NULL")) {
        p.setBytes(1, Database.uuidBytes(context.workspaceId()));
        try (ResultSet rs = p.executeQuery()) { if (rs.next()) blockedTasks = rs.getInt(1); }
      }
    }
    int totalDone = doneTodos + doneSchedules + doneTasks;
    if (totalDone == 0 && overdueTodos == 0 && blockedTasks == 0) {
      return "今天还没有完成记录，休息一下，明天继续。";
    }
    StringBuilder sb = new StringBuilder("今日复盘（统计版）：\n");
    if (totalDone > 0) sb.append("- 今天完成：待办 ").append(doneTodos).append(" 个、日程 ").append(doneSchedules).append(" 个、任务 ").append(doneTasks).append(" 个\n");
    else sb.append("- 今天还没有完成记录\n");
    if (overdueTodos > 0) sb.append("- 逾期待办 ").append(overdueTodos).append(" 个\n");
    if (blockedTasks > 0) sb.append("- 受阻任务 ").append(blockedTasks).append(" 个\n");
    return sb.toString().trim();
  }

  private String viewDiet(Database.Context context) throws SQLException {
    List<String> lines = new ArrayList<>();
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "SELECT title, progress, status FROM plans WHERE workspace_id=? AND title LIKE '%饮食%' AND deleted_at IS NULL "
            + "ORDER BY updated_at DESC LIMIT 5")) {
      p.setBytes(1, Database.uuidBytes(context.workspaceId()));
      try (ResultSet rs = p.executeQuery()) {
        while (rs.next()) {
          String label = switch (rs.getString("status")) {
            case "active" -> "进行中，进度 " + Math.round(rs.getDouble("progress")) + "%";
            case "paused" -> "已暂停";
            default -> "已完成";
          };
          lines.add(rs.getString("title") + "（" + label + "）");
        }
      }
    }
    if (lines.isEmpty()) return "还没有饮食计划。";
    return "饮食计划：\n- " + String.join("\n- ", lines);
  }

  private String viewReminders(Database.Context context) throws SQLException {
    List<String> lines = new ArrayList<>();
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "SELECT payload, scheduled_at FROM notification_outbox WHERE user_id=? AND channel='wechat' AND status='pending' "
            + "AND scheduled_at > NOW() ORDER BY scheduled_at LIMIT 10")) {
      p.setBytes(1, Database.uuidBytes(context.userId()));
      try (ResultSet rs = p.executeQuery()) {
        while (rs.next()) {
          String when = rs.getTimestamp("scheduled_at").toLocalDateTime().format(MD_HM);
          JsonObject payload = JsonParser.parseString(rs.getString("payload")).getAsJsonObject();
          String title = payload.has("title") ? payload.get("title").getAsString() : "未知事项";
          String label = switch (payload.has("type") ? payload.get("type").getAsString() : "todo_reminder") {
            case "task_reminder" -> "任务";
            case "schedule_reminder" -> "日程";
            default -> "待办";
          };
          lines.add(when + " " + title + "（" + label + "）");
        }
      }
    }
    if (lines.isEmpty()) return "接下来没有待触发的提醒。";
    return "接下来的提醒：\n- " + String.join("\n- ", lines);
  }

  private String viewAll(Database.Context context) throws SQLException {
    int activePlans = 0, openTodos = 0, todaySchedules = 0, openTasks = 0;
    try (Connection c = database.connection()) {
      try (PreparedStatement p = c.prepareStatement("SELECT COUNT(*) FROM plans WHERE workspace_id=? AND status='active' AND deleted_at IS NULL")) {
        p.setBytes(1, Database.uuidBytes(context.workspaceId()));
        try (ResultSet rs = p.executeQuery()) { if (rs.next()) activePlans = rs.getInt(1); }
      }
      try (PreparedStatement p = c.prepareStatement("SELECT COUNT(*) FROM todos WHERE workspace_id=? AND created_by=? AND status<>'done' AND deleted_at IS NULL")) {
        p.setBytes(1, Database.uuidBytes(context.workspaceId())); p.setBytes(2, Database.uuidBytes(context.userId()));
        try (ResultSet rs = p.executeQuery()) { if (rs.next()) openTodos = rs.getInt(1); }
      }
      try (PreparedStatement p = c.prepareStatement("SELECT COUNT(*) FROM schedule_items WHERE workspace_id=? AND DATE(start_at)=CURDATE() AND status<>'done' AND deleted_at IS NULL")) {
        p.setBytes(1, Database.uuidBytes(context.workspaceId()));
        try (ResultSet rs = p.executeQuery()) { if (rs.next()) todaySchedules = rs.getInt(1); }
      }
      try (PreparedStatement p = c.prepareStatement(
          "SELECT COUNT(*) FROM plan_tasks t JOIN plans p ON p.id=t.plan_id WHERE p.workspace_id=? AND t.status IN ('pending','in_progress') AND t.deleted_at IS NULL AND p.deleted_at IS NULL")) {
        p.setBytes(1, Database.uuidBytes(context.workspaceId()));
        try (ResultSet rs = p.executeQuery()) { if (rs.next()) openTasks = rs.getInt(1); }
      }
    }
    return "当前概览：\n- 进行中计划 " + activePlans + " 个\n- 未完成待办 " + openTodos + " 个\n- 今天日程 " + todaySchedules + " 个\n- 未完成任务 " + openTasks + " 个";
  }

  // ==================== 精确只读句（优先级 1）====================

  private String todayOpenItems(Database.Context context) throws SQLException {
    List<String> schedules = new ArrayList<>();
    List<String> todos = new ArrayList<>();
    List<String> tasks = new ArrayList<>();
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "SELECT title FROM schedule_items WHERE workspace_id = ? AND DATE(start_at) = CURDATE() AND status <> 'done' AND deleted_at IS NULL ORDER BY start_at LIMIT 10")) {
      p.setBytes(1, Database.uuidBytes(context.workspaceId()));
      try (ResultSet rs = p.executeQuery()) { while (rs.next()) schedules.add("日程：" + rs.getString(1)); }
    }
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "SELECT title FROM todos WHERE workspace_id = ? AND created_by = ? AND (due_at IS NULL OR DATE(due_at) = CURDATE()) AND status <> 'done' AND deleted_at IS NULL ORDER BY due_at LIMIT 10")) {
      p.setBytes(1, Database.uuidBytes(context.workspaceId())); p.setBytes(2, Database.uuidBytes(context.userId()));
      try (ResultSet rs = p.executeQuery()) { while (rs.next()) todos.add("待办：" + rs.getString(1)); }
    }
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "SELECT t.title FROM plan_tasks t JOIN plans p ON p.id=t.plan_id WHERE p.workspace_id=? "
            + "AND (t.due_at IS NULL OR DATE(t.due_at)=CURDATE()) AND t.status IN ('pending','in_progress') "
            + "AND t.deleted_at IS NULL AND p.deleted_at IS NULL ORDER BY t.due_at LIMIT 10")) {
      p.setBytes(1, Database.uuidBytes(context.workspaceId()));
      try (ResultSet rs = p.executeQuery()) { while (rs.next()) tasks.add("任务：" + rs.getString(1)); }
    }
    List<String> all = new ArrayList<>();
    all.addAll(schedules); all.addAll(todos); all.addAll(tasks);
    if (all.isEmpty()) return "今天没有未完成的日程、待办或任务，按自己的节奏休息一下。";
    return "今天还有：\n- " + String.join("\n- ", all);
  }

  private String progressSummary(Database.Context context) throws SQLException {
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "SELECT COUNT(*), COALESCE(AVG(progress), 0) FROM plans WHERE workspace_id = ? AND status = 'active' AND deleted_at IS NULL")) {
      p.setBytes(1, Database.uuidBytes(context.workspaceId()));
      try (ResultSet rs = p.executeQuery()) {
        if (!rs.next()) return "暂时还没有长期计划。";
        return "当前有 " + rs.getInt(1) + " 个进行中的长期计划，平均完成度 " + Math.round(rs.getDouble(2)) + "%。";
      }
    }
  }

  // ==================== 通用查询 / 写入 ====================

  /** 按标题模糊反查，支持 4 表；onlyTable 非空时只查指定表（用于任务目标计划解析）。 */
  private ItemRef findByTitle(Database.Context context, String title, String onlyTable) throws SQLException {
    String[] tables = {"todos", "schedule_items", "plans", "plan_tasks"};
    for (String table : tables) {
      if (onlyTable != null && !table.equals(onlyTable)) continue;
      if (table.equals("plan_tasks")) {
        try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
            "SELECT t.id, t.title, t.version FROM plan_tasks t JOIN plans p ON p.id=t.plan_id "
                + "WHERE p.workspace_id=? AND t.deleted_at IS NULL AND p.deleted_at IS NULL AND t.title LIKE ? ORDER BY t.updated_at DESC LIMIT 1")) {
          p.setBytes(1, Database.uuidBytes(context.workspaceId())); p.setString(2, "%" + title + "%");
          try (ResultSet rs = p.executeQuery()) {
            if (rs.next()) return new ItemRef(table, Database.bytesUuid(rs.getBytes("id")), rs.getString("title"), rs.getInt("version"));
          }
        }
      } else {
        try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
            "SELECT id, title, version FROM " + table + " WHERE workspace_id=? AND deleted_at IS NULL AND title LIKE ? ORDER BY updated_at DESC LIMIT 1")) {
          p.setBytes(1, Database.uuidBytes(context.workspaceId())); p.setString(2, "%" + title + "%");
          try (ResultSet rs = p.executeQuery()) {
            if (rs.next()) return new ItemRef(table, Database.bytesUuid(rs.getBytes("id")), rs.getString("title"), rs.getInt("version"));
          }
        }
      }
    }
    return null;
  }

  /** 任务目标计划解析：优先"到<计划名>"，否则取最近更新的进行中计划。 */
  private PlanTarget resolvePlanTarget(Database.Context context, String tail) throws SQLException {
    Matcher to = TARGET_PLAN.matcher(tail);
    if (to.matches()) {
      ItemRef plan = findByTitle(context, to.group(2), "plans");
      if (plan != null) return new PlanTarget(plan.id(), plan.title(), to.group(3));
    }
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "SELECT id, title FROM plans WHERE workspace_id=? AND status='active' AND deleted_at IS NULL ORDER BY updated_at DESC LIMIT 1")) {
      p.setBytes(1, Database.uuidBytes(context.workspaceId()));
      try (ResultSet rs = p.executeQuery()) {
        if (rs.next()) return new PlanTarget(Database.bytesUuid(rs.getBytes("id")), rs.getString("title"), tail);
      }
    }
    return null;
  }

  private UUID firstStage(Database.Context context, UUID planId) throws SQLException {
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "SELECT id FROM plan_stages WHERE plan_id=? AND deleted_at IS NULL ORDER BY sort_order, created_at LIMIT 1")) {
      p.setBytes(1, Database.uuidBytes(planId));
      try (ResultSet rs = p.executeQuery()) { return rs.next() ? Database.bytesUuid(rs.getBytes("id")) : null; }
    }
  }

  private void insertTodo(Database.Context context, UUID id, String title, LocalDateTime dueAt, Integer reminderMinutes) throws SQLException {
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "INSERT INTO todos (id, workspace_id, created_by, title, due_at, reminder_minutes) VALUES (?, ?, ?, ?, ?, ?)")) {
      p.setBytes(1, Database.uuidBytes(id)); p.setBytes(2, Database.uuidBytes(context.workspaceId()));
      p.setBytes(3, Database.uuidBytes(context.userId())); p.setString(4, title);
      p.setObject(5, dueAt == null ? null : Timestamp.valueOf(dueAt)); p.setObject(6, reminderMinutes);
      p.executeUpdate();
    }
  }

  private void insertPlan(Database.Context context, UUID id, String title) throws SQLException {
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "INSERT INTO plans (id, workspace_id, owner_id, title, description, color) VALUES (?, ?, ?, ?, ?, ?)")) {
      p.setBytes(1, Database.uuidBytes(id)); p.setBytes(2, Database.uuidBytes(context.workspaceId()));
      p.setBytes(3, Database.uuidBytes(context.userId())); p.setString(4, title);
      p.setString(5, "微信命令创建"); p.setString(6, "#D39A24"); p.executeUpdate();
    }
  }

  // ==================== 时间解析 ====================

  /**
   * 从命令文本中抽离相对日期/时刻，返回 When。kind 决定默认值：
   * todo: 日期缺失且有时刻 → 今天；只给日期 → 当天 23:00（调用方处理）。
   * remind: 无时刻 → now+1h（reminderMinutes=0）；有时刻 → 该时刻（reminderMinutes=30）。
   * schedule: 日期缺失 → 今天；无时刻 → 09:00（调用方处理）。
   * task: 只有给具体时刻才写 due_at（调用方处理）。
   */
  static When parseWhen(String raw, String kind) {
    String text = raw == null ? "" : raw.trim();
    LocalDate date = null;
    LocalTime time = null;
    String dateText = null;
    String timeText = null;

    Matcher dm = DATE_TOKEN.matcher(text);
    if (dm.find()) {
      dateText = dm.group();
      text = text.substring(0, dm.start()) + " " + text.substring(dm.end());
    }
    Matcher tm = TIME_TOKEN.matcher(text);
    if (tm.find()) {
      timeText = tm.group().trim();
      text = text.substring(0, tm.start()) + " " + text.substring(tm.end());
    }

    if (dateText != null) date = resolveDate(dateText);
    if (timeText != null) time = resolveTime(timeText);

    LocalDate today = LocalDate.now();
    Integer reminderMinutes = null;
    if ("remind".equals(kind)) {
      reminderMinutes = time != null ? 30 : 0;
      if (date == null) date = today;
    } else {
      if (date == null && (time != null || "schedule".equals(kind))) date = today;
    }
    String title = text.replaceAll("\\s+", " ").trim();
    return new When(date, time, reminderMinutes, title);
  }

  private static LocalDate resolveDate(String token) {
    LocalDate today = LocalDate.now();
    if (token.equals("今天")) return today;
    if (token.equals("明天")) return today.plusDays(1);
    if (token.equals("后天")) return today.plusDays(2);
    if (token.startsWith("周") || token.startsWith("礼拜")) {
      int target = dayOfWeek(token.charAt(token.length() - 1));
      int diff = target - today.getDayOfWeek().getValue();
      if (diff <= 0) diff += 7;
      return today.plusDays(diff);
    }
    Matcher monthDay = Pattern.compile("(\\d{1,2})月(\\d{1,2})[日号]").matcher(token);
    if (monthDay.matches()) {
      int month = Integer.parseInt(monthDay.group(1));
      int day = Integer.parseInt(monthDay.group(2));
      LocalDate candidate = LocalDate.of(today.getYear(), month, day);
      if (candidate.isBefore(today)) candidate = candidate.plusYears(1);
      return candidate;
    }
    Matcher day = Pattern.compile("(\\d{1,2})号").matcher(token);
    if (day.matches()) {
      int value = Integer.parseInt(day.group(1));
      LocalDate candidate = LocalDate.of(today.getYear(), today.getMonthValue(), value);
      if (candidate.isBefore(today)) candidate = candidate.plusMonths(1);
      return candidate;
    }
    return today;
  }

  private static int dayOfWeek(char c) {
    return switch (c) {
      case '一' -> 1;
      case '二' -> 2;
      case '三' -> 3;
      case '四' -> 4;
      case '五' -> 5;
      case '六' -> 6;
      case '日', '天' -> 7;
      default -> 0;
    };
  }

  private static LocalTime resolveTime(String token) {
    String period = null;
    int hour;
    int minute;
    Matcher half = Pattern.compile("(凌晨|早晨|早上|上午|中午|下午|傍晚|晚上|今晚|夜里|深夜|晚间)?\\s*(\\d{1,2})点半").matcher(token);
    if (half.matches()) {
      period = half.group(1);
      hour = Integer.parseInt(half.group(2));
      minute = 30;
    } else {
      Matcher full = Pattern.compile("(凌晨|早晨|早上|上午|中午|下午|傍晚|晚上|今晚|夜里|深夜|晚间)?\\s*(\\d{1,2})[:：点时](\\d{1,2})?分?").matcher(token);
      if (!full.matches()) return null;
      period = full.group(1);
      hour = Integer.parseInt(full.group(2));
      String mins = full.group(3);
      minute = mins == null ? 0 : Integer.parseInt(mins);
    }
    if (hour < 12 && period != null
        && (period.contains("下午") || period.contains("晚上") || period.contains("今晚")
            || period.contains("傍晚") || period.contains("夜里") || period.contains("深夜")
            || period.contains("晚间"))) {
      hour += 12;
    }
    if (hour >= 24) hour %= 24;
    if (minute >= 60) minute = 59;
    return LocalTime.of(hour, minute);
  }

  private static String dueSuffix(When when) {
    if (when.date() == null) return "";
    LocalDate today = LocalDate.now();
    String datePart;
    if (when.date().equals(today)) datePart = "今天";
    else if (when.date().equals(today.plusDays(1))) datePart = "明天";
    else datePart = when.date().toString();
    if (when.time() == null) return "（" + datePart + " 23:00 前完成）";
    return "（" + datePart + " " + when.time().format(HM) + "）";
  }

  private static String dueScopeSql(String when) {
    return switch (when == null ? "" : when) {
      case "明天" -> " AND (due_at IS NOT NULL AND DATE(due_at) = CURDATE() + INTERVAL 1 DAY)";
      case "后天" -> " AND (due_at IS NOT NULL AND DATE(due_at) = CURDATE() + INTERVAL 2 DAY)";
      case "本周" -> " AND (due_at IS NULL OR YEARWEEK(due_at, 1) = YEARWEEK(CURDATE(), 1))";
      case "最近" -> " AND (due_at IS NOT NULL AND due_at <= CURDATE() + INTERVAL 7 DAY)";
      case "今天" -> " AND (due_at IS NULL OR DATE(due_at) = CURDATE())";
      default -> "";
    };
  }

  private static String taskScopeSql(String when) {
    return switch (when == null ? "" : when) {
      case "明天" -> " AND DATE(t.due_at) = CURDATE() + INTERVAL 1 DAY";
      case "后天" -> " AND DATE(t.due_at) = CURDATE() + INTERVAL 2 DAY";
      case "本周" -> " AND YEARWEEK(t.due_at, 1) = YEARWEEK(CURDATE(), 1)";
      case "最近" -> " AND t.due_at IS NOT NULL AND t.due_at <= CURDATE() + INTERVAL 7 DAY";
      case "今天" -> " AND DATE(t.due_at) = CURDATE()";
      default -> "";
    };
  }

  private static String friendlyMessage(Exception error) {
    String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    if (message.startsWith("schedule_conflict:")) {
      return "该时间段已有日程“" + message.substring("schedule_conflict:".length()) + "”，换个时间吧。";
    }
    if (message.contains("_conflict")) return "数据有变化，请重试一次。";
    return message;
  }
}
