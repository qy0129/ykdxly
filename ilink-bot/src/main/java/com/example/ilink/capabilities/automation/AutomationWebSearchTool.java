package com.example.ilink.capabilities.automation;

import com.example.ilink.application.tooling.Tool;
import com.example.ilink.application.tooling.ToolContext;
import com.example.ilink.application.tooling.ToolDefinition;
import com.example.ilink.application.tooling.ToolResult;
import com.example.ilink.bootstrap.Config;
import com.example.ilink.capabilities.web.SearchResult;
import com.example.ilink.capabilities.web.WebSearchService;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class AutomationWebSearchTool implements Tool {
    public static final String NAME = "automation_web_search";
    private final SearchGateway search;
    private final Gson gson = new Gson();
    private final ResearchQueryPlanner queryPlanner = new ResearchQueryPlanner();

    public AutomationWebSearchTool(WebSearchService service) {
        this(service::search);
    }

    public AutomationWebSearchTool(SearchGateway search) {
        this.search = search;
    }

    @Override
    public ToolDefinition definition() {
        JsonObject properties = new JsonObject();
        properties.add("query", ToolDefinition.stringProperty("需要联网调研的问题"));
        properties.add("limit", ToolDefinition.integerProperty("结果数量", 1, 10));
        return new ToolDefinition(NAME, "自动化联网调研", "检索公开网页并返回带来源的结构化资料",
                ToolDefinition.objectParameters(properties, "query", "limit"), true);
    }

    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        String query = arguments.get("query").getAsString().trim();
        int limit = arguments.has("limit") ? arguments.get("limit").getAsInt()
                : Config.WEB_SEARCH_RESULT_LIMIT;
        try {
            ResearchQueryPlanner.ResearchPlan plan = queryPlanner.plan(query);
            List<SearchResult> collected = new ArrayList<>();
            for (String target : plan.targets()) collected.addAll(OfficialSourceCatalog.sourcesFor(target));
            for (String subQuery : plan.queries().stream().limit(3).toList()) {
                try {
                    collected.addAll(search.search(subQuery, 5));
                } catch (Exception error) {
                    System.err.println("[Automation 调研] 子查询失败：" + subQuery + "，" + error.getMessage());
                }
            }
            List<SearchResult> results = rankAndFilter(collected, plan,
                    Math.min(16, Math.max(3, limit * 2)));
            if (results.isEmpty()) return ToolResult.failure("没有检索到可用的公开资料");
            List<String> missingTargets = missingTargets(results, plan.targets());
            if (!missingTargets.isEmpty()) {
                return ToolResult.failure("检索资料没有覆盖以下对象：" + String.join("、", missingTargets));
            }
            JsonObject output = new JsonObject();
            output.addProperty("query", query);
            output.add("targets", gson.toJsonTree(plan.targets()));
            output.add("queries", gson.toJsonTree(plan.queries()));
            output.add("targetSources", targetSources(results, plan.targets()));
            output.add("results", gson.toJsonTree(results));
            return ToolResult.success(output.toString(), results);
        } catch (Exception error) {
            return ToolResult.failure("联网检索失败：" + error.getMessage());
        }
    }

    static List<SearchResult> deduplicate(List<SearchResult> source) {
        Map<String, SearchResult> unique = new LinkedHashMap<>();
        if (source != null) {
            for (SearchResult result : source) {
                if (result != null && result.url() != null && !result.url().isBlank()) {
                    unique.putIfAbsent(result.url(), result);
                }
            }
        }
        return List.copyOf(unique.values());
    }

    static List<SearchResult> rankAndFilter(List<SearchResult> source,
                                            ResearchQueryPlanner.ResearchPlan plan, int limit) {
        List<SearchResult> unique = deduplicate(source);
        Set<String> topicTerms = terms(plan.topic());
        List<ScoredResult> scored = unique.stream()
                .map(result -> new ScoredResult(result, score(result, plan.targets(), topicTerms)))
                .filter(value -> value.score() >= minimumScore(plan.targets()))
                .sorted(Comparator.comparingInt(ScoredResult::score).reversed())
                .toList();

        LinkedHashMap<String, SearchResult> selected = new LinkedHashMap<>();
        for (String target : plan.targets()) {
            String needle = target.toLowerCase(Locale.ROOT);
            scored.stream().filter(value -> searchable(value.result()).contains(needle))
                    .filter(value -> !OfficialSourceCatalog.hasSources(target)
                            || OfficialSourceCatalog.isOfficialFor(target, value.result()))
                    .limit(3).forEach(value -> selected.putIfAbsent(value.result().url(), value.result()));
        }
        for (ScoredResult value : scored) {
            if (selected.size() >= limit) break;
            if (!acceptableForComparison(value.result(), plan.targets())) continue;
            selected.putIfAbsent(value.result().url(), value.result());
        }
        return selected.values().stream().limit(limit).toList();
    }

    private static int score(SearchResult result, List<String> targets, Set<String> topicTerms) {
        String text = searchable(result);
        int score = 0;
        for (String target : targets) {
            if (text.contains(target.toLowerCase(Locale.ROOT))) score += 8;
        }
        for (String term : topicTerms) {
            if (term.length() >= 2 && text.contains(term)) score++;
        }
        String host = host(result.url());
        if (host.contains("docs.") || host.contains("github.com") || host.contains("microsoft.com")
                || host.contains("spring.io") || host.contains("langchain4j.dev")) score += 5;
        if (text.contains("documentation") || text.contains("官方") || text.contains("reference")) score += 2;
        return score;
    }

    private static int minimumScore(List<String> targets) {
        return targets.isEmpty() ? 2 : 12;
    }

    private static String searchable(SearchResult result) {
        return (safe(result.title()) + " " + safe(result.summary()) + " "
                + safe(result.source()) + " " + safe(result.url())).toLowerCase(Locale.ROOT);
    }

    private static Set<String> terms(String value) {
        Set<String> result = new LinkedHashSet<>();
        for (String term : safe(value).toLowerCase(Locale.ROOT).split("[^a-z0-9+#.\\u4e00-\\u9fa5]+")) {
            if (term.length() >= 2) result.add(term);
        }
        return result;
    }

    private static String host(String value) {
        try {
            return safe(URI.create(value).getHost()).toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static List<String> missingTargets(List<SearchResult> results, List<String> targets) {
        String all = results.stream().map(AutomationWebSearchTool::searchable)
                .reduce("", (left, right) -> left + " " + right);
        return targets.stream().filter(target -> !all.contains(target.toLowerCase(Locale.ROOT))).toList();
    }

    private static boolean acceptableForComparison(SearchResult result, List<String> targets) {
        if (targets.isEmpty()) return true;
        for (String target : targets) {
            if (OfficialSourceCatalog.hasSources(target)) {
                if (OfficialSourceCatalog.isOfficialFor(target, result)) return true;
            } else if (searchable(result).contains(target.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private JsonObject targetSources(List<SearchResult> results, List<String> targets) {
        JsonObject groups = new JsonObject();
        for (String target : targets) {
            List<SearchResult> values = results.stream()
                    .filter(result -> OfficialSourceCatalog.isOfficialFor(target, result)
                            || searchable(result).contains(target.toLowerCase(Locale.ROOT)))
                    .toList();
            groups.add(target, gson.toJsonTree(values));
        }
        return groups;
    }

    private record ScoredResult(SearchResult result, int score) { }
}
