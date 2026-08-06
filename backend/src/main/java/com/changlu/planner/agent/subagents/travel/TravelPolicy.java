package com.changlu.planner.agent.subagents.travel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.changlu.planner.agent.core.contract.SubagentRequest;
import com.changlu.planner.agent.subagents.travel.nlu.*;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class TravelPolicy {
  private static final Pattern DESTINATION_PATTERN = Pattern.compile(
      "(?:去|到|前往)([\\p{IsHan}A-Za-z0-9]{2,12}?)(?:玩|旅游|旅行|度假|出发|，|,|$|(?=[一二两三四五六七八九十百零〇0-9]{1,3}(?:天|日)))");
  private static final Pattern ARABIC_DAYS_PATTERN = Pattern.compile("(\\d{1,3})\\s*(?:天|日)");
  private static final Pattern CHINESE_DAYS_PATTERN = Pattern.compile(
      "([一二两三四五六七八九十百零〇]+)\\s*(?:天|日)");
  private final Clock clock;
  private final RelativeDateParser relativeDates;
  private final ChineseDurationParser durations = new ChineseDurationParser();
  private final ChineseMoneyParser money = new ChineseMoneyParser();
  private final TravelPreferenceParser preferences = new TravelPreferenceParser();

  public TravelPolicy() { this(Clock.system(ZoneId.of("Asia/Shanghai"))); }
  public TravelPolicy(Clock clock) { this.clock = clock; this.relativeDates = new RelativeDateParser(clock); }

  public SubagentRequest normalizeRequest(SubagentRequest request) {
    JsonObject arguments = request.arguments();
    String message = request.message().replaceAll("\\s", "");
    if (!arguments.has("destination") || arguments.get("destination").getAsString().isBlank()) {
      Matcher matcher = DESTINATION_PATTERN.matcher(message);
      if (matcher.find()) arguments.addProperty("destination", matcher.group(1));
    }
    LocalDate startDate = date(arguments, "startDate");
    clearInvalidDate(arguments, "startDate");
    clearInvalidDate(arguments, "endDate");
    if (arguments.has("budget") && arguments.get("budget").isJsonObject()
        && arguments.getAsJsonObject("budget").keySet().isEmpty()) arguments.remove("budget");
    if (startDate == null) {
      startDate = relativeDates.parse(message, requestZone(arguments));
      if (startDate != null) arguments.addProperty("startDate", startDate.toString());
    }
    if (startDate != null && (!arguments.has("endDate") || arguments.get("endDate").getAsString().isBlank())) {
      int days = durations.parseDays(message);
      if (days > 0) arguments.addProperty("endDate", startDate.plusDays(days - 1L).toString());
    }
    if (!arguments.has("travelers") && (message.contains("一个人") || message.contains("独自"))) {
      arguments.addProperty("travelers", 1);
    }
    preferences.apply(message, arguments);
    if (!arguments.has("budget")) {
      java.math.BigDecimal amount = money.parse(message);
      if (amount != null) { JsonObject budget = new JsonObject(); budget.addProperty("amount", amount); budget.addProperty("currency", "CNY"); arguments.add("budget", budget); }
    }
    if (previewOnly(message)) {
      // A current explicit preview instruction must override a reused request from an earlier turn.
      arguments.addProperty("saveToPlanner", false);
    } else if (!arguments.has("saveToPlanner") && writeRequested(message, arguments)) {
      arguments.addProperty("saveToPlanner", true);
    }
    String remarks = arguments.has("remarks") && !arguments.get("remarks").isJsonNull()
        ? arguments.get("remarks").getAsString().trim() : "";
    if (!remarks.isBlank()) {
      JsonArray constraints = arguments.has("constraints") && arguments.get("constraints").isJsonArray()
          ? arguments.getAsJsonArray("constraints") : new JsonArray();
      constraints.add("备注：" + remarks);
      arguments.add("constraints", constraints);
    }
    return new SubagentRequest(request.message(), arguments, request.documentIds());
  }

  private ZoneId requestZone(JsonObject arguments) {
    try {
      if (arguments.has("deviceLocation") && arguments.get("deviceLocation").isJsonObject()) {
        JsonObject location = arguments.getAsJsonObject("deviceLocation");
        if (location.has("timezone") && !location.get("timezone").getAsString().isBlank()) return ZoneId.of(location.get("timezone").getAsString());
      }
    } catch (Exception ignored) { }
    return clock.getZone();
  }

  private void clearInvalidDate(JsonObject arguments, String name) {
    if (!arguments.has(name) || arguments.get(name).isJsonNull()) return;
    String value = arguments.get(name).getAsString().trim();
    if (value.isBlank()) { arguments.remove(name); return; }
    try { LocalDate.parse(value); } catch (DateTimeParseException error) { arguments.remove(name); }
  }

  public void validateInput(SubagentRequest request) {
    if (request.message().isBlank()) throw new IllegalArgumentException("INVALID_ARGUMENT:message");
    JsonObject arguments = request.arguments();
    requireType(arguments, "destination", "string");
    requireType(arguments, "origin", "string");
    requireType(arguments, "travelers", "number");
    requireType(arguments, "saveToPlanner", "boolean");
    requireType(arguments, "pace", "string");
    validateDate(arguments, "startDate");
    validateDate(arguments, "endDate");
    if (arguments.has("travelers")) {
      double travelers = arguments.get("travelers").getAsDouble();
      if (travelers < 1 || travelers != Math.rint(travelers)) {
        throw new IllegalArgumentException("TRAVEL_TRAVELERS_INVALID");
      }
    }
    validateStringArray(arguments, "interests");
    validateStringArray(arguments, "constraints");
    validateBudget(arguments);
    if (arguments.has("pace") && !arguments.get("pace").getAsString().isBlank()
        && !Set.of("relaxed", "balanced", "intensive").contains(arguments.get("pace").getAsString())) {
      throw new IllegalArgumentException("INVALID_ARGUMENT:pace");
    }
    for (String documentId : request.documentIds()) {
      try { UUID.fromString(documentId); }
      catch (IllegalArgumentException error) { throw new IllegalArgumentException("INVALID_ARGUMENT:documentIds"); }
    }
  }

  public boolean unsupportedRequest(String message) {
    String normalized = message == null ? "" : message.replaceAll("\\s", "");
    return normalized.contains("代我订票") || normalized.contains("帮我订票")
        || normalized.contains("直接订票") || normalized.contains("替我付款")
        || normalized.contains("帮我付款") || normalized.contains("代订酒店")
        || normalized.contains("直接预订酒店") || normalized.contains("代订门票");
  }

  public boolean writeRequested(String message, JsonObject arguments) {
    if (previewOnly(message)) return false;
    if (arguments.has("saveToPlanner") && arguments.get("saveToPlanner").isJsonPrimitive()) {
      return arguments.get("saveToPlanner").getAsBoolean();
    }
    String normalized = message == null ? "" : message.replaceAll("\\s", "");
    boolean implicitTripPlan = normalized.matches(".*(?:去|到|前往).{2,16}[一二两三四五六七八九十百零〇0-9]{1,3}(?:天|日).*?");
    return implicitTripPlan || normalized.contains("制定") || normalized.contains("创建") || normalized.contains("安排")
        || normalized.contains("生成计划") || normalized.contains("加入计划") || normalized.contains("保存到")
        || normalized.contains("加入日历") || normalized.contains("写入日历") || normalized.contains("排进日历")
        || normalized.contains("生成草案") || normalized.contains("生成写入")
        || normalized.contains("做一个旅游计划") || normalized.contains("做一个旅行计划")
        || normalized.contains("创建旅行计划") || normalized.contains("生成旅行计划")
        || normalized.contains("行程计划") || normalized.contains("旅游安排");
  }

  public boolean planApproved(String message, JsonObject arguments) {
    if (previewOnly(message)) return false;
    if (arguments.has("approvePlan") && arguments.get("approvePlan").isJsonPrimitive()
        && arguments.get("approvePlan").getAsBoolean()) return true;
    String normalized = message == null ? "" : message.replaceAll("\\s", "");
    return normalized.contains("确认行程") || normalized.contains("确认方案")
        || normalized.contains("确认旅行计划") || normalized.contains("确认计划")
        || normalized.contains("确认无误") || normalized.contains("没问题")
        || normalized.contains("就按这个") || normalized.contains("就这么定")
         || normalized.contains("生成草案") || normalized.contains("生成写入计划");
  }

  private boolean previewOnly(String message) {
    String normalized = message == null ? "" : message.replaceAll("\\s", "");
    return normalized.contains("\u4e0d\u8981\u4fdd\u5b58") || normalized.contains("\u53ea\u8981\u5efa\u8bae")
        || normalized.contains("\u4ec5\u9884\u89c8") || normalized.contains("\u4e0d\u8981\u5199\u5165\u65e5\u5386")
        || normalized.contains("\u4e0d\u5199\u5165\u65e5\u5386") || normalized.contains("\u4e0d\u8981\u751f\u6210\u8349\u6848")
        || normalized.contains("\u53ea\u751f\u6210\u65b9\u6848\u9884\u89c8");
  }

  /** 纯确认语句：去掉确认类词与标点后没有剩余内容，可安全复用第一阶段方案。 */
  public boolean pureApproval(String message) {
    if (message == null) return false;
    String stripped = message.replaceAll("\\s", "")
        .replace("确认旅行计划", "").replace("确认行程", "").replace("确认方案", "")
        .replace("确认计划", "").replace("确认无误", "").replace("确认", "")
        .replace("生成写入计划", "").replace("生成写入草案", "").replace("生成写入", "")
        .replace("生成草案", "").replace("写入草案", "").replace("草案", "")
        .replace("没问题", "").replace("就按这个", "").replace("就这么定", "")
        .replace("就这样", "").replace("可以", "").replace("好的", "")
        .replaceAll("[，。！？、,.;:：；…·~ ]", "");
    return stripped.isBlank();
  }

  public void validate(TravelResult result) {
    TravelRequest request = result.request();
    LocalDate start = request.startDate().isBlank() ? null : LocalDate.parse(request.startDate());
    LocalDate end = request.endDate().isBlank() ? null : LocalDate.parse(request.endDate());
    if (start != null && end != null && end.isBefore(start)) {
      throw new IllegalArgumentException("TRAVEL_DATE_RANGE_INVALID");
    }
    if (request.travelers() != null && request.travelers() < 1) {
      throw new IllegalArgumentException("TRAVEL_TRAVELERS_INVALID");
    }
    validateDays(result.days(), start, end);
    validateBudgetEstimate(result.budgetEstimate());
    // 目的地缺失不由这里抛异常：missingRequirements → informationForm 会在 TravelSubagent
    // 里优雅地询问目的地/日期，而不是让整个 run 失败（"我要旅游"等无目的地请求的场景）。
  }

  private void validateDays(JsonArray days, LocalDate start, LocalDate end) {
    Set<String> dates = new HashSet<>();
    for (JsonElement element : days) {
      if (!element.isJsonObject()) throw new IllegalArgumentException("TRAVEL_DAY_INVALID");
      JsonObject day = element.getAsJsonObject();
      if (!day.has("date") || !day.get("date").isJsonPrimitive()) {
        throw new IllegalArgumentException("TRAVEL_DAY_DATE_REQUIRED");
      }
      LocalDate date;
      try {
        date = LocalDate.parse(day.get("date").getAsString());
      } catch (DateTimeParseException error) {
        throw new IllegalArgumentException("TRAVEL_DAY_DATE_INVALID");
      }
      if (!dates.add(date.toString())) throw new IllegalArgumentException("TRAVEL_DAY_DUPLICATE");
      if (start != null && date.isBefore(start) || end != null && date.isAfter(end)) {
        throw new IllegalArgumentException("TRAVEL_DAY_OUT_OF_RANGE");
      }
      if (day.has("activities") && !day.get("activities").isJsonArray()) {
        throw new IllegalArgumentException("TRAVEL_ACTIVITIES_INVALID");
      }
    }
  }

  private void validateBudgetEstimate(JsonObject budget) {
    if (budget == null || budget.keySet().isEmpty()) return;
    if (budget.has("amount") && (!budget.get("amount").isJsonPrimitive()
        || !budget.getAsJsonPrimitive("amount").isNumber()
        || budget.get("amount").getAsDouble() < 0)) {
      throw new IllegalArgumentException("TRAVEL_BUDGET_INVALID");
    }
    if (budget.has("currency") && (!budget.get("currency").isJsonPrimitive()
        || !budget.getAsJsonPrimitive("currency").isString()
        || budget.get("currency").getAsString().length() != 3)) {
      throw new IllegalArgumentException("TRAVEL_BUDGET_INVALID");
    }
  }

  private void requireType(JsonObject arguments, String name, String expected) {
    if (!arguments.has(name) || arguments.get(name).isJsonNull()) return;
    boolean valid = switch (expected) {
      case "string" -> arguments.get(name).isJsonPrimitive()
          && arguments.get(name).getAsJsonPrimitive().isString();
      case "number" -> arguments.get(name).isJsonPrimitive()
          && arguments.get(name).getAsJsonPrimitive().isNumber();
      case "boolean" -> arguments.get(name).isJsonPrimitive()
          && arguments.get(name).getAsJsonPrimitive().isBoolean();
      default -> false;
    };
    if (!valid) throw new IllegalArgumentException("INVALID_ARGUMENT:" + name);
  }

  private void validateDate(JsonObject arguments, String name) {
    requireType(arguments, name, "string");
    if (!arguments.has(name) || arguments.get(name).getAsString().isBlank()) return;
    try { LocalDate.parse(arguments.get(name).getAsString()); }
    catch (DateTimeParseException error) { throw new IllegalArgumentException("INVALID_ARGUMENT:" + name); }
  }

  private void validateStringArray(JsonObject arguments, String name) {
    if (!arguments.has(name) || arguments.get(name).isJsonNull()) return;
    if (!arguments.get(name).isJsonArray()) throw new IllegalArgumentException("INVALID_ARGUMENT:" + name);
    arguments.getAsJsonArray(name).forEach(value -> {
      if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
        throw new IllegalArgumentException("INVALID_ARGUMENT:" + name);
      }
    });
  }

  private void validateBudget(JsonObject arguments) {
    if (!arguments.has("budget") || arguments.get("budget").isJsonNull()) return;
    if (!arguments.get("budget").isJsonObject()) throw new IllegalArgumentException("INVALID_ARGUMENT:budget");
    JsonObject budget = arguments.getAsJsonObject("budget");
    if (budget.keySet().isEmpty()) return;
    if (!budget.has("amount") || !budget.has("currency")
        || !budget.get("amount").isJsonPrimitive() || !budget.getAsJsonPrimitive("amount").isNumber()
        || budget.get("amount").getAsDouble() < 0
        || !budget.get("currency").isJsonPrimitive() || !budget.getAsJsonPrimitive("currency").isString()
        || budget.get("currency").getAsString().length() != 3) {
      throw new IllegalArgumentException("INVALID_ARGUMENT:budget");
    }
  }

  private LocalDate date(JsonObject arguments, String name) {
    if (!arguments.has(name) || arguments.get(name).isJsonNull()) return null;
    String value = arguments.get(name).getAsString().trim();
    if (value.isBlank()) return null;
    try { return LocalDate.parse(value); } catch (DateTimeParseException ignored) { return null; }
  }

  private int durationDays(String message) {
    Matcher arabic = ARABIC_DAYS_PATTERN.matcher(message);
    if (arabic.find()) return Integer.parseInt(arabic.group(1));
    Matcher chinese = CHINESE_DAYS_PATTERN.matcher(message);
    if (chinese.find()) return chineseNumber(chinese.group(1));
    return message.contains("一周") || message.contains("一星期") ? 7 : 0;
  }

  private int chineseNumber(String value) {
    if ("十".equals(value)) return 10;
    if (value.length() == 2 && value.charAt(0) == '十') return 10 + digit(value.charAt(1));
    if (value.length() == 2 && value.charAt(1) == '十') return digit(value.charAt(0)) * 10;
    if (value.length() == 3 && value.charAt(1) == '十') {
      return digit(value.charAt(0)) * 10 + digit(value.charAt(2));
    }
    return value.length() == 1 ? digit(value.charAt(0)) : 0;
  }

  private int digit(char value) {
    return switch (value) {
      case '一' -> 1; case '二', '两' -> 2; case '三' -> 3; case '四' -> 4;
      case '五' -> 5; case '六' -> 6; case '七' -> 7; case '八' -> 8;
      case '九' -> 9; case '零', '〇' -> 0; default -> 0;
    };
  }
}
