package com.example.ilink.capabilities.automation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 将 Automation 意图转换为稳定的工作流输入。 */
public final class AutomationRequestParser {
    private static final Pattern JD_SECTION = Pattern.compile(
            "(?is)(?:JD|职位描述|岗位描述)[:：]\\s*(.+?)(?=\\n(?:简历|resume)[:：]|$)");
    private static final Pattern RESUME_SECTION = Pattern.compile(
            "(?is)(?:简历|resume)[:：]\\s*(.+?)(?=\\n(?:JD|职位描述|岗位描述)[:：]|$)");
    private static final Pattern MONTHS = Pattern.compile(
            "(?:至少|最少|不低于|不少于)?\\s*([零一二三四五六七八九十两\\d]+)\\s*个?月");
    private static final Pattern DAYS = Pattern.compile("每周\\s*(\\d+)\\s*天");
    private static final List<String> KNOWN_CITIES = List.of(
            "北京", "上海", "杭州", "深圳", "广州", "南京", "苏州", "成都", "武汉", "西安", "重庆");

    public AutomationSpec parse(String intent, String text) {
        String request = text == null ? "" : text.trim();
        AutomationType type = switch (intent == null ? "" : intent) {
            case "job_search" -> AutomationType.JOB_SEARCH;
            case "jd_analysis" -> AutomationType.JD_ANALYSIS;
            case "resume_match" -> AutomationType.RESUME_MATCH;
            default -> infer(request);
        };
        String jd = section(JD_SECTION, request);
        String resume = section(RESUME_SECTION, request);
        String query = stripCommand(AutomationSchedule.stripPrefix(request));
        if (type == AutomationType.JD_ANALYSIS && jd.isBlank()) jd = query;
        JobSearchSpec jobs = type == AutomationType.JOB_SEARCH ? parseJobSearch(request) : JobSearchSpec.empty();
        return new AutomationSpec(type, request, query, jd, resume, jobs,
                AutomationSchedule.parse(request, java.time.LocalDateTime.now()));
    }

    public boolean looksLikeAutomation(String text) {
        if (text == null) return false;
        return text.matches("(?is).*(帮我调研|自动调研|搜索岗位|找工作|找实习|实习岗位|招聘岗位|校招职位|"
                + "找.{0,30}(?:岗位|职位|实习)|分析JD|分析职位描述|简历匹配|岗位匹配|"
                + "(?:每天|每周[一二三四五六日天]?).{0,20}(?:自动)?(?:搜索|调研|整理|汇总).{0,40}"
                + "(?:新闻|资讯|简报|报告)).*" );
    }

    private AutomationType infer(String text) {
        if (text.matches("(?is).*(简历匹配|岗位匹配|匹配简历).*")) return AutomationType.RESUME_MATCH;
        if (text.matches("(?is).*(分析JD|分析职位描述|分析岗位描述).*")) return AutomationType.JD_ANALYSIS;
        if (text.matches("(?is).*(搜索岗位|找工作|找实习|实习岗位|招聘岗位|校招|招聘).*")) {
            return AutomationType.JOB_SEARCH;
        }
        return AutomationType.RESEARCH;
    }

    private String section(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private String stripCommand(String text) {
        return text.replaceFirst("^(请|麻烦)?(帮我)?(自动)?(调研|搜索|查找|找一下|找|分析|对比)[:： ]*", "").trim();
    }

    private JobSearchSpec parseJobSearch(String text) {
        List<String> cities = KNOWN_CITIES.stream().filter(text::contains).toList();
        String education = text.contains("本科") ? "本科" : text.contains("硕士") ? "硕士" : "";
        int months = number(MONTHS, text);
        int days = number(DAYS, text);
        Set<String> keywords = new LinkedHashSet<>();
        for (String value : List.of("Java", "后端", "Spring Boot", "MySQL", "Redis", "实习")) {
            if (text.toLowerCase().contains(value.toLowerCase())) keywords.add(value);
        }
        return new JobSearchSpec(cities, inferRole(text, keywords), education,
                months, days, new ArrayList<>(keywords));
    }

    private int number(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) return 0;
        String value = matcher.group(1);
        if (value.matches("\\d+")) return Integer.parseInt(value);
        return chineseNumber(value);
    }

    private int chineseNumber(String value) {
        int tenIndex = value.indexOf('十');
        if (tenIndex >= 0) {
            int tens = tenIndex == 0 ? 1 : chineseDigit(value.charAt(tenIndex - 1));
            int ones = tenIndex == value.length() - 1 ? 0 : chineseDigit(value.charAt(tenIndex + 1));
            return tens * 10 + ones;
        }
        return value.length() == 1 ? chineseDigit(value.charAt(0)) : 0;
    }

    private int chineseDigit(char value) {
        return switch (value) {
            case '一' -> 1;
            case '二', '两' -> 2;
            case '三' -> 3;
            case '四' -> 4;
            case '五' -> 5;
            case '六' -> 6;
            case '七' -> 7;
            case '八' -> 8;
            case '九' -> 9;
            default -> 0;
        };
    }

    private String inferRole(String text, Set<String> keywords) {
        if (text.matches("(?is).*Java\\s*后端.*")) return "Java 后端实习";
        if (text.contains("后端")) return "后端实习";
        if (text.toLowerCase().contains("java")) return "Java 实习";
        return keywords.isEmpty() ? "实习岗位" : String.join(" ", keywords);
    }
}
