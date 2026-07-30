package com.example.ilink.capabilities.life;

import com.example.ilink.capabilities.planning.PlanTask;
import com.example.ilink.capabilities.web.SearchResult;

import java.util.ArrayList;
import java.util.List;

/** 根据学习目标生成每日可执行内容，联网资料只作为可追溯来源。 */
public final class StudyPlanBuilder {

    public List<PlanTask> build(StudyPlanDraft draft, List<SearchResult> resources) {
        List<PlanTask> tasks = new ArrayList<>();
        for (int day = 1; day <= draft.periodDays(); day++) {
            String phase = phase(day, draft.periodDays());
            String topic = topic(draft.subject(), day, draft.periodDays(), phase);
            String title = "第" + day + "天：" + draft.subject() + " - " + topic;
            String source = resource(resources, day - 1);
            String description = "学习目标：理解并能复述“" + topic + "”的核心方法。"
                    + "\n执行内容：概念学习占 40%，例题跟练占 30%，独立练习占 30%。"
                    + "\n验收标准：不看答案完成至少 3 道对应练习，并记录错因和仍未理解的问题。"
                    + "\n当前基础：" + draft.level() + "；阶段目标：" + draft.target() + "。"
                    + (source.isBlank() ? "" : "\n参考来源：" + source);
            tasks.add(new PlanTask("DRAFT-" + day, title, description,
                    draft.dailyMinutes(), priority(day, draft.periodDays()), "", "pending"));
        }
        return tasks;
    }

    public List<String> sourceUrls(List<SearchResult> resources) {
        if (resources == null) return List.of();
        return resources.stream().map(SearchResult::url)
                .filter(url -> url != null && !url.isBlank()).distinct().limit(5).toList();
    }

    private String resource(List<SearchResult> resources, int index) {
        if (resources == null || resources.isEmpty()) return "";
        SearchResult item = resources.get(index % resources.size());
        return item.title() + " - " + item.url();
    }

    private String phase(int day, int total) {
        double progress = day / (double) Math.max(1, total);
        if (day == 1) return "诊断基础并建立知识地图";
        if (progress <= 0.35) return "核心概念与基础例题";
        if (progress <= 0.7) return "典型方法与专项练习";
        if (progress < 1) return "综合应用与错题回练";
        return "模拟检验与阶段复盘";
    }

    private String priority(int day, int total) {
        return day == 1 || day == total ? "high" : "medium";
    }

    private String topic(String subject, int day, int total, String fallback) {
        List<String> topics;
        if (subject.matches(".*(高数|高等数学|微积分).*")) {
            topics = List.of("函数、极限与连续", "导数定义与求导法则", "微分中值定理与导数应用",
                    "不定积分与换元积分", "定积分及其应用", "多元函数微分", "重积分与曲线积分",
                    "无穷级数", "常微分方程", "综合题与阶段复盘");
        } else if (subject.matches(".*(Java|java).*")) {
            topics = List.of("语法与面向对象基础", "集合框架与泛型", "异常、IO 与序列化", "并发基础",
                    "JVM 与内存模型", "Spring 核心", "数据库与事务", "项目实战", "测试与调优", "综合复盘");
        } else {
            return fallback;
        }
        if (day == total) return topics.getLast();
        int index = Math.min(topics.size() - 2,
                (int) Math.floor((day - 1) * (topics.size() - 1) / (double) Math.max(1, total - 1)));
        return topics.get(index);
    }
}
