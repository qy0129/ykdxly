package com.example.ilink.capabilities.automation;

import com.example.ilink.application.executive.ExecutiveRuntime;
import com.example.ilink.application.executive.ExecutiveTaskService;
import com.example.ilink.application.executive.ScheduleRule;

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
        return runtime.submit(userId, spec.goal(), "wechat", "", dedupKey(userId, spec),
                "medium", null, LocalDateTime.now(), ScheduleRule.NONE, plans.build(spec));
    }

    public boolean looksLikeAutomation(String text) {
        return parser.looksLikeAutomation(text);
    }

    static String dedupKey(String userId, AutomationSpec spec) {
        try {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime window = now.withMinute(now.getMinute() / 5 * 5).withSecond(0).withNano(0);
            String value = WORKFLOW_VERSION + "|" + userId + "|" + window + "|" + spec.type() + "|"
                    + spec.goal().replaceAll("\\s+", " ").trim();
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return "automation:" + HexFormat.of().formatHex(digest).substring(0, 24);
        } catch (Exception error) {
            return "automation:" + Integer.toHexString(spec.hashCode());
        }
    }
}
