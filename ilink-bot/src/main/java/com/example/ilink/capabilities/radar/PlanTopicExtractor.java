package com.example.ilink.capabilities.radar;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从计划目标和未完成任务中提取适合公开搜索的非敏感主题。 */
public final class PlanTopicExtractor {
    private static final Pattern LATIN_TERM = Pattern.compile(
            "[A-Za-z][A-Za-z0-9.+#-]*(?:\\s+[A-Za-z][A-Za-z0-9.+#-]*){0,2}");
    private static final Pattern ACTION_PREFIX = Pattern.compile(
            "^(?:完成|学习|准备|实现|开发|优化|阅读|研究|掌握|构建|搭建|修复|上线|推进|整理|练习|复习)+");
    private static final Pattern SENSITIVE = Pattern.compile(
            ".*(?:薪资|工资|身份证|手机号|电话|住址|密码|验证码|\\d{6,}|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+).*",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> GENERIC = Set.of(
            "项目", "任务", "计划", "工作", "学习", "完成", "准备", "今天", "本周", "当前");

    public ExtractedPlan extract(List<String> planSignals) {
        List<String> normalizedSignals = planSignals == null ? List.of() : planSignals.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim).distinct().limit(30).toList();
        LinkedHashSet<String> topics = new LinkedHashSet<>();
        for (String signal : normalizedSignals) {
            String cleaned = ACTION_PREFIX.matcher(signal).replaceFirst("")
                    .replaceAll("[，。；;：:（()）\\[\\]]", " ")
                    .replaceAll("\\s+", " ").trim();
            Matcher matcher = LATIN_TERM.matcher(cleaned);
            while (matcher.find()) {
                String term = matcher.group().trim();
                if (term.length() >= 2 && !GENERIC.contains(term.toLowerCase(Locale.ROOT))) topics.add(term);
            }
            String chinese = cleaned.replaceAll("[A-Za-z0-9.+#_ -]", "").trim();
            if (chinese.length() >= 4 && chinese.length() <= 24 && !GENERIC.contains(chinese)
                    && !chinese.matches("项目(?:上线)?|本周计划|当前任务")
                    && !SENSITIVE.matcher(signal).matches()) {
                topics.add(chinese);
            }
            if (topics.size() >= 10) break;
        }
        return new ExtractedPlan(fingerprint(normalizedSignals), List.copyOf(topics));
    }

    static String fingerprint(List<String> values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(String.join("\n", values).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte value : hash) result.append(String.format(Locale.ROOT, "%02x", value));
            return result.toString();
        } catch (Exception error) {
            return Integer.toHexString(values.hashCode());
        }
    }

    public record ExtractedPlan(String fingerprint, List<String> topics) {
        public ExtractedPlan {
            fingerprint = fingerprint == null ? "" : fingerprint;
            topics = topics == null ? new ArrayList<>() : List.copyOf(topics);
        }
    }
}
