package com.example.ilink.capabilities.automation;

import com.example.ilink.application.executive.ApprovalService;
import com.example.ilink.application.executive.DefaultResultVerifier;
import com.example.ilink.application.executive.ExecutionLogService;
import com.example.ilink.application.executive.ExecutiveEngine;
import com.example.ilink.application.executive.ExecutiveRuntime;
import com.example.ilink.application.executive.ExecutiveScheduler;
import com.example.ilink.application.executive.ExecutiveTask;
import com.example.ilink.application.executive.ExecutiveTaskService;
import com.example.ilink.application.executive.ExecutiveTaskStore;
import com.example.ilink.application.executive.NotificationOutbox;
import com.example.ilink.application.executive.TaskStatus;
import com.example.ilink.application.executive.ToolCapabilityExecutor;
import com.example.ilink.application.tooling.ToolContext;
import com.example.ilink.application.tooling.ToolManager;
import com.example.ilink.capabilities.web.SearchResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AutomationModuleTest {

    @Test
    void parsesWeeklyMondayMorningSchedule() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 31, 12, 0);

        AutomationSchedule schedule = AutomationSchedule.parse(
                "每周一早上自动搜索大模型行业新闻", now);

        assertEquals(com.example.ilink.application.executive.ScheduleRule.WEEKLY, schedule.rule());
        assertEquals(LocalDateTime.of(2026, 8, 3, 9, 0), schedule.nextRunAt());
    }

    @Test
    void parsesDailyEveningSchedule() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 31, 12, 0);

        AutomationSchedule schedule = AutomationSchedule.parse(
                "每天晚上8点整理新闻", now);

        assertEquals(com.example.ilink.application.executive.ScheduleRule.DAILY, schedule.rule());
        assertEquals(LocalDateTime.of(2026, 7, 31, 20, 0), schedule.nextRunAt());
    }

    @Test
    void recognizesScheduledNewsAutomationAndCleansSearchQuery() {
        String request = "每周一早上自动搜索大模型行业新闻，整理简报微信推送给我";
        AutomationRequestParser parser = new AutomationRequestParser();

        AutomationSpec spec = parser.parse("automation_research", request);

        assertTrue(parser.looksLikeAutomation(request));
        assertEquals("大模型行业新闻，整理简报微信推送给我", spec.query());
        assertEquals(com.example.ilink.application.executive.ScheduleRule.WEEKLY, spec.schedule().rule());
    }

    @Test
    void deduplicatesSearchResultsByUrl() {
        SearchResult first = new SearchResult("A", "one", "x", "", "https://example.com/a");
        SearchResult duplicate = new SearchResult("A2", "two", "x", "", "https://example.com/a");
        SearchResult second = new SearchResult("B", "three", "y", "", "https://example.com/b");

        List<SearchResult> results = AutomationWebSearchTool.deduplicate(List.of(first, duplicate, second));

        assertEquals(2, results.size());
        assertEquals("A", results.get(0).title());
    }

    @Test
    void blocksLocalAndPrivateUrls() {
        assertThrows(IllegalArgumentException.class,
                () -> PublicUrlPolicy.requirePublic("http://127.0.0.1/admin"));
        assertThrows(IllegalArgumentException.class,
                () -> PublicUrlPolicy.requirePublic("http://192.168.1.10/data"));
        assertThrows(IllegalArgumentException.class,
                () -> PublicUrlPolicy.requirePublic("file:///etc/passwd"));
    }

    @Test
    void buildsStructuredFallbackAnalysis() {
        String jd = AutomationAnalysisService.fallbackJd("Java 后端实习，要求 Spring、MySQL、Git");
        String match = AutomationAnalysisService.fallbackMatch(
                "Java 项目，使用 Spring 和 Git", "要求 Java、Spring、MySQL、Git");

        assertTrue(jd.contains("关键要求"));
        assertTrue(jd.contains("Java"));
        assertTrue(match.contains("基础匹配度"));
        assertTrue(match.contains("MySQL"));
    }

    @Test
    void buildsDeterministicJobSearchPlan() {
        AutomationSpec spec = new AutomationSpec(AutomationType.JOB_SEARCH,
                "找 Java 实习", "Java 实习 上海", "", "Java Spring 项目经历",
                new JobSearchSpec(List.of("上海"), "Java 后端实习", "本科", 3, 0, List.of("Java")));

        var steps = new AutomationPlanBuilder().build(spec);

        assertEquals(5, steps.size());
        assertEquals(JobSearchTool.NAME, steps.get(0).toolName());
        assertEquals(List.of(1), steps.get(1).dependsOn());
        assertEquals(List.of(2), steps.get(2).dependsOn());
        assertEquals(List.of(3), steps.get(3).dependsOn());
        assertEquals(List.of(3, 4), steps.get(4).dependsOn());
        assertTrue(steps.get(1).arguments().get("candidates").getAsString().contains("{{step:1}}"));
    }

    @Test
    void preservesCitiesEducationAndInternshipDuration() {
        AutomationSpec spec = new AutomationRequestParser().parse("job_search",
                "帮我找上海和杭州的 Java 后端实习岗位，优先考虑接受本科生、日常实习三个月以上的岗位");

        assertEquals(List.of("上海", "杭州"), spec.jobSearchSpec().cities());
        assertEquals("Java 后端实习", spec.jobSearchSpec().role());
        assertEquals("本科", spec.jobSearchSpec().education());
        assertEquals(3, spec.jobSearchSpec().minimumInternshipMonths());
    }

    @Test
    void filtersCityPagesAndKeepsRecruitmentPages() {
        JobSearchSpec spec = new JobSearchSpec(List.of("上海", "杭州"),
                "Java 后端实习", "本科", 3, 0, List.of("Java", "后端"));
        List<JobSearchTool.JobCandidate> jobs = JobSearchTool.filterCandidates(List.of(
                new SearchResult("上海市人民政府", "上海城市信息", "gov", "", "https://www.shanghai.gov.cn/"),
                new SearchResult("上海地图", "城市地图", "map", "", "https://map.baidu.com/"),
                new SearchResult("广州 Java 后端实习", "广州招聘 Java 实习生", "jobs", "",
                        "https://jobs.example.com/guangzhou"),
                new SearchResult("上海 Java 实习面经", "上海 Java 实习经验分享", "nowcoder", "",
                        "https://www.nowcoder.com/discuss/123"),
                new SearchResult("杭州 Java 实习经历", "杭州 Java 实习记录", "douyin", "",
                        "https://www.douyin.com/video/123"),
                new SearchResult("Java后端实习生招聘", "上海 本科 职位描述 Java 后端 200-300元/天",
                        "nowcoder", "", "https://www.nowcoder.com/jobs/1"),
                new SearchResult("杭州Java开发实习岗位", "杭州 招聘 本科 实习3个月 Spring Boot",
                        "career", "", "https://jobs.example.com/2")), spec);

        assertEquals(2, jobs.size());
        assertTrue(jobs.stream().noneMatch(job -> job.url().contains("gov.cn")));
        assertTrue(jobs.stream().noneMatch(job -> job.url().contains("/discuss/")));
        assertTrue(jobs.stream().noneMatch(job -> job.url().contains("guangzhou")));
        assertTrue(jobs.stream().noneMatch(job -> job.url().contains("douyin")));
        assertTrue(jobs.stream().anyMatch(job -> "上海".equals(job.matchedCity())));
        assertTrue(jobs.stream().anyMatch(job -> "杭州".equals(job.matchedCity())));
    }

    @Test
    void balancesCandidatesAcrossRequestedCities() {
        List<JobSearchTool.JobCandidate> candidates = List.of(
                new JobSearchTool.JobCandidate("上海1", "", "", "https://jobs/1", "上海", 10),
                new JobSearchTool.JobCandidate("上海2", "", "", "https://jobs/2", "上海", 9),
                new JobSearchTool.JobCandidate("上海3", "", "", "https://jobs/3", "上海", 8),
                new JobSearchTool.JobCandidate("杭州1", "", "", "https://jobs/4", "杭州", 7));

        List<JobSearchTool.JobCandidate> balanced = JobSearchTool.prioritizeCities(
                candidates, List.of("上海", "杭州"), 3);

        assertEquals(List.of("上海", "杭州", "上海"), balanced.stream()
                .map(JobSearchTool.JobCandidate::matchedCity).toList());
    }

    @Test
    void extractsJobFieldsWithoutInventingMissingValues() {
        JsonObject request = new GsonBuilderSupport().jobRequest();
        JsonObject job = new JsonObject();
        job.addProperty("title", "Java后端实习生｜示例科技");
        job.addProperty("summary", "上海 Java 后端实习招聘");
        job.addProperty("url", "https://jobs.example.com/1");
        job.addProperty("matchedCity", "上海");
        job.addProperty("sourceLevel", "page");
        job.addProperty("pageText", "职位描述：参与订单系统开发。任职要求：本科，熟悉Java和MySQL，200-300元/天，实习3个月，每周4天。");
        JsonArray jobs = new JsonArray();
        jobs.add(job);
        JsonObject data = new JsonObject();
        data.add("request", request);
        data.add("jobs", jobs);
        JsonObject arguments = new JsonObject();
        arguments.addProperty("goal", "找上海Java实习");
        arguments.addProperty("job_data", data.toString());

        String output = new JobAnalysisTool().execute(new ToolContext("u1"), arguments).output();

        assertTrue(output.contains("200-300元/天"));
        assertTrue(output.contains("本科"));
        assertTrue(output.contains("3个月"));
        assertTrue(output.contains("每周到岗：4天"));
        assertTrue(output.contains("https://jobs.example.com/1"));
    }

    @Test
    void decomposesComparisonResearchIntoTargetedQueries() {
        ResearchQueryPlanner.ResearchPlan plan = new ResearchQueryPlanner().plan(
                "帮我调研目前主流的 Java AI Agent 开发框架，对比 Spring AI、LangChain4j 和 Semantic Kernel，给出适用场景");

        assertEquals(List.of("Spring AI", "LangChain4j", "Semantic Kernel"), plan.targets());
        assertTrue(plan.queries().stream().anyMatch(query -> query.equals("Spring AI official documentation")));
        assertTrue(plan.queries().stream().noneMatch(query -> query.equals("目前")));
    }

    @Test
    void filtersIrrelevantDictionaryResults() {
        ResearchQueryPlanner.ResearchPlan plan = new ResearchQueryPlanner().plan(
                "对比 Spring AI、LangChain4j 和 Semantic Kernel");
        List<SearchResult> results = AutomationWebSearchTool.rankAndFilter(List.of(
                new SearchResult("目前是什么意思", "汉语词典", "dictionary", "", "https://example.com/current"),
                new SearchResult("Spring AI Reference", "Official Spring AI documentation", "spring.io", "",
                        "https://docs.spring.io/spring-ai/reference/"),
                new SearchResult("LangChain4j Documentation", "Agents and tools", "langchain4j.dev", "",
                        "https://docs.langchain4j.dev/"),
                new SearchResult("Semantic Kernel Java", "Microsoft agent framework", "microsoft.com", "",
                        "https://learn.microsoft.com/en-us/semantic-kernel/overview/"),
                new SearchResult("Spring AI 全面指南", "Spring AI Java Agent 教程", "blog", "",
                        "https://blog.example.com/spring-ai")), plan, 10);

        assertEquals(3, results.size());
        assertTrue(results.stream().noneMatch(result -> result.title().contains("目前")));
        assertTrue(results.stream().noneMatch(result -> result.url().contains("blog.example.com")));
    }

    @Test
    void providesOfficialSeedsForKnownFrameworks() {
        assertTrue(OfficialSourceCatalog.sourcesFor("Spring AI").stream()
                .anyMatch(result -> result.url().contains("docs.spring.io/spring-ai")));
        assertTrue(OfficialSourceCatalog.sourcesFor("LangChain4j").stream()
                .anyMatch(result -> result.url().contains("docs.langchain4j.dev")));
        assertTrue(OfficialSourceCatalog.sourcesFor("Semantic Kernel").stream()
                .anyMatch(result -> result.url().contains("microsoft/semantic-kernel")));
    }

    @Test
    void buildsDeterministicKnownFrameworkComparison() {
        String report = OfficialSourceCatalog.buildComparisonReport("对比三个框架");

        assertTrue(report.contains("Spring AI"));
        assertTrue(report.contains("LangChain4j"));
        assertTrue(report.contains("Semantic Kernel"));
        assertTrue(report.contains("未确认项"));
        assertFalse(report.contains("Kotlin"));
    }

    @Test
    void completesResearchWorkflowAndQueuesNotification() {
        SearchGateway search = (query, limit) -> List.of(
                new SearchResult("Java Agent Framework Guide",
                        "A practical Java Agent framework comparison covering tools, RAG and model integration.",
                        "example", "", "https://example.com/source"),
                new SearchResult("Java AI Agent Documentation",
                        "Official-style reference for building Java AI agents with tool calling and retrieval.",
                        "docs.example", "", "https://docs.example.com/java-agent"),
                new SearchResult("Java Agent Architecture",
                        "Architecture notes, runtime tradeoffs and production use cases for Java Agent projects.",
                        "github.com", "", "https://github.com/example/java-agent"));
        ToolManager tools = new ToolManager()
                .register(new AutomationWebSearchTool(search))
                .register(new ResearchPageFetchTool(url -> "Java Agent 正文资料，包含工具调用、RAG 和生产实践。"))
                .register(new ResearchAnalysisTool(new AutomationAnalysisService(null)))
                .register(new AutomationReportTool());
        ExecutiveTaskStore store = ExecutiveTaskStore.inMemory();
        ExecutionLogService logs = new ExecutionLogService(store);
        NotificationOutbox outbox = new NotificationOutbox(store);
        ApprovalService approvals = new ApprovalService(store);
        ExecutiveTaskService tasks = new ExecutiveTaskService(store, logs, outbox);
        ExecutiveEngine engine = new ExecutiveEngine("worker", store, new ToolCapabilityExecutor(tools),
                new DefaultResultVerifier(), approvals, logs, outbox);
        ExecutiveRuntime runtime = new ExecutiveRuntime(store, tasks, approvals, logs, outbox,
                new ExecutiveScheduler(engine));
        AutomationWorkflow workflow = new AutomationWorkflow(runtime,
                new AutomationRequestParser(), new AutomationPlanBuilder());

        ExecutiveTask task = workflow.submit("u1", "automation_research", "帮我调研 Java Agent 项目").task();
        engine.runDue(LocalDateTime.now().plusSeconds(1));
        engine.runDue(LocalDateTime.now().plusSeconds(2));
        engine.runDue(LocalDateTime.now().plusSeconds(3));
        engine.runDue(LocalDateTime.now().plusSeconds(4));

        assertEquals(TaskStatus.COMPLETED, store.findTask(task.id()).status());
        assertEquals(4, store.loadSteps(task.id()).size());
        assertTrue(store.loadSteps(task.id()).get(3).outputText().contains("https://example.com/source"));
        assertFalse(store.loadSteps(task.id()).get(3).outputText().contains("\"results\":[{"));
        assertFalse(runtime.pendingNotifications("u1", 10).isEmpty());
    }

    @Test
    void completesJobSearchWorkflowWithStructuredJobDetails() {
        SearchGateway search = (query, limit) -> {
            if (query.contains("上海")) {
                return List.of(new SearchResult("Java 后端实习生｜上海示例科技",
                        "上海招聘 Java 后端实习生，本科，实习三个月以上",
                        "jobs.example.com", "", "https://jobs.example.com/shanghai-java"));
            }
            if (query.contains("杭州")) {
                return List.of(new SearchResult("Java 开发实习生｜杭州示例网络",
                        "杭州 Java 后端实习岗位，本科生可投",
                        "career.example.com", "", "https://career.example.com/hangzhou-java"));
            }
            return List.of();
        };
        JobPageGateway pages = url -> url.contains("shanghai")
                ? "职位描述：参与订单系统 Java 后端开发。任职要求：本科，熟悉 Spring Boot 和 MySQL，200-300元/天，实习3个月，每周4天。"
                : "工作内容：参与支付平台 Java 服务开发。岗位要求：本科及以上，掌握 Redis 和数据库，250-350元/天，实习6个月，每周5天。";
        ToolManager tools = new ToolManager()
                .register(new JobSearchTool(search))
                .register(new JobPageFetchTool(pages))
                .register(new JobAnalysisTool())
                .register(new JobReportTool());
        ExecutiveTaskStore store = ExecutiveTaskStore.inMemory();
        ExecutionLogService logs = new ExecutionLogService(store);
        NotificationOutbox outbox = new NotificationOutbox(store);
        ApprovalService approvals = new ApprovalService(store);
        ExecutiveTaskService tasks = new ExecutiveTaskService(store, logs, outbox);
        ExecutiveEngine engine = new ExecutiveEngine("worker", store, new ToolCapabilityExecutor(tools),
                new DefaultResultVerifier(), approvals, logs, outbox);
        ExecutiveRuntime runtime = new ExecutiveRuntime(store, tasks, approvals, logs, outbox,
                new ExecutiveScheduler(engine));
        AutomationWorkflow workflow = new AutomationWorkflow(runtime,
                new AutomationRequestParser(), new AutomationPlanBuilder());

        String request = "帮我找上海和杭州的 Java 后端实习岗位，优先考虑接受本科生、日常实习三个月以上的岗位";
        ExecutiveTask task = workflow.submit("u1", "job_search", request).task();
        for (int second = 1; second <= 4; second++) {
            engine.runDue(LocalDateTime.now().plusSeconds(second));
        }

        ExecutiveTask completed = store.findTask(task.id());
        String report = store.loadSteps(task.id()).get(3).outputText();
        assertEquals(TaskStatus.COMPLETED, completed.status());
        assertEquals(4, store.loadSteps(task.id()).size());
        assertTrue(report.contains("上海示例科技"));
        assertTrue(report.contains("杭州示例网络"));
        assertTrue(report.contains("200-300元/天"));
        assertTrue(report.contains("本科"));
        assertTrue(report.contains("3个月"));
        assertTrue(report.contains("参与订单系统 Java 后端开发"));
        assertTrue(report.contains("https://jobs.example.com/shanghai-java"));
        assertTrue(report.contains("https://career.example.com/hangzhou-java"));
        assertFalse(runtime.pendingNotifications("u1", 10).isEmpty());
    }

    @Test
    void returnsFailureWhenSearchBackendFails() {
        AutomationWebSearchTool tool = new AutomationWebSearchTool((query, limit) -> {
            throw new IllegalStateException("offline");
        });
        JsonObject arguments = new JsonObject();
        arguments.addProperty("query", "test");
        arguments.addProperty("limit", 5);

        assertFalse(tool.execute(new ToolContext("u1"), arguments).success());
    }

    private static final class GsonBuilderSupport {
        JsonObject jobRequest() {
            JsonObject request = new JsonObject();
            JsonArray cities = new JsonArray();
            cities.add("上海");
            request.add("cities", cities);
            request.addProperty("role", "Java 后端实习");
            request.addProperty("education", "本科");
            request.addProperty("minimumInternshipMonths", 3);
            request.addProperty("daysPerWeek", 4);
            request.add("keywords", new JsonArray());
            return request;
        }
    }
}
