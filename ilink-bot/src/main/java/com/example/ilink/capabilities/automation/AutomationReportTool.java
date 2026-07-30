package com.example.ilink.capabilities.automation;

import com.example.ilink.application.tooling.Tool;
import com.example.ilink.application.tooling.ToolContext;
import com.example.ilink.application.tooling.ToolDefinition;
import com.example.ilink.application.tooling.ToolResult;
import com.google.gson.JsonObject;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.net.URI;

public final class AutomationReportTool implements Tool {
    public static final String NAME = "automation_report";
    private static final Pattern URL = Pattern.compile("https?://[^\\s\\\"}]+", Pattern.CASE_INSENSITIVE);

    @Override
    public ToolDefinition definition() {
        JsonObject properties = new JsonObject();
        properties.add("title", ToolDefinition.stringProperty("报告标题"));
        properties.add("research", ToolDefinition.stringProperty("搜索或原始资料"));
        properties.add("analysis", ToolDefinition.stringProperty("分析结果，可为空"));
        return new ToolDefinition(NAME, "生成自动化报告", "汇总执行结果并保留公开来源 URL",
                ToolDefinition.objectParameters(properties, "title", "research", "analysis"), true);
    }

    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        String title = arguments.get("title").getAsString().trim();
        String research = arguments.get("research").getAsString().trim();
        String analysis = arguments.get("analysis").getAsString().trim();
        if (research.isBlank() && analysis.isBlank()) return ToolResult.failure("没有可汇总的执行结果");
        StringBuilder report = new StringBuilder(title.isBlank() ? "自动化任务报告" : title);
        if (!analysis.isBlank()) report.append("\n\n分析结论\n").append(analysis);
        Set<String> sources = new LinkedHashSet<>();
        Matcher matcher = URL.matcher(research + "\n" + analysis);
        while (matcher.find()) sources.add(normalizeUrl(matcher.group()));
        if (!sources.isEmpty()) {
            report.append("\n\n来源\n");
            sources.forEach(source -> report.append("- ").append(source).append('\n'));
        }
        return ToolResult.success(report.toString());
    }

    private String normalizeUrl(String value) {
        String cleaned = value.replaceAll("[)\\]}>，。；;,]+$", "");
        try {
            URI uri = URI.create(cleaned);
            String path = uri.getPath();
            if (path != null && path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            return new URI(uri.getScheme(), uri.getAuthority(), path, uri.getQuery(), null).toString();
        } catch (Exception ignored) {
            return cleaned;
        }
    }
}
