package com.example.ilink.capabilities.automation;

import com.example.ilink.application.executive.ExecutiveStepSpec;
import com.example.ilink.application.executive.RiskLevel;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;

import java.util.ArrayList;
import java.util.List;

/** 使用确定性模板构建 Automation 步骤，避免自由规划产生不可控工具调用。 */
public final class AutomationPlanBuilder {

    public List<ExecutiveStepSpec> build(AutomationSpec spec) {
        return switch (spec.type()) {
            case RESEARCH -> research(spec);
            case JOB_SEARCH -> jobSearch(spec);
            case JD_ANALYSIS -> jdAnalysis(spec);
            case RESUME_MATCH -> resumeMatch(spec);
        };
    }

    private List<ExecutiveStepSpec> research(AutomationSpec spec) {
        JsonObject search = new JsonObject();
        search.addProperty("query", spec.query());
        search.addProperty("limit", 8);
        JsonObject analysis = new JsonObject();
        analysis.addProperty("goal", spec.goal());
        analysis.addProperty("research", "{{step:1}}");
        JsonObject report = report(spec.goal(), "{{step:1}}", "{{step:2}}");
        return List.of(step("检索公开资料", "automation_research", AutomationWebSearchTool.NAME,
                        search, List.of(), "contains_url"),
                step("综合分析资料", "automation_research", ResearchAnalysisTool.NAME,
                        analysis, List.of(1), "research_report"),
                step("整理调研报告", "automation_research", AutomationReportTool.NAME,
                        report, List.of(1, 2), "research_report"));
    }

    private List<ExecutiveStepSpec> jobSearch(AutomationSpec spec) {
        JsonObject search = new JsonObject();
        search.addProperty("query", spec.query());
        JsonArray cities = new JsonArray();
        spec.jobSearchSpec().cities().forEach(cities::add);
        search.add("cities", cities);
        search.addProperty("role", spec.jobSearchSpec().role());
        search.addProperty("education", spec.jobSearchSpec().education());
        search.addProperty("minimum_months", spec.jobSearchSpec().minimumInternshipMonths());
        search.addProperty("days_per_week", spec.jobSearchSpec().daysPerWeek());
        JsonObject fetch = new JsonObject();
        fetch.addProperty("candidates", "{{step:1}}");
        JsonObject analysis = new JsonObject();
        analysis.addProperty("goal", spec.goal());
        analysis.addProperty("job_data", "{{step:2}}");
        List<ExecutiveStepSpec> steps = new ArrayList<>();
        steps.add(step("搜索公开岗位", "job_search", JobSearchTool.NAME,
                search, List.of(), "job_candidates"));
        steps.add(step("抓取岗位正文", "job_search", JobPageFetchTool.NAME,
                fetch, List.of(1), "contains_url"));
        steps.add(step("提取岗位信息", "job_search", JobAnalysisTool.NAME,
                analysis, List.of(2), "job_report"));
        if (!spec.resumeText().isBlank()) {
            JsonObject match = new JsonObject();
            match.addProperty("resume_text", spec.resumeText());
            match.addProperty("jd_text", "{{step:3}}");
            steps.add(step("匹配简历与岗位", "resume_match", ResumeMatchTool.NAME, match, List.of(3)));
            steps.add(step("生成岗位报告", "job_search", JobReportTool.NAME,
                    jobReport(spec.goal(), "{{step:3}}", "{{step:4}}"), List.of(3, 4), "job_report"));
        } else {
            steps.add(step("生成岗位报告", "job_search", JobReportTool.NAME,
                    jobReport(spec.goal(), "{{step:3}}", ""), List.of(3), "job_report"));
        }
        return List.copyOf(steps);
    }

    private List<ExecutiveStepSpec> jdAnalysis(AutomationSpec spec) {
        JsonObject analysis = new JsonObject();
        analysis.addProperty("jd_text", spec.jdText());
        return List.of(step("分析职位描述", "jd_analysis", JdAnalysisTool.NAME, analysis, List.of()),
                step("生成 JD 报告", "jd_analysis", AutomationReportTool.NAME,
                        report(spec.goal(), spec.jdText(), "{{step:1}}"), List.of(1)));
    }

    private List<ExecutiveStepSpec> resumeMatch(AutomationSpec spec) {
        JsonObject match = new JsonObject();
        match.addProperty("resume_text", spec.resumeText());
        match.addProperty("jd_text", spec.jdText());
        return List.of(step("对比简历与岗位", "resume_match", ResumeMatchTool.NAME, match, List.of()),
                step("生成匹配报告", "resume_match", AutomationReportTool.NAME,
                        report(spec.goal(), spec.jdText(), "{{step:1}}"), List.of(1)));
    }

    private JsonObject report(String title, String research, String analysis) {
        JsonObject value = new JsonObject();
        value.addProperty("title", title);
        value.addProperty("research", research);
        value.addProperty("analysis", analysis);
        return value;
    }

    private JsonObject jobReport(String title, String analysis, String resumeMatch) {
        JsonObject value = new JsonObject();
        value.addProperty("title", title);
        value.addProperty("analysis", analysis);
        value.addProperty("resume_match", resumeMatch);
        return value;
    }

    private ExecutiveStepSpec step(String title, String capability, String tool,
                                   JsonObject arguments, List<Integer> dependencies) {
        return step(title, capability, tool, arguments, dependencies, "non_empty");
    }

    private ExecutiveStepSpec step(String title, String capability, String tool,
                                   JsonObject arguments, List<Integer> dependencies,
                                   String verificationRule) {
        return new ExecutiveStepSpec(title, capability, tool, arguments, dependencies,
                false, RiskLevel.READ_ONLY, 3, verificationRule);
    }
}
