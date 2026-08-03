package com.changlu.planner.features.briefing;

import com.changlu.planner.shared.config.EnvironmentConfig;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Real-time plan-related news tool used by the briefing sub-agent. */
final class NewsTool {
  record News(String title, String summary, String source, String url) {}
  private final HttpClient http = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(5))
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build();
  private final int timeoutSeconds = Integer.parseInt(EnvironmentConfig.value(
      "WEB_SEARCH_TIMEOUT_SECONDS", "web.search.timeout.seconds", "12"));
  private final int resultLimit = Integer.parseInt(EnvironmentConfig.value(
      "WEB_SEARCH_RESULT_LIMIT", "web.search.result.limit", "3"));
  private volatile Cache cache;

  List<News> planNews(List<String> planTitles) {
    if (!Boolean.parseBoolean(EnvironmentConfig.value("BRIEFING_NEWS_ENABLED", "briefing.news.enabled", "true"))) return List.of();
    String topic = publicTopics(planTitles);
    Cache current = cache;
    if (current != null && current.topic().equals(topic) && current.loadedAt().isAfter(LocalDateTime.now().minusMinutes(15))) {
      return current.items();
    }
    try {
      String query = topic + " 今日最新新闻";
      URI uri = URI.create("https://www.bing.com/search?format=rss&q="
          + URLEncoder.encode(query, StandardCharsets.UTF_8));
      HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(timeoutSeconds))
          .header("User-Agent", "Mozilla/5.0 ChangluPlanner/1.0").GET().build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode());
      List<News> items = parse(response.body(), resultLimit);
      cache = new Cache(topic, LocalDateTime.now(), items);
      return items;
    } catch (Exception error) {
      System.err.println("[简报新闻工具] 搜索失败: " + error.getMessage());
      return List.of();
    }
  }

  // Only public topic categories leave the machine; private plan titles never become search queries.
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

  private List<News> parse(String xml, int limit) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setExpandEntityReferences(false);
    NodeList nodes = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml))).getElementsByTagName("item");
    List<News> results = new ArrayList<>();
    for (int index = 0; index < nodes.getLength() && results.size() < limit; index++) {
      Element item = (Element) nodes.item(index);
      String title = child(item, "title");
      String url = child(item, "link");
      if (title.isBlank() || !(url.startsWith("https://") || url.startsWith("http://"))) continue;
      String host = URI.create(url).getHost();
      results.add(new News(title, clean(child(item, "description")), host == null ? "新闻网页" : host, url));
    }
    return List.copyOf(results);
  }

  private String child(Element item, String name) {
    NodeList values = item.getElementsByTagName(name);
    return values.getLength() == 0 ? "" : values.item(0).getTextContent().trim();
  }

  private String clean(String value) {
    String text = value.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    return text.length() <= 100 ? text : text.substring(0, 100) + "...";
  }

  private record Cache(String topic, LocalDateTime loadedAt, List<News> items) {}
}
