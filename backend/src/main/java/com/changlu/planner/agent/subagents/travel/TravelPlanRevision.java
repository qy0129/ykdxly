package com.changlu.planner.agent.subagents.travel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Applies a narrowly scoped itinerary change without asking the model to regenerate unaffected days. */
final class TravelPlanRevision {
  private static final Pattern DAY = Pattern.compile("\\x{7b2c}\\s*([0-9\\x{4e00}-\\x{4e5d}\\x{4e24}\\x{5341}]+)\\s*\\x{5929}");
  private static final Pattern DATE = Pattern.compile("\\b(20\\d{2}-\\d{2}-\\d{2})\\b");
  private static final Pattern TIME = Pattern.compile("\\b([01]?\\d|2[0-3])[:\\uff1a]([0-5]\\d)\\b");
  private static final Pattern REPLACEMENT = Pattern.compile("(?:\\x{6539}\\x{6210}|\\x{6362}\\x{6210}|\\x{66ff}\\x{6362}|\\x{8c03}\\x{6574}\\x{4e3a})([\\p{IsHan}A-Za-z0-9]{2,20}?)(?=\\x{53ea}\\x{4fee}\\x{6539}|\\x{8bf7}\\x{91cd}\\x{65b0}|[，。！？,:：]|\\d{1,2}[:：]|\\d+\\x{5206}\\x{949f}|$)");
  private static final Pattern DURATION = Pattern.compile("(\\d+)\\s*\\x{5206}\\x{949f}");

  private TravelPlanRevision() {}

  static boolean requiresFullReplan(String message) {
    if (message == null || message.isBlank()) return false;
    return contains(message, "\\u6574\\u4e2a\\u8ba1\\u5212") || contains(message, "\\u5168\\u90e8\\u884c\\u7a0b")
        || contains(message, "\\u91cd\\u65b0\\u89c4\\u5212") || contains(message, "\\u91cd\\u65b0\\u751f\\u6210")
        || contains(message, "\\u91cd\\u505a\\u4e00\\u7248") || contains(message, "\\u6362\\u4e00\\u7248");
  }

  static String attractionQuery(String message) {
    Matcher matcher = REPLACEMENT.matcher(revisionClause(message).replaceAll("\\s+", ""));
    String query = "";
    while (matcher.find()) query = matcher.group(1);
    return query;
  }

  static JsonObject apply(JsonObject previous, String message, JsonArray attractions) {
    if (previous == null || message == null || requiresFullReplan(message)) return null;
    String clause = revisionClause(message);
    if (clause.isBlank()) return null;
    JsonObject revised = previous.deepCopy();
    JsonArray days = array(revised, "days");
    int dayIndex = dayIndex(days, clause);
    if (dayIndex < 0 || dayIndex >= days.size() || !days.get(dayIndex).isJsonObject()) return null;

    JsonObject day = days.get(dayIndex).getAsJsonObject();
    JsonArray activities = array(day, "activities");
    String attractionQuery = attractionQuery(clause);
    JsonObject attraction = matchingAttraction(attractions, attractionQuery);
    String time = time(clause);
    Integer duration = duration(clause);
    if (attraction == null && time == null && duration == null) return null;

    int activityIndex = activityIndex(activities, clause);
    if (activities.isEmpty()) {
      JsonObject created = new JsonObject();
      created.addProperty("durationMinutes", 120);
      activities.add(created);
      activityIndex = 0;
    }
    int targetIndex = Math.max(0, Math.min(activityIndex, activities.size() - 1));
    JsonObject activity = activities.get(targetIndex).getAsJsonObject();
    JsonObject before = activity.deepCopy();
    if (time != null) activity.addProperty("startTime", time);
    if (duration != null) activity.addProperty("durationMinutes", duration);
    if (attraction != null) {
      copy(attraction, activity, "attractionId", "attractionId");
      String name = text(attraction, "name");
      activity.addProperty("attractionName", name);
      activity.addProperty("title", name);
      copy(attraction, activity, "address", "location");
      copy(attraction, activity, "lat", "lat");
      copy(attraction, activity, "lng", "lng");
      copy(attraction, activity, "coordinateSystem", "coordinateSystem");
      copy(attraction, activity, "openingHours", "openingHours");
      copy(attraction, activity, "requiresReservation", "requiresReservation");
      copy(attraction, activity, "sourceUrl", "sourceUrl");
       if (!hasTimeSegment(clause)) day.addProperty("title", name + "\\u6e38\\u89c8");
    }
    day.add("activities", activities);
    revised.addProperty("revisionMode", "localized");
    revised.addProperty("revisionSummary", "\\u5df2\\u6839\\u636e\\u6307\\u5b9a\\u65e5\\u671f\\u6216\\u65f6\\u6bb5\\u66f4\\u65b0\\u884c\\u7a0b\\uff0c\\u5176\\u4ed6\\u5929\\u6570\\u4fdd\\u6301\\u4e0d\\u53d8\\u3002");
    JsonObject diff = new JsonObject();
    diff.addProperty("date", text(day, "date"));
    diff.addProperty("day", dayIndex + 1);
    diff.addProperty("activity", targetIndex + 1);
    diff.add("before", before);
    diff.add("after", activity.deepCopy());
    JsonArray changes = new JsonArray();
    changes.add(diff);
    revised.add("revisionDiff", changes);
    return revised;
  }

