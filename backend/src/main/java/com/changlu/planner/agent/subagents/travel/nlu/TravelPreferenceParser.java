package com.changlu.planner.agent.subagents.travel.nlu;

import com.google.gson.JsonObject;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TravelPreferenceParser {
  private static final Pattern STAR = Pattern.compile("([一二三四五1-5])星(?:级)?酒店");
  public void apply(String text, JsonObject arguments) {
    if (contains(text, "海边", "沙滩", "海滨")) arguments.addProperty("beachPreference", true);
    if (contains(text, "带父母", "老人", "老年人")) arguments.addProperty("elderlyTravel", true);
    if (contains(text, "不早起", "不要早起", "睡到自然醒")) arguments.addProperty("avoidEarlyMorning", true);
    if (contains(text, "不要太累", "轻松", "休闲", "带父母", "老人")) arguments.addProperty("pace", "relaxed");
    if (text.contains("高铁")) arguments.addProperty("preferredTransport", "highSpeedRail");
    else if (text.contains("飞机") || text.contains("航班")) arguments.addProperty("preferredTransport", "flight");
    else if (text.contains("自驾")) arguments.addProperty("preferredTransport", "selfDrive");
    else if (text.contains("公交") || text.contains("公共交通")) arguments.addProperty("preferredTransport", "publicTransit");
    Matcher matcher = STAR.matcher(text);
    if (matcher.find()) arguments.addProperty("hotelStarRating", digit(matcher.group(1).charAt(0)));
  }
  private boolean contains(String text, String... values) { for (String value : values) if (text.contains(value)) return true; return false; }
  private int digit(char value) { return "一二三四五".indexOf(value) + 1 > 0 ? "一二三四五".indexOf(value) + 1 : value - '0'; }
}
