package com.changlu.planner.agent.subagents.travel.nlu;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ChineseDurationParser {
  private static final Pattern VALUE = Pattern.compile("([0-9]{1,3}|[一二两三四五六七八九十百零〇]+)\\s*(天|日|周|星期)");
  private static final Map<Character, Integer> DIGITS = Map.ofEntries(
      Map.entry('零', 0), Map.entry('〇', 0), Map.entry('一', 1), Map.entry('二', 2), Map.entry('两', 2),
      Map.entry('三', 3), Map.entry('四', 4), Map.entry('五', 5), Map.entry('六', 6), Map.entry('七', 7),
      Map.entry('八', 8), Map.entry('九', 9));
  public int parseDays(String text) {
    Matcher matcher = VALUE.matcher(text);
    if (!matcher.find()) return 0;
    int value = matcher.group(1).matches("\\d+") ? Integer.parseInt(matcher.group(1)) : chinese(matcher.group(1));
    return matcher.group(2).equals("周") || matcher.group(2).equals("星期") ? value * 7 : value;
  }
  static int chinese(String value) {
    if (value.equals("十")) return 10;
    int ten = value.indexOf('十');
    if (ten >= 0) {
      int left = ten == 0 ? 1 : DIGITS.getOrDefault(value.charAt(ten - 1), 0);
      int right = ten == value.length() - 1 ? 0 : DIGITS.getOrDefault(value.charAt(ten + 1), 0);
      return left * 10 + right;
    }
    int result = 0;
    for (char c : value.toCharArray()) result = result * 10 + DIGITS.getOrDefault(c, 0);
    return result;
  }
}
