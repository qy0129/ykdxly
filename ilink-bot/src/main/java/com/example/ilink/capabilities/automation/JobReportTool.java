package com.example.ilink.capabilities.automation;

import com.example.ilink.application.tooling.Tool;
import com.example.ilink.application.tooling.ToolContext;
import com.example.ilink.application.tooling.ToolDefinition;
import com.example.ilink.application.tooling.ToolResult;
import com.google.gson.JsonObject;

/** 岗位专用报告，避免通用报告只剩来源链接。 */
public final class JobReportTool implements Tool {
    public static final String NAME = "automation_job_report";

    @Override
    public ToolDefinition definition() {
        JsonObject properties = new JsonObject();
        properties.add("title", ToolDefinition.stringProperty("用户岗位搜索目标"));
        properties.add("analysis", ToolDefinition.stringProperty("结构化岗位分析"));
        properties.add("resume_match", ToolDefinition.stringProperty("简历匹配结果，可为空"));
        return new ToolDefinition(NAME, "生成岗位报告", "输出岗位详情、条件匹配和来源",
                ToolDefinition.objectParameters(properties, "title", "analysis", "resume_match"), true);
    }

    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        String title = arguments.get("title").getAsString().trim();
        String analysis = arguments.get("analysis").getAsString().trim();
        String match = arguments.get("resume_match").getAsString().trim();
        if (analysis.isBlank()) return ToolResult.failure("没有可输出的岗位分析");
        StringBuilder report = new StringBuilder(title).append("\n\n").append(analysis);
        if (!match.isBlank()) report.append("\n\n简历匹配\n").append(match);
        return ToolResult.success(report.toString().trim());
    }
}
