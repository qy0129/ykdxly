package com.example.ilink.capabilities.automation;

public record AutomationSpec(AutomationType type, String goal, String query,
                             String jdText, String resumeText, JobSearchSpec jobSearchSpec,
                             AutomationSchedule schedule) {
    public AutomationSpec(AutomationType type, String goal, String query,
                          String jdText, String resumeText, JobSearchSpec jobSearchSpec) {
        this(type, goal, query, jdText, resumeText, jobSearchSpec, null);
    }

    public AutomationSpec(AutomationType type, String goal, String query,
                          String jdText, String resumeText) {
        this(type, goal, query, jdText, resumeText, JobSearchSpec.empty(), null);
    }

    public AutomationSpec {
        goal = clean(goal);
        query = clean(query);
        jdText = clean(jdText);
        resumeText = clean(resumeText);
        jobSearchSpec = jobSearchSpec == null ? JobSearchSpec.empty() : jobSearchSpec;
        schedule = schedule == null ? AutomationSchedule.parse("", java.time.LocalDateTime.now()) : schedule;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
