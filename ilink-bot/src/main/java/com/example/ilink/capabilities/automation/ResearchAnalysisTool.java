package com.example.ilink.capabilities.automation;

import com.example.ilink.application.tooling.Tool;
import com.example.ilink.application.tooling.ToolContext;
import com.example.ilink.application.tooling.ToolDefinition;
import com.example.ilink.application.tooling.ToolResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 将检索结果综合为有结论、有对比、有来源的调研报告。 */
public final class ResearchAnalysisTool implements Tool {
    public static final String NAME = "automation_research_analysis";
    private final AutomationAnalysisService analysis;

    public ResearchAnalysisTool(AutomationAnalysisService analysis) {
        this.analysis = analysis;
    }

    @Override
    public ToolDefinition definition() {
        JsonObject properties = new JsonObject();
        properties.add("goal", ToolDefinition.stringProperty("原始调研目标"));
        properties.add("research", ToolDefinition.stringProperty("结构化搜索结果"));
        return new ToolDefinition(NAME, "综合调研分析", "综合多来源资料，生成对比、结论和适用场景",
                ToolDefinition.objectParameters(properties, "goal", "research"), true);
    }

    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        String goal = arguments.get("goal").getAsString().trim();
        String research = arguments.get("research").getAsString().trim();
        if (research.isBlank()) return ToolResult.failure("缺少可分析的检索资料");
        String output = analysis.synthesizeResearch(goal, research);
        List<String> missing = missingTargets(output, research);
        return missing.isEmpty() ? ToolResult.success(output)
                : ToolResult.failure("分析没有覆盖以下对比对象：" + String.join("、", missing));
    }

    private List<String> missingTargets(String output, String research) {
        List<String> missing = new ArrayList<>();
        try {
            var targets = JsonParser.parseString(research).getAsJsonObject().getAsJsonArray("targets");
            if (targets == null) return missing;
            String normalized = output.toLowerCase(Locale.ROOT);
            targets.forEach(value -> {
                String target = value.getAsString();
                if (!normalized.contains(target.toLowerCase(Locale.ROOT))) missing.add(target);
            });
        } catch (Exception ignored) {
            // 搜索结果不是预期 JSON 时交给结果验证器处理。
        }
        return missing;
    }
}
