package com.example.ilink.capabilities.automation;

import com.example.ilink.application.tooling.Tool;
import com.example.ilink.application.tooling.ToolContext;
import com.example.ilink.application.tooling.ToolDefinition;
import com.example.ilink.application.tooling.ToolResult;
import com.google.gson.JsonObject;

public final class JdAnalysisTool implements Tool {
    public static final String NAME = "automation_jd_analysis";
    private final AutomationAnalysisService analysis;

    public JdAnalysisTool(AutomationAnalysisService analysis) {
        this.analysis = analysis;
    }

    @Override
    public ToolDefinition definition() {
        JsonObject properties = new JsonObject();
        properties.add("jd_text", ToolDefinition.stringProperty("职位描述或岗位搜索结果"));
        return new ToolDefinition(NAME, "JD 分析", "提取岗位职责、要求、风险和准备建议",
                ToolDefinition.objectParameters(properties, "jd_text"), true);
    }

    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        String jd = arguments.get("jd_text").getAsString().trim();
        if (jd.isBlank()) return ToolResult.failure("缺少职位描述");
        return ToolResult.success(analysis.analyzeJd(jd));
    }
}
