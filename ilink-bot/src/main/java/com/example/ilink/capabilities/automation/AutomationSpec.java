package com.example.ilink.capabilities.automation;

public record AutomationSpec(AutomationType type, String goal, String query,
                             String jdText, String resumeText, JobSearchSpec jobSearchSpec) {
    public AutomationSpec(AutomationType type, String goal, String query,
                          String jdText, String resumeText) {
        this(type, goal, query, jdText, resumeText, JobSearchSpec.empty());
    }

    public AutomationSpec {
        goal = clean(goal);
        query = clean(query);
        jdText = clean(jdText);
        resumeText = clean(resumeText);
        jobSearchSpec = jobSearchSpec == null ? JobSearchSpec.empty() : jobSearchSpec;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
