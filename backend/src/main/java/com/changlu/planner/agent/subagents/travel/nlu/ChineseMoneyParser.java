package com.changlu.planner.agent.subagents.travel.nlu;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ChineseMoneyParser {
  private static final Pattern ARABIC = Pattern.compile("(?:预算|费用)?\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(万|千)?\\s*(?:元|块)?");
  private static final Pattern CHINESE_WAN = Pattern.compile("(?:预算|费用)?\\s*([一二两三四五六七八九十]+)万([一二两三四五六七八九])?");
  public BigDecimal parse(String text) {
    Matcher chinese = CHINESE_WAN.matcher(text);
    if (chinese.find()) {
      int wan = ChineseDurationParser.chinese(chinese.group(1));
      int tail = chinese.group(2) == null ? 0 : ChineseDurationParser.chinese(chinese.group(2)) * 1_000;
      return BigDecimal.valueOf(wan * 10_000L + tail);
    }
    Matcher arabic = ARABIC.matcher(text);
    while (arabic.find()) {
      if (!text.substring(Math.max(0, arabic.start() - 2), arabic.start()).contains("预算")
          && arabic.group(2) == null && !arabic.group().contains("元") && !arabic.group().contains("块")) continue;
      BigDecimal amount = new BigDecimal(arabic.group(1));
      if ("万".equals(arabic.group(2))) amount = amount.multiply(BigDecimal.valueOf(10_000));
      if ("千".equals(arabic.group(2))) amount = amount.multiply(BigDecimal.valueOf(1_000));
      return amount;
    }
    return null;
  }
}
