package com.changlu.planner.agent.subagents.briefing;

import com.changlu.planner.agent.subagents.research.WebSearchTool;
import com.changlu.planner.shared.config.EnvironmentConfig;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 将公开网页搜索收敛为适合每日简报的计划主题新闻。 */
final class NewsTool {
  record News(String title, String summary, String source, String url) {}

  private final WebSearchTool search;
  private final int resultLimit = Integer.parseInt(EnvironmentConfig.value(
      "WEB_SEARCH_RESULT_LIMIT", "web.search.result.limit", "3"));

  NewsTool(WebSearchTool search) { this.search = search; }

  List<News> planNews(List<String> planTitles) {
    if (!Boolean.parseBoolean(EnvironmentConfig.value(
        "BRIEFING_NEWS_ENABLED", "briefing.news.enabled", "true"))) return List.of();
    return search.search(publicTopics(planTitles) + " 今日最新新闻", resultLimit, false).stream()
        .map(item -> new News(item.title(), item.summary(), item.source(), item.url()))
        .toList();
  }

  private String publicTopics(List<String> planTitles) {
    Set<String> topics = new LinkedHashSet<>();
    for (String raw : planTitles) {
      String title = raw == null ? "" : raw.toLowerCase();
      if (title.matches(".*(ai|人工智能|agent|智能体|产品|软件|编程|开发).*")) topics.add("人工智能科技");
      if (title.matches(".*(学习|数学|高数|课程|阅读|考试|知识).*")) topics.add("教育学习");
      if (title.matches(".*(生活|运动|健康|睡眠|饮食).*")) topics.add("健康生活");
      if (title.matches(".*(旅行|旅居|出行).*")) topics.add("旅游出行");
      if (topics.size() >= 2) break;
    }
    return topics.isEmpty() ? "效率工具" : String.join(" ", topics);
  }
}
