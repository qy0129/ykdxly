package com.example.ilink.capabilities.automation;

import com.example.ilink.application.tooling.Tool;
import com.example.ilink.application.tooling.ToolContext;
import com.example.ilink.application.tooling.ToolDefinition;
import com.example.ilink.application.tooling.ToolResult;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从网页正文和搜索摘要中提取岗位字段，缺失信息明确标记为未公开。 */
public final class JobAnalysisTool implements Tool {
    public static final String NAME = "automation_job_analysis";
    private static final Pattern SALARY = Pattern.compile(
            "(?i)(\\d{2,5}\\s*[-~至]\\s*\\d{2,5}\\s*元/(?:天|日|小时)|"
                    + "\\d+(?:\\.\\d+)?\\s*[-~至]\\s*\\d+(?:\\.\\d+)?\\s*[k千]\\s*/?月|"
                    + "\\d+\\s*[-~至]\\s*\\d+\\s*万/年)");
    private static final Pattern MONTHS = Pattern.compile("(\\d+)\\s*个?月");
    private static final Pattern DAYS = Pattern.compile("每周\\s*(\\d+)\\s*天");
    private final Gson gson = new Gson();

    @Override
    public ToolDefinition definition() {
        JsonObject properties = new JsonObject();
        properties.add("goal", ToolDefinition.stringProperty("用户完整岗位要求"));
        properties.add("job_data", ToolDefinition.stringProperty("抓取后的岗位候选 JSON"));
        return new ToolDefinition(NAME, "分析岗位信息", "提取岗位、薪资、学历、周期、职责和来源",
                ToolDefinition.objectParameters(properties, "goal", "job_data"), true);
    }

    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        try {
            JsonObject data = JsonParser.parseString(arguments.get("job_data").getAsString()).getAsJsonObject();
            JobSearchSpec request = gson.fromJson(data.get("request"), JobSearchSpec.class);
            JsonArray jobs = data.getAsJsonArray("jobs");
            if (jobs == null || jobs.isEmpty()) return ToolResult.failure("没有可分析的岗位");
            StringBuilder report = new StringBuilder();
            report.append("岗位搜索条件\n")
                    .append("城市：").append(request.cities().isEmpty() ? "未限定" : String.join("、", request.cities())).append('\n')
                    .append("方向：").append(request.role()).append('\n')
                    .append("学历：").append(request.education().isBlank() ? "未限定" : request.education()).append('\n')
                    .append("最低实习周期：").append(request.minimumInternshipMonths() > 0
                            ? request.minimumInternshipMonths() + "个月" : "未限定").append("\n\n");
            for (String city : request.cities()) {
                long count = jobs.asList().stream().map(value -> value.getAsJsonObject())
                        .filter(job -> city.equals(string(job, "matchedCity"))).count();
                report.append(city).append("相关候选：").append(count).append(" 个\n");
            }
            report.append("\n岗位详情\n");
            for (int index = 0; index < Math.min(jobs.size(), 8); index++) {
                JsonObject job = jobs.get(index).getAsJsonObject();
                String content = sourceText(job);
                String salary = match(SALARY, content);
                String education = containsAny(content, List.of("本科及以上", "本科", "硕士"));
                int months = number(MONTHS, content);
                int days = number(DAYS, content);
                report.append("\n").append(index + 1).append(". ").append(string(job, "title")).append('\n')
                        .append("公司：").append(inferCompany(job)).append('\n')
                        .append("城市：").append(orUnknown(string(job, "matchedCity"))).append('\n')
                        .append("薪资：").append(orUnknown(salary)).append('\n')
                        .append("学历：").append(orUnknown(education)).append('\n')
                        .append("实习周期：").append(months > 0 ? months + "个月" : "未公开").append('\n')
                        .append("每周到岗：").append(days > 0 ? days + "天" : "未公开").append('\n')
                        .append("工作内容与要求：").append(excerpt(content)).append('\n')
                        .append("匹配说明：").append(matchNote(request, education, months, days)).append('\n')
                        .append("信息来源：").append("page".equals(string(job, "sourceLevel"))
                                ? "岗位网页正文" : "搜索摘要，正文未获取").append('\n')
                        .append("链接：").append(string(job, "url")).append('\n');
            }
            report.append("\n说明：岗位可能下线或变更，薪资和到岗要求以招聘页面及 HR 确认为准。");
            return ToolResult.success(report.toString());
        } catch (Exception error) {
            return ToolResult.failure("岗位分析失败：" + error.getMessage());
        }
    }

    private String matchNote(JobSearchSpec request, String education, int months, int days) {
        boolean unknown = (!request.education().isBlank() && education.isBlank())
                || (request.minimumInternshipMonths() > 0 && months == 0)
                || (request.daysPerWeek() > 0 && days == 0);
        if (unknown) return "岗位方向相关，但部分学历或实习时间信息未公开，需要进一步确认";
        if (!request.education().isBlank() && !education.contains(request.education())) return "学历条件可能不匹配";
        if (request.minimumInternshipMonths() > 0 && months > 0 && months < request.minimumInternshipMonths()) {
            return "公开实习周期低于要求";
        }
        if (request.daysPerWeek() > 0 && days > 0 && days < request.daysPerWeek()) return "每周到岗天数低于要求";
        return "公开条件与要求较匹配";
    }

    private String sourceText(JsonObject job) {
        String page = string(job, "pageText");
        return (page.isBlank() ? string(job, "summary") : page).replaceAll("\\s+", " ").trim();
    }

    private String excerpt(String content) {
        if (content.isBlank()) return "未获取到岗位描述";
        int start = firstKeyword(content, List.of("岗位职责", "职位描述", "工作内容", "任职要求", "岗位要求"));
        if (start < 0) start = 0;
        String value = content.substring(start, Math.min(content.length(), start + 420)).trim();
        return value.isBlank() ? "未获取到岗位描述" : value;
    }

    private int firstKeyword(String content, List<String> keywords) {
        return keywords.stream().mapToInt(content::indexOf).filter(value -> value >= 0).min().orElse(-1);
    }

    private String inferCompany(JsonObject job) {
        String title = string(job, "title");
        for (String separator : List.of("_", "｜", "|", " - ")) {
            String[] parts = title.split(Pattern.quote(separator));
            if (parts.length > 1 && !parts[parts.length - 1].isBlank()) return parts[parts.length - 1].trim();
        }
        return "未识别";
    }

    private String containsAny(String text, List<String> values) {
        return values.stream().filter(text::contains).findFirst().orElse("");
    }

    private String match(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).replaceAll("\\s+", "") : "";
    }

    private int number(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private String orUnknown(String value) {
        return value == null || value.isBlank() ? "未公开" : value;
    }

    private String string(JsonObject object, String name) {
        return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : "";
    }
}
