package com.example.ilink.capabilities.automation;

import com.example.ilink.application.tooling.Tool;
import com.example.ilink.application.tooling.ToolContext;
import com.example.ilink.application.tooling.ToolDefinition;
import com.example.ilink.application.tooling.ToolResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 抓取调研搜索结果正文，并保留每条资料的抓取状态。 */
public final class ResearchPageFetchTool implements Tool {
    public static final String NAME = "automation_research_page_fetch";
    private static final int MAX_BYTES = 1024 * 1024;
    private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);
    private static final Pattern CHARSET = Pattern.compile("(?i)charset=([a-zA-Z0-9._-]+)");
    private final ResearchPageGateway pages;

    public ResearchPageFetchTool(HttpClient client) {
        this(url -> fetchHttp(client, url));
    }

    public ResearchPageFetchTool(ResearchPageGateway pages) {
        this.pages = pages;
    }

    @Override
    public ToolDefinition definition() {
        JsonObject properties = new JsonObject();
        properties.add("research", ToolDefinition.stringProperty("检索步骤返回的结构化资料"));
        return new ToolDefinition(NAME, "抓取调研来源正文", "读取公开来源正文并保留抓取证据",
                ToolDefinition.objectParameters(properties, "research"), true);
    }

    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        try {
            JsonObject source = JsonParser.parseString(arguments.get("research").getAsString()).getAsJsonObject();
            JsonArray results = source.getAsJsonArray("results");
            if (results == null || results.isEmpty()) return ToolResult.failure("没有可抓取的调研来源");
            JsonArray enriched = new JsonArray();
            for (int index = 0; index < Math.min(12, results.size()); index++) {
                JsonObject item = results.get(index).getAsJsonObject().deepCopy();
                try {
                    String pageText = pages.fetch(string(item, "url"));
                    if (pageText == null || pageText.isBlank()) throw new IllegalStateException("网页没有可读取正文");
                    item.addProperty("pageText", limit(pageText, 20000));
                    item.addProperty("sourceLevel", "page");
                    item.addProperty("fetchedAt", Instant.now().toString());
                } catch (Exception error) {
                    item.addProperty("pageText", "");
                    item.addProperty("sourceLevel", "snippet");
                    item.addProperty("fetchError", error.getMessage());
                }
                enriched.add(item);
            }
            JsonObject output = source.deepCopy();
            output.add("results", enriched);
            output.addProperty("untrustedContent", true);
            return ToolResult.success(output.toString());
        } catch (Exception error) {
            return ToolResult.failure("调研正文抓取失败：" + error.getMessage());
        }
    }

    private static String fetchHttp(HttpClient client, String value) throws Exception {
        URI current = PublicUrlPolicy.requirePublic(value);
        for (int redirect = 0; redirect <= 3; redirect++) {
            HttpRequest request = HttpRequest.newBuilder(current).timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "Mozilla/5.0 iLinkBot-Research/1.0").GET().build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (REDIRECTS.contains(response.statusCode())) {
                response.body().close();
                if (redirect == 3) throw new IllegalStateException("网页重定向次数过多");
                String location = response.headers().firstValue("Location")
                        .orElseThrow(() -> new IllegalStateException("网页重定向缺少地址"));
                current = PublicUrlPolicy.requirePublic(current.resolve(location).toString());
                continue;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                response.body().close();
                throw new IllegalStateException("HTTP " + response.statusCode());
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("text/html");
            if (!contentType.toLowerCase().matches(".*(?:text/html|text/plain|application/xhtml\\+xml).*")) {
                response.body().close();
                throw new IllegalStateException("不支持的网页类型：" + contentType);
            }
            byte[] bytes;
            try (InputStream body = response.body()) {
                bytes = body.readNBytes(MAX_BYTES + 1);
            }
            if (bytes.length > MAX_BYTES) throw new IllegalStateException("网页内容超过 1 MB");
            Charset charset = charset(contentType);
            String text = JobPageFetchTool.cleanHtml(new String(bytes, charset));
            if (text.isBlank()) throw new IllegalStateException("网页没有可读取正文");
            return text;
        }
        throw new IllegalStateException("网页抓取失败");
    }

    private static Charset charset(String contentType) {
        Matcher matcher = CHARSET.matcher(contentType);
        if (!matcher.find()) return StandardCharsets.UTF_8;
        try {
            return Charset.forName(matcher.group(1));
        } catch (Exception ignored) {
            return StandardCharsets.UTF_8;
        }
    }

    private static String string(JsonObject object, String name) {
        return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : "";
    }

    private static String limit(String value, int max) {
        return value.substring(0, Math.min(max, value.length()));
    }
}
