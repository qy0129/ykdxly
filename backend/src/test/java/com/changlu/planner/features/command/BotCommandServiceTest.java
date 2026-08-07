package com.changlu.planner.features.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

/**
 * BotCommandService 解析层纯单测：不依赖数据库。
 * 重点覆盖 parseWhen 的中文相对时间抽离，以及命令回退 AI 的兜底契约。
 */
final class BotCommandServiceTest {

  // ==================== parseWhen：待办 ====================

  @Test void todoWithFullDateAndTime() {
    BotCommandService.When when = BotCommandService.parseWhen("明天下午3点 交作业", "todo");
    assertEquals(LocalDate.now().plusDays(1), when.date());
    assertEquals(LocalTime.of(15, 0), when.time());
    assertEquals("交作业", when.title());
    assertNull(when.reminderMinutes());
  }

  @Test void todoWithDateOnlyKeepsTitle() {
    BotCommandService.When when = BotCommandService.parseWhen("明天 交作业", "todo");
    assertEquals(LocalDate.now().plusDays(1), when.date());
    assertNull(when.time());
    assertEquals("交作业", when.title());
  }

  @Test void todoWithoutTimeHasNullDate() {
    BotCommandService.When when = BotCommandService.parseWhen("买菜", "todo");
    assertNull(when.date());
    assertNull(when.time());
    assertEquals("买菜", when.title());
  }

  @Test void todoEveningTimeDefaultsToToday() {
    BotCommandService.When when = BotCommandService.parseWhen("晚上8点 跑步", "todo");
    assertEquals(LocalDate.now(), when.date());
    assertEquals(LocalTime.of(20, 0), when.time());
    assertEquals("跑步", when.title());
  }

  @Test void todoHalfHourParsing() {
    BotCommandService.When when = BotCommandService.parseWhen("9点半 开会", "todo");
    assertEquals(LocalDate.now(), when.date());
    assertEquals(LocalTime.of(9, 30), when.time());
    assertEquals("开会", when.title());
  }

  @Test void todoAfternoonHalfHourAddsTwelve() {
    BotCommandService.When when = BotCommandService.parseWhen("下午5点半 健身", "todo");
    assertEquals(LocalTime.of(17, 30), when.time());
    assertEquals("健身", when.title());
  }

  @Test void todoExplicitMonthDay() {
    LocalDate today = LocalDate.now();
    LocalDate expected = LocalDate.of(today.getYear(), 10, 1);
    if (expected.isBefore(today)) expected = expected.plusYears(1);
    BotCommandService.When when = BotCommandService.parseWhen("10月1日 旅行计划", "todo");
    assertEquals(expected, when.date());
    assertEquals("旅行计划", when.title());
  }

  @Test void todoWeekdayTargetsNextOccurrence() {
    LocalDate today = LocalDate.now();
    int diff = DayOfWeek.FRIDAY.getValue() - today.getDayOfWeek().getValue();
    if (diff <= 0) diff += 7;
    BotCommandService.When when = BotCommandService.parseWhen("周五 汇报", "todo");
    assertEquals(today.plusDays(diff), when.date());
    assertEquals("汇报", when.title());
  }

  @Test void todoColonTimeParsing() {
    BotCommandService.When when = BotCommandService.parseWhen("14:30 取快递", "todo");
    assertEquals(LocalDate.now(), when.date());
    assertEquals(LocalTime.of(14, 30), when.time());
    assertEquals("取快递", when.title());
  }

  // ==================== parseWhen：提醒我 ====================

  @Test void remindWithoutTimeUsesImmediateWindow() {
    BotCommandService.When when = BotCommandService.parseWhen("喝水", "remind");
    assertEquals(LocalDate.now(), when.date());
    assertNull(when.time());
    assertEquals(0, when.reminderMinutes());
    assertEquals("喝水", when.title());
  }

  @Test void remindWithTimeDefaultsToTodayAndThirtyMinutes() {
    BotCommandService.When when = BotCommandService.parseWhen("下午3点 喝水", "remind");
    assertEquals(LocalDate.now(), when.date());
    assertEquals(LocalTime.of(15, 0), when.time());
    assertEquals(30, when.reminderMinutes());
    assertEquals("喝水", when.title());
  }

  // ==================== parseWhen：日程 ====================

  @Test void scheduleWithoutTimeKeepsTodayOnly() {
    BotCommandService.When when = BotCommandService.parseWhen("团队会议", "schedule");
    assertEquals(LocalDate.now(), when.date());
    assertNull(when.time());
    assertEquals("团队会议", when.title());
  }

  @Test void scheduleWithDateAndTime() {
    BotCommandService.When when = BotCommandService.parseWhen("明天9:30 晨会", "schedule");
    assertEquals(LocalDate.now().plusDays(1), when.date());
    assertEquals(LocalTime.of(9, 30), when.time());
    assertEquals("晨会", when.title());
  }

  // ==================== parseWhen：任务 ====================

  @Test void taskWithoutTimeKeepsBothNull() {
    BotCommandService.When when = BotCommandService.parseWhen("背单词", "task");
    assertNull(when.date());
    assertNull(when.time());
    assertEquals("背单词", when.title());
  }

  @Test void taskWithTimeDefaultsToToday() {
    BotCommandService.When when = BotCommandService.parseWhen("今晚8点 写周报", "task");
    assertEquals(LocalDate.now(), when.date());
    assertEquals(LocalTime.of(20, 0), when.time());
    assertEquals("写周报", when.title());
  }

  // ==================== handle：命令兜底契约 ====================

  @Test void unrecognizedTextFallsThroughToAi() {
    BotCommandService service = new BotCommandService(null, null, null);
    assertFalse(service.handle("今天天气怎么样", null).handled());
    assertFalse(service.handle("你好", null).handled());
    assertFalse(service.handle("帮我写一首诗", null).handled());
  }

  @Test void createWithoutObjectNounDoesNotFire() {
    BotCommandService service = new BotCommandService(null, null, null);
    assertFalse(service.handle("添加待办", null).handled());
    assertFalse(service.handle("提醒我", null).handled());
  }

  @Test void commandResultContract() {
    assertTrue(BotCommandService.CommandResult.ok("x").handled());
    assertEquals("x", BotCommandService.CommandResult.ok("x").message());
    assertFalse(BotCommandService.CommandResult.pass().handled());
    assertNull(BotCommandService.CommandResult.pass().message());
  }
}
