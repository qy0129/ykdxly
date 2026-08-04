package com.changlu.planner.agent.subagents.research;

import com.changlu.planner.shared.config.EnvironmentConfig;
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
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** Research Subagent 的公开网页搜索工具。 */
public final class WebSearchTool {
  public record Result(String title, String summary, String source, String url) {}

  private final HttpClient http = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(5))
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build();
  private final int timeoutSeconds = Integer.parseInt(EnvironmentConfig.value(
      "WEB_SEARCH_TIMEOUT_SECONDS", "web.search.timeout.seconds", "12"));
  private volatile Cache cache;

  public List<Result> search(String query, int limit, boolean refresh) {
    String normalized = query == null ? "" : query.trim();
    if (normalized.isBlank()) return List.of();
    int requestedLimit = Math.max(1, Math.min(limit, 10));
    Cache current = cache;
    if (!refresh && current != null && current.query().equals(normalized)
        && current.loadedAt().isAfter(LocalDateTime.now().minusMinutes(10))) {
      return current.items().stream().limit(requestedLimit).toList();
    }
    try {
      URI uri = URI.create("https://www.bing.com/search?format=rss&q="
          + URLEncoder.encode(normalized, StandardCharsets.UTF_8));
      HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(timeoutSeconds))
          .header("User-Agent", "Mozilla/5.0 ChangluPlanner/1.0").GET().build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode());
      List<Result> items = parse(response.body(), requestedLimit);
      cache = new Cache(normalized, LocalDateTime.now(), items);
      return items;
    } catch (Exception error) {
      System.err.println("[网页搜索] 搜索失败: " + error.getMessage());
      return List.of();
    }
  }

  private List<Result> parse(String xml, int limit) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setExpandEntityReferences(false);
    NodeList nodes = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)))
        .getElementsByTagName("item");
    List<Result> results = new ArrayList<>();
    for (int index = 0; index < nodes.getLength() && results.size() < limit; index++) {
      Element item = (Element) nodes.item(index);
      String title = child(item, "title");
      String url = child(item, "link");
      if (title.isBlank() || !(url.startsWith("https://") || url.startsWith("http://"))) continue;
      String host = URI.create(url).getHost();
      String summary = clean(child(item, "description"));
      if (isLowQuality(title, host, summary)) continue;
      results.add(new Result(title, summary, host == null ? "网页" : host, url));
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

  private boolean isLowQuality(String title, String host, String summary) {
    String page = ((host == null ? "" : host) + " " + title + " " + summary).toLowerCase();
    return page.contains("baike.baidu.com") || page.contains("hanyuguoxue.com")
        || page.contains("hgcha.com") || page.contains("gushici.net")
        || page.contains("hancibao.com") || page.contains("zidian")
        || page.contains("cidian") || page.contains("词典") || page.contains("字典")
        || page.contains("拼音") || page.contains("部首") || page.contains("笔顺")
        || page.contains("汉字解释");
  }

  private record Cache(String query, LocalDateTime loadedAt, List<Result> items) {}
}
