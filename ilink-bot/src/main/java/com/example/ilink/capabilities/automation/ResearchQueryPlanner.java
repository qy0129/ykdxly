package com.example.ilink.capabilities.automation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 将自然语言调研目标拆成短查询，避免搜索引擎只命中句首泛化词。 */
public final class ResearchQueryPlanner {
    private static final Pattern COMPARISON = Pattern.compile(
            "(?:对比|比较)\\s*(.+?)(?=[，。；;]|给出|分析|说明|$)", Pattern.CASE_INSENSITIVE);
    private static final Set<String> STOP_WORDS = Set.of(
            "目前", "当前", "主流", "帮我", "请", "调研", "研究", "对比", "比较", "给出", "保留", "来源链接");

    public ResearchPlan plan(String request) {
        String normalized = normalize(request);
        List<String> targets = comparisonTargets(normalized);
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        if (!targets.isEmpty()) {
            for (String target : targets) {
                String officialHint = officialHint(target);
                if (!officialHint.isBlank()) queries.add(officialHint);
                queries.add(target + " official documentation");
                queries.add(target + " Java framework overview");
            }
        }
        String topic = removeInstructions(normalized);
        if (!topic.isBlank()) queries.add(topic);
        if (queries.isEmpty()) queries.add(normalized);
        return new ResearchPlan(topic, targets, queries.stream().limit(8).toList());
    }

    private List<String> comparisonTargets(String text) {
        Matcher matcher = COMPARISON.matcher(text);
        if (!matcher.find()) return List.of();
        List<String> targets = new ArrayList<>();
        for (String value : matcher.group(1).split("\\s*(?:、|，|,|以及|和|与|vs\\.?|VS\\.?)\\s*")) {
            String target = value.trim().replaceAll("^[：: ]+|[：: ]+$", "");
            if (target.length() >= 2 && target.length() <= 60) targets.add(target);
        }
        return List.copyOf(targets);
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private String removeInstructions(String value) {
        String result = value;
        for (String word : STOP_WORDS) result = result.replace(word, " ");
        return result.replaceAll("[，。；;]+", " ").replaceAll("\\s+", " ")
                .replaceFirst("^的\\s*", "").replaceFirst("(?:并|和)$", "").trim();
    }

    private String officialHint(String target) {
        String normalized = target.toLowerCase().replaceAll("[^a-z0-9]", "");
        return switch (normalized) {
            case "springai" -> "site:docs.spring.io/spring-ai Spring AI";
            case "langchain4j" -> "site:docs.langchain4j.dev LangChain4j";
            case "semantickernel" -> "Microsoft Semantic Kernel Java SDK GitHub";
            default -> "";
        };
    }

    public record ResearchPlan(String topic, List<String> targets, List<String> queries) { }
}