  /** Isolates the final requested replacement from copied itinerary text and later keep-as-is clauses. */
  private static String revisionClause(String message) {
    if (message == null || message.isBlank()) return "";
    String compact = message.replaceAll("\\s+", "");
    Matcher replacement = REPLACEMENT.matcher(compact);
    int replacementStart = -1;
    while (replacement.find()) replacementStart = replacement.start();
    if (replacementStart < 0) return message;

    int dayStart = -1;
    Matcher day = DAY.matcher(compact);
    while (day.find()) {
      if (day.start() <= replacementStart) dayStart = day.start();
      else break;
    }
    int intentStart = -1;
    for (String marker : new String[] {"\\u53ea\\u4fee\\u6539", "\\u4fee\\u6539", "\\u8c03\\u6574", "\\u66ff\\u6362"}) {
      int found = compact.lastIndexOf(decodeUnicode(marker), replacementStart);
      if (found > intentStart) intentStart = found;
    }
    int start = Math.max(dayStart, intentStart);
    if (start < 0) start = replacementStart;
    int end = compact.length();
    Matcher followingDay = DAY.matcher(compact);
    while (followingDay.find()) {
      if (followingDay.start() > replacementStart) { end = followingDay.start(); break; }
    }
    return compact.substring(start, end);
  }

  private static int dayIndex(JsonArray days, String message) {
    Matcher matcher = DAY.matcher(message);
    int parsedDay = -1;
    while (matcher.find()) parsedDay = number(matcher.group(1));
    if (parsedDay > 0) return parsedDay - 1;
    Matcher dateMatcher = DATE.matcher(message);
    String date = "";
    while (dateMatcher.find()) date = dateMatcher.group(1);
    if (!date.isBlank()) {
      for (int index = 0; index < days.size(); index++) {
        if (days.get(index).isJsonObject() && date.equals(text(days.get(index).getAsJsonObject(), "date"))) return index;
      }
    }
    return -1;
  }

  private static int activityIndex(JsonArray activities, String message) {
    String segment = contains(message, "\\u4e0a\\u5348") ? "\\u4e0a\\u5348"
        : contains(message, "\\u4e0b\\u5348") ? "\\u4e0b\\u5348"
        : contains(message, "\\u665a\\u4e0a") ? "\\u665a\\u4e0a" : "";
    if (!segment.isBlank()) {
      for (int index = 0; index < activities.size(); index++) {
        if (activities.get(index).isJsonObject() && text(activities.get(index).getAsJsonObject(), "title").contains(segment)) return index;
      }
      for (int index = 0; index < activities.size(); index++) {
        if (!activities.get(index).isJsonObject()) continue;
        String startTime = text(activities.get(index).getAsJsonObject(), "startTime");
        if (("\\u4e0a\\u5348".equals(segment) && startTime.compareTo("12:00") < 0)
            || ("\\u4e0b\\u5348".equals(segment) && startTime.compareTo("12:00") >= 0 && startTime.compareTo("18:00") < 0)
            || ("\\u665a\\u4e0a".equals(segment) && startTime.compareTo("18:00") >= 0)) return index;
      }
      return "\\u4e0b\\u5348".equals(segment) ? Math.min(1, activities.size() - 1)
          : "\\u665a\\u4e0a".equals(segment) ? Math.max(0, activities.size() - 1) : 0;
    }
    return 0;
  }

