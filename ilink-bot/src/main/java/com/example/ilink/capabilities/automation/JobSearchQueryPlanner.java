package com.example.ilink.capabilities.automation;

import java.util.LinkedHashSet;
import java.util.List;

/** 按城市和用户约束生成短岗位查询。 */
public final class JobSearchQueryPlanner {
    public List<String> plan(JobSearchSpec spec, String fallbackQuery) {
        List<String> cities = spec.cities().isEmpty() ? List.of("") : spec.cities();
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        for (String city : cities) {
            String base = (city + " " + spec.role() + " " + spec.education())
                    .replaceAll("\\s+", " ").trim();
            queries.add(base + " 招聘 职位");
            queries.add(base + " 日常实习 "
                    + (spec.minimumInternshipMonths() > 0 ? spec.minimumInternshipMonths() + "个月" : ""));
            queries.add("site:nowcoder.com " + base + " 实习");
        }
        if (queries.stream().allMatch(String::isBlank) && fallbackQuery != null && !fallbackQuery.isBlank()) {
            queries.add(fallbackQuery + " 招聘 实习 职位");
        }
        return queries.stream().map(String::trim).filter(value -> !value.isBlank()).limit(8).toList();
    }
}
