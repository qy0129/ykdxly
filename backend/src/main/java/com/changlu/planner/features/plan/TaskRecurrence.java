package com.changlu.planner.features.plan;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/** 将简单频率转换为具体执行日期，网页、AI 和微信共用同一套规则。 */
public final class TaskRecurrence {
  private static final List<String> SUPPORTED = List.of("once", "daily", "every_other_day", "weekdays", "weekly");

  private TaskRecurrence() {}

  public static List<LocalDate> dates(String type, LocalDate start, LocalDate end) {
    if (!SUPPORTED.contains(type)) throw new IllegalArgumentException("不支持的任务频率");
    if (start == null) throw new IllegalArgumentException("请选择开始日期");
    LocalDate last = "once".equals(type) ? start : end;
    if (last == null) throw new IllegalArgumentException("请选择结束日期");
    if (last.isBefore(start)) throw new IllegalArgumentException("结束日期不能早于开始日期");
    if (ChronoUnit.DAYS.between(start, last) > 730) throw new IllegalArgumentException("重复任务最长可安排两年");

    List<LocalDate> result = new ArrayList<>();
    for (LocalDate date = start; !date.isAfter(last); date = date.plusDays(1)) {
      long offset = ChronoUnit.DAYS.between(start, date);
      boolean included = switch (type) {
        case "once" -> offset == 0;
        case "daily" -> true;
        case "every_other_day" -> offset % 2 == 0;
        case "weekdays" -> date.getDayOfWeek() != DayOfWeek.SATURDAY
            && date.getDayOfWeek() != DayOfWeek.SUNDAY;
        case "weekly" -> offset % 7 == 0;
        default -> false;
      };
      if (included) result.add(date);
    }
    if (result.isEmpty()) throw new IllegalArgumentException("所选日期范围内没有可执行日期");
    return result;
  }
}
