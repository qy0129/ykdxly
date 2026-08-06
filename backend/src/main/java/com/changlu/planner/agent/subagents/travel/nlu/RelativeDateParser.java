package com.changlu.planner.agent.subagents.travel.nlu;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

public final class RelativeDateParser {
  private final Clock clock;
  public RelativeDateParser(Clock clock) { this.clock = clock; }
  public LocalDate parse(String text, ZoneId zone) {
    LocalDate today = LocalDate.now(clock.withZone(zone));
    if (text.contains("明天")) return today.plusDays(1);
    if (text.contains("后天")) return today.plusDays(2);
    if (text.contains("今天")) return today;
    if (text.contains("下周一")) return today.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
    return null;
  }
}
