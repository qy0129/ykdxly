package com.example.ilink.capabilities.automation;

import com.example.ilink.application.tooling.Tool;
import com.example.ilink.application.tooling.ToolContext;
import com.example.ilink.application.tooling.ToolDefinition;
import com.example.ilink.application.tooling.ToolResult;
import com.example.ilink.capabilities.web.SearchResult;
import com.example.ilink.capabilities.web.WebSearchService;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 分城市检索并过滤真实招聘候选页面。 */
public final class JobSearchTool implements Tool {
    public static final String NAME = "automation_job_search";
    private static final List<String> BANNED_TEXT = List.of(
            "百度百科", "人民政府", "政府门户", "地图", "旅游", "天气", "词典", "城市介绍");
    private static final List<String> JOB_MARKERS = List.of(
            "招聘", "职位", "岗位", "校招", "应届", "任职要求", "职位描述");
    private final SearchGateway search;
    private final Gson gson = new Gson();
    private final JobSearchQueryPlanner planner = new JobSearchQueryPlanner();

    public JobSearchTool(WebSearchService service) {
        this(service::search);
    }

    public JobSearchTool(SearchGateway search) {
        this.search = search;
    }

    @Override
    public ToolDefinition definition() {
        JsonObject properties = new JsonObject();
        properties.add("query", ToolDefinition.stringProperty("完整岗位搜索要求"));
        properties.add("cities", arrayProperty("目标城市"));
        properties.add("role", ToolDefinition.stringProperty("岗位方向"));
        properties.add("education", ToolDefinition.stringProperty("学历要求，可为空"));
        properties.add("minimum_months", ToolDefinition.integerProperty("最低实习月数", 0, 24));
        properties.add("days_per_week", ToolDefinition.integerProperty("每周最低到岗天数", 0, 7));
        return new ToolDefinition(NAME, "岗位搜索", "按城市搜索并过滤无关页面，返回真实招聘候选",
                ToolDefinition.objectParameters(properties, "query", "cities", "role",
                        "education", "minimum_months", "days_per_week"), true);
    }

    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        String query = string(arguments, "query");
        JobSearchSpec spec = new JobSearchSpec(strings(arguments.getAsJsonArray("cities")),
                string(arguments, "role"), string(arguments, "education"),
                integer(arguments, "minimum_months"), integer(arguments, "days_per_week"), List.of());
        try {
            List<String> queries = planner.plan(spec, query);
            List<SearchResult> combined = new ArrayList<>();
            for (String subQuery : queries) {
                try {
                    combined.addAll(search.search(subQuery, 8));
                } catch (Exception error) {
                    System.err.println("[岗位搜索] 子查询失败：" + subQuery + "，" + error.getMessage());
                }
            }
            List<JobCandidate> candidates = prioritizeCities(filterCandidates(combined, spec), spec.cities(), 12);
            if (candidates.isEmpty()) return ToolResult.failure("没有找到与城市和岗位方向匹配的招聘页面");
            JsonObject output = new JsonObject();
            output.addProperty("query", query);
            output.add("request", gson.toJsonTree(spec));
            output.add("queries", gson.toJsonTree(queries));
            output.add("jobs", gson.toJsonTree(candidates));
            return ToolResult.success(output.toString(), candidates);
        } catch (Exception error) {
            return ToolResult.failure("岗位搜索失败：" + error.getMessage());
        }
    }

    static List<JobCandidate> filterCandidates(List<SearchResult> source, JobSearchSpec spec) {
        Map<String, JobCandidate> unique = new LinkedHashMap<>();
        for (SearchResult result : AutomationWebSearchTool.deduplicate(source)) {
            String text = searchable(result);
            if (BANNED_TEXT.stream().anyMatch(text::contains) || bannedHost(result.url())
                    || nonJobContentPath(result.url())) continue;
            boolean jobRelated = JOB_MARKERS.stream().anyMatch(text::contains) || recruitmentHost(result.url());
            boolean roleRelated = roleRelated(text, spec.role());
            String city = spec.cities().stream().filter(text::contains).findFirst().orElse("");
            if (!spec.cities().isEmpty() && city.isBlank()) continue;
            if (!jobRelated || !roleRelated) continue;
            int score = 4;
            if (!city.isBlank()) score += 3;
            if (text.contains("java")) score += 3;
            if (text.contains("后端")) score += 2;
            if (text.matches("(?s).*(\\d{2,5}[-~至]\\d{2,5}|\\d+[kK][-~至]\\d+[kK]).*")) score += 2;
            if (!spec.education().isBlank() && text.contains(spec.education())) score++;
            if (text.contains("个月") || text.contains("每周")) score++;
            if (recruitmentHost(result.url())) score += 2;
            unique.putIfAbsent(result.url(), new JobCandidate(result.title(), result.summary(), result.source(),
                    result.url(), city, score));
        }
        return unique.values().stream().sorted(Comparator.comparingInt(JobCandidate::score).reversed()).toList();
    }

    static List<JobCandidate> prioritizeCities(List<JobCandidate> candidates, List<String> cities, int limit) {
        if (cities.isEmpty()) return candidates.stream().limit(limit).toList();
        List<JobCandidate> prioritized = new ArrayList<>();
        for (int offset = 0; prioritized.size() < limit; offset++) {
            boolean added = false;
            for (String city : cities) {
                List<JobCandidate> cityJobs = candidates.stream()
                        .filter(job -> city.equals(job.matchedCity())).toList();
                if (offset < cityJobs.size()) {
                    prioritized.add(cityJobs.get(offset));
                    added = true;
                    if (prioritized.size() == limit) break;
                }
            }
            if (!added) break;
        }
        return List.copyOf(prioritized);
    }

    private static boolean roleRelated(String text, String role) {
        String normalizedRole = role == null ? "" : role.toLowerCase(Locale.ROOT);
        if (normalizedRole.contains("java") && !text.contains("java")) return false;
        if (normalizedRole.contains("后端") && !text.contains("后端") && !text.contains("开发")) return false;
        return true;
    }

    private static boolean recruitmentHost(String value) {
        String host = host(value);
        return host.contains("zhipin") || host.contains("nowcoder") || host.contains("liepin")
                || host.contains("lagou") || host.contains("51job") || host.contains("shixiseng")
                || host.contains("jobs.") || host.contains("career") || host.contains("join");
    }

    private static boolean bannedHost(String value) {
        String host = host(value);
        return host.endsWith(".gov.cn") || host.equals("baike.baidu.com")
                || host.equals("map.baidu.com") || host.contains("meet-in-shanghai");
    }

    private static boolean nonJobContentPath(String value) {
        try {
            String path = URI.create(value).getPath().toLowerCase(Locale.ROOT);
            return path.contains("/discuss/") || path.contains("/feed/")
                    || path.contains("/creation/") || path.contains("/article/");
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String host(String value) {
        try {
            return URI.create(value).getHost().toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String searchable(SearchResult result) {
        return (safe(result.title()) + " " + safe(result.summary()) + " " + safe(result.source())
                + " " + safe(result.url())).toLowerCase(Locale.ROOT);
    }

    private static JsonObject arrayProperty(String description) {
        JsonObject value = new JsonObject();
        value.addProperty("type", "array");
        value.addProperty("description", description);
        JsonObject items = new JsonObject();
        items.addProperty("type", "string");
        value.add("items", items);
        return value;
    }

    private static List<String> strings(JsonArray array) {
        if (array == null) return List.of();
        return array.asList().stream().map(value -> value.getAsString()).toList();
    }

    private static String string(JsonObject value, String name) {
        return value.has(name) && !value.get(name).isJsonNull() ? value.get(name).getAsString().trim() : "";
    }

    private static int integer(JsonObject value, String name) {
        return value.has(name) ? value.get(name).getAsInt() : 0;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public record JobCandidate(String title, String summary, String source,
                               String url, String matchedCity, int score) { }
}
