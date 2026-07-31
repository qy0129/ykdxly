package com.example.ilink.capabilities.automation;

import com.example.ilink.application.executive.ExecutiveRuntime;
import com.example.ilink.application.executive.ExecutiveTaskService;
import com.example.ilink.application.executive.ScheduleRule;
import com.example.ilink.bootstrap.Config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;

/** 把用户请求提交给 Executive Core，Automation 本身不维护任务状态。 */
public final class AutomationWorkflow {
    private static final String WORKFLOW_VERSION = "research-v2";
    private final ExecutiveRuntime runtime;
    private final AutomationRequestParser parser;
    private final AutomationPlanBuilder plans;

    public AutomationWorkflow(ExecutiveRuntime runtime, AutomationRequestParser parser,
                              AutomationPlanBuilder plans) {
        this.runtime = runtime;
        this.parser = parser;
        this.plans = plans;
    }

    public ExecutiveTaskService.Submission submit(String userId, String intent, String text) {
        AutomationSpec spec = parser.parse(intent, text);
        LocalDateTime deadline = spec.schedule().nextRunAt().plus(Config.AUTOMATION_TASK_TIMEOUT);
        return runtime.submit(userId, spec.goal(), "wechat", "", dedupKey(userId, spec),
                "medium", deadline, spec.schedule().nextRunAt(),
                spec.schedule().rule(), plans.build(spec));
    }

    public boolean looksLikeAutomation(String text) {
        return parser.looksLikeAutomation(text);
    }

    static String dedupKey(String userId, AutomationSpec spec) {
        try {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime window = now.withMinute(now.getMinute() / 5 * 5).withSecond(0).withNano(0);
            String windowKey = spec.schedule().rule() == ScheduleRule.NONE ? window.toString() : "recurring";
            String value = WORKFLOW_VERSION + "|" + userId + "|" + windowKey + "|" + spec.type() + "|"
                    + spec.schedule().rule() + "|"
                    + spec.goal().replaceAll("\\s+", " ").trim();
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return "automation:" + HexFormat.of().formatHex(digest).substring(0, 24);
        } catch (Exception error) {
            return "automation:" + Integer.toHexString(spec.hashCode());
        }
    }
}
