package com.example.ilink.capabilities.automation;

import com.example.ilink.application.tooling.Tool;
import com.example.ilink.application.tooling.ToolContext;
import com.example.ilink.application.tooling.ToolDefinition;
import com.example.ilink.application.tooling.ToolResult;
import com.google.gson.JsonObject;

public final class ResumeMatchTool implements Tool {
    public static final String NAME = "automation_resume_match";
    private final AutomationAnalysisService analysis;

    public ResumeMatchTool(AutomationAnalysisService analysis) {
        this.analysis = analysis;
    }

    @Override
    public ToolDefinition definition() {
        JsonObject properties = new JsonObject();
        properties.add("resume_text", ToolDefinition.stringProperty("用户简历文本"));
        properties.add("jd_text", ToolDefinition.stringProperty("职位描述或岗位搜索结果"));
        return new ToolDefinition(NAME, "简历岗位匹配", "对比简历与岗位要求并给出证据化建议",
                ToolDefinition.objectParameters(properties, "resume_text", "jd_text"), true);
    }

    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        String resume = arguments.get("resume_text").getAsString().trim();
        String jd = arguments.get("jd_text").getAsString().trim();
        if (resume.isBlank()) return ToolResult.failure("缺少简历文本");
        if (jd.isBlank()) return ToolResult.failure("缺少职位描述");
        return ToolResult.success(analysis.matchResume(resume, jd));
    }
}