  private static String time(String message) {
    Matcher matcher = TIME.matcher(message);
    String result = null;
    while (matcher.find()) result = "%02d:%02d".formatted(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
    if (result != null) return result;
    String replacementTail = replacementTail(message);
    if (contains(replacementTail, "\\u4e0a\\u5348")) return "10:00";
    if (contains(replacementTail, "\\u4e0b\\u5348")) return "14:00";
    if (contains(replacementTail, "\\u665a\\u4e0a")) return "18:00";
    if (contains(message, "\\u4e0a\\u5348")) return "10:00";
    if (contains(message, "\\u4e0b\\u5348")) return "14:00";
    if (contains(message, "\\u665a\\u4e0a")) return "18:00";
    return null;
  }

  private static Integer duration(String message) {
    Matcher matcher = DURATION.matcher(message);
    Integer result = null;
    while (matcher.find()) result = Integer.valueOf(matcher.group(1));
    return result;
  }

  private static String replacementTail(String message) {
    int index = -1;
    for (String marker : new String[] { "\\u6539\\u6210", "\\u8c03\\u6574\\u4e3a", "\\u6362\\u6210" }) {
      int found = message.indexOf(decodeUnicode(marker));
      if (found >= 0) index = Math.max(index, found + decodeUnicode(marker).length());
    }
    return index < 0 ? "" : message.substring(index);
  }

  private static JsonObject matchingAttraction(JsonArray attractions, String query) {
    if (query == null || query.isBlank()) return null;
    String normalizedQuery = query.replaceAll("\\s+", "");
    for (JsonElement item : attractions) {
      if (!item.isJsonObject()) continue;
      JsonObject attraction = item.getAsJsonObject();
      String name = text(attraction, "name");
      if (normalizedQuery.equals(name.replaceAll("\\s+", ""))) return attraction;
    }
    for (JsonElement item : attractions) {
      if (!item.isJsonObject()) continue;
      JsonObject attraction = item.getAsJsonObject();
      String name = text(attraction, "name").replaceAll("\\s+", "");
      if (!name.isBlank() && (name.contains(normalizedQuery) || normalizedQuery.contains(name))) return attraction;
    }
    return null;
  }

  private static boolean hasTimeSegment(String message) {
    return contains(message, "\\u4e0a\\u5348") || contains(message, "\\u4e0b\\u5348") || contains(message, "\\u665a\\u4e0a") || TIME.matcher(message).find();
  }

  private static int number(String value) {
    try { return Integer.parseInt(value); } catch (NumberFormatException ignored) { }
    return switch (value) {
      case "\\u4e00" -> 1; case "\\u4e8c", "\\u4e24" -> 2; case "\\u4e09" -> 3; case "\\u56db" -> 4; case "\\u4e94" -> 5;
      case "\\u516d" -> 6; case "\\u4e03" -> 7; case "\\u516b" -> 8; case "\\u4e5d" -> 9; case "\\u5341" -> 10;
      default -> 0;
    };
  }

  private static JsonArray array(JsonObject value, String name) {
    return value.has(name) && value.get(name).isJsonArray() ? value.getAsJsonArray(name) : new JsonArray();
  }
  private static String text(JsonObject value, String name) {
    return value.has(name) && !value.get(name).isJsonNull() ? value.get(name).getAsString() : "";
  }
  private static void copy(JsonObject source, JsonObject target, String sourceName, String targetName) {
    if (source.has(sourceName) && !source.get(sourceName).isJsonNull()) target.add(targetName, source.get(sourceName).deepCopy());
  }
  private static boolean contains(String value, String escaped) { return value.contains(decodeUnicode(escaped)); }
  private static String decodeUnicode(String value) {
    StringBuilder decoded = new StringBuilder();
    for (int index = 0; index < value.length(); index++) {
      if (value.charAt(index) == '\\' && index + 5 < value.length() && value.charAt(index + 1) == 'u') {
        try {
          decoded.append((char) Integer.parseInt(value.substring(index + 2, index + 6), 16));
          index += 5;
          continue;
        } catch (NumberFormatException ignored) { }
      }
      decoded.append(value.charAt(index));
    }
    return decoded.toString();
  }
  private static String unescape(String value) {
    return value.replace("\\u4e0a", "上").replace("\\u4e0b", "下").replace("\\u5348", "午").replace("\\u665a", "晚")
        .replace("\\u6574", "整").replace("\\u4e2a", "个").replace("\\u8ba1", "计").replace("\\u5212", "划")
        .replace("\\u5168", "全").replace("\\u90e8", "部").replace("\\u884c", "行").replace("\\u7a0b", "程")
        .replace("\\u91cd", "重").replace("\\u65b0", "新").replace("\\u89c4", "规").replace("\\u505a", "做").replace("\\u7248", "版");
  }
}
