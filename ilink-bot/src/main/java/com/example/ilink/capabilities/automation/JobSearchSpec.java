package com.example.ilink.capabilities.automation;

import java.util.List;

public record JobSearchSpec(List<String> cities, String role, String education,
                            int minimumInternshipMonths, int daysPerWeek,
                            List<String> keywords) {
    public JobSearchSpec {
        cities = cities == null ? List.of() : List.copyOf(cities);
        role = role == null ? "" : role.trim();
        education = education == null ? "" : education.trim();
        minimumInternshipMonths = Math.max(0, minimumInternshipMonths);
        daysPerWeek = Math.max(0, daysPerWeek);
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
    }

    public static JobSearchSpec empty() {
        return new JobSearchSpec(List.of(), "", "", 0, 0, List.of());
    }
}
