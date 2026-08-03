package com.changlu.planner.features.briefing;

import com.changlu.planner.shared.database.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/** Independent sub-agent that composes planning data, weather and news into one briefing. */
public final class BriefingSubAgent {
  public record Result(String message, int plans, long progress, int pendingTodos, int overdueTodos, String tone) {}

  private final Database database;
  private final WeatherTool weatherTool = new WeatherTool();
  private final NewsTool newsTool = new NewsTool();

  public BriefingSubAgent(Database database) { this.database = database; }

  public Result build(String externalUserId) throws SQLException {
    Database.Context context = database.contextForExternalUser(externalUserId);
    List<PlanRow> plans = loadPlans(context);
    List<ScheduleRow> schedules = loadSchedules(context);
    List<TodoRow> todos = loadTodos(context);
    int overdueTodos = (int) todos.stream().filter(TodoRow::overdue).count();
    long progress = Math.round(plans.stream().mapToDouble(PlanRow::progress).average().orElse(0));
    String tone = progress >= 75 ? "positive" : overdueTodos > 0 || todos.size() > 4 ? "gentle" : "steady";

    CompletableFuture<String> weather = CompletableFuture.supplyAsync(weatherTool::current);
    CompletableFuture<List<NewsTool.News>> news = CompletableFuture.supplyAsync(
        () -> newsTool.planNews(plans.stream().map(PlanRow::title).toList()));

    LocalDate today = LocalDate.now();
    StringBuilder text = new StringBuilder(greeting()).append("，欢迎回来。\n今天是")
        .append(today.format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA))).append("。\n");
    String weatherText = weather.join();
    if (!weatherText.isBlank()) text.append("\n天气：\n").append(weatherText).append('\n');

    text.append("\n今日计划：\n");
    if (plans.isEmpty()) text.append("- 暂时没有进行中的长期计划\n");
    else for (PlanRow plan : plans) text.append("- ").append(plan.title()).append("（")
        .append(Math.round(plan.progress())).append("%）\n");

    text.append("\n日程：\n");
    if (schedules.isEmpty()) text.append("- 今天没有日程安排\n");
    else for (ScheduleRow item : schedules) text.append("- ").append(item.time()).append(' ')
        .append(item.title()).append("（").append(item.status().equals("done") ? "已完成" : Math.round(item.progress()) + "%")
        .append("）\n");

    text.append("\n待办：\n");
    if (todos.isEmpty()) text.append("- 当前没有未完成待办\n");
    else for (TodoRow item : todos) text.append("- ").append(item.title())
        .append(item.due().isBlank() ? "（未安排）" : "（" + item.due() + (item.overdue() ? "，已逾期" : "") + "）").append('\n');

    List<NewsTool.News> newsItems = news.join();
    text.append("\n计划相关新闻：\n");
    if (newsItems.isEmpty()) text.append("- 新闻搜索暂时没有返回结果\n");
    else for (int index = 0; index < newsItems.size(); index++) {
      NewsTool.News item = newsItems.get(index);
      text.append(index + 1).append(". ").append(item.title()).append('\n');
      if (!item.summary().isBlank()) text.append("   ").append(item.summary()).append('\n');
      text.append("   来源：").append(item.source()).append('\n').append("   ").append(item.url()).append('\n');
    }

    text.append("\n").append(emotionalReminder(tone, todos.size(), overdueTodos));
    return new Result(text.toString().trim(), plans.size(), progress, todos.size(), overdueTodos, tone);
  }

  private List<PlanRow> loadPlans(Database.Context context) throws SQLException {
    String sql = "SELECT title, progress FROM plans WHERE workspace_id = ? AND status = 'active' ORDER BY progress DESC LIMIT 5";
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(sql)) {
      p.setBytes(1, Database.uuidBytes(context.workspaceId()));
      try (ResultSet rs = p.executeQuery()) { List<PlanRow> rows = new ArrayList<>(); while (rs.next()) rows.add(new PlanRow(rs.getString(1), rs.getDouble(2))); return rows; }
    }
  }

  private List<ScheduleRow> loadSchedules(Database.Context context) throws SQLException {
    String sql = "SELECT TIME_FORMAT(start_at, '%H:%i'), title, status, progress FROM schedule_items WHERE workspace_id = ? AND DATE(start_at) = CURDATE() ORDER BY start_at LIMIT 10";
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(sql)) {
      p.setBytes(1, Database.uuidBytes(context.workspaceId()));
      try (ResultSet rs = p.executeQuery()) { List<ScheduleRow> rows = new ArrayList<>(); while (rs.next()) rows.add(new ScheduleRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getDouble(4))); return rows; }
    }
  }

  private List<TodoRow> loadTodos(Database.Context context) throws SQLException {
    String sql = "SELECT title, due_at FROM todos WHERE workspace_id = ? AND status <> 'done' ORDER BY due_at IS NULL, due_at LIMIT 10";
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(sql)) {
      p.setBytes(1, Database.uuidBytes(context.workspaceId()));
      try (ResultSet rs = p.executeQuery()) {
        List<TodoRow> rows = new ArrayList<>();
        while (rs.next()) {
          LocalDateTime due = rs.getTimestamp(2) == null ? null : rs.getTimestamp(2).toLocalDateTime();
          rows.add(new TodoRow(rs.getString(1), due == null ? "" : due.format(DateTimeFormatter.ofPattern("M月d日 HH:mm")), due != null && due.isBefore(LocalDateTime.now())));
        }
        return rows;
      }
    }
  }

  private String greeting() {
    int hour = LocalDateTime.now().getHour();
    return hour < 6 ? "夜深了" : hour < 12 ? "早上好" : hour < 18 ? "下午好" : "晚上好";
  }

  private String emotionalReminder(String tone, int pending, int overdue) {
    return switch (tone) {
      case "positive" -> "你最近推进得很稳，今天继续守住这个节奏就够了，我会陪你把剩下的事情一件件完成。";
      case "gentle" -> "还有 " + pending + " 项待办没有完成" + (overdue > 0 ? "，其中 " + overdue + " 项已经逾期" : "") + "。先挑最重要的一件开始，不用一次把所有压力都扛下来。";
      default -> "今天只要向最重要的计划推进一小步就很好，完成比把日程塞满更有价值。";
    };
  }

  private record PlanRow(String title, double progress) {}
  private record ScheduleRow(String time, String title, String status, double progress) {}
  private record TodoRow(String title, String due, boolean overdue) {}
}
