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
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 安全抓取候选岗位正文；失败时保留搜索摘要。 */
public final class JobPageFetchTool implements Tool {
    public static final String NAME = "automation_job_page_fetch";
    private static final int MAX_BYTES = 1024 * 1024;
    private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);
    private static final Pattern CHARSET = Pattern.compile("(?i)charset=([a-zA-Z0-9._-]+)");
    private final JobPageGateway pages;

    public JobPageFetchTool(HttpClient client) {
        this(url -> fetchHttp(client, url));
    }

    public JobPageFetchTool(JobPageGateway pages) {
        this.pages = pages;
    }

    @Override
    public ToolDefinition definition() {
        JsonObject properties = new JsonObject();
        properties.add("candidates", ToolDefinition.stringProperty("岗位搜索工具返回的结构化候选"));
        return new ToolDefinition(NAME, "抓取岗位正文", "读取候选招聘页面，抓取失败时保留搜索摘要",
                ToolDefinition.objectParameters(properties, "candidates"), true);
    }

    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        try {
            JsonObject source = JsonParser.parseString(arguments.get("candidates").getAsString()).getAsJsonObject();
            JsonArray jobs = source.getAsJsonArray("jobs");
            if (jobs == null || jobs.isEmpty()) return ToolResult.failure("没有可抓取的岗位候选");
            JsonArray enriched = new JsonArray();
            for (int index = 0; index < Math.min(jobs.size(), 8); index++) {
                JsonObject job = jobs.get(index).getAsJsonObject().deepCopy();
                String url = string(job, "url");
                try {
                    String pageText = pages.fetch(url);
                    if (blocked(pageText)) throw new IllegalStateException("页面要求登录、验证码或安全验证");
                    if (!jobContent(pageText)) throw new IllegalStateException("页面不是可识别的岗位正文");
                    job.addProperty("pageText", limit(pageText, 12000));
                    job.addProperty("sourceLevel", "page");
                    job.addProperty("fetchedAt", Instant.now().toString());
                } catch (Exception error) {
                    job.addProperty("pageText", "");
                    job.addProperty("sourceLevel", "snippet");
                    job.addProperty("fetchError", error.getMessage());
                }
                enriched.add(job);
            }
            JsonObject output = new JsonObject();
            output.add("request", source.get("request"));
            output.add("queries", source.get("queries"));
            output.add("jobs", enriched);
            output.addProperty("untrustedContent", true);
            return ToolResult.success(output.toString());
        } catch (Exception error) {
            return ToolResult.failure("岗位正文处理失败：" + error.getMessage());
        }
    }

    private static String fetchHttp(HttpClient client, String value) throws Exception {
        URI current = PublicUrlPolicy.requirePublic(value);
        for (int redirect = 0; redirect <= 3; redirect++) {
            HttpRequest request = HttpRequest.newBuilder(current).timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "Mozilla/5.0 iLinkBot-JobSearch/1.0").GET().build();
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
            try (InputStream input = response.body()) {
                bytes = input.readNBytes(MAX_BYTES + 1);
            }
            if (bytes.length > MAX_BYTES) throw new IllegalStateException("岗位页面超过 1 MB");
            return cleanHtml(new String(bytes, charset(contentType)));
        }
        return "";
    }

    static String cleanHtml(String value) {
        return value.replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&")
                .replaceAll("\\s+", " ").trim();
    }

    private static boolean blocked(String text) {
        String value = text == null ? "" : text;
        return value.matches("(?is).*(登录后查看|请先登录|验证码|安全验证|访问过于频繁|人机验证|异常访问).*" );
    }

    private static boolean jobContent(String text) {
        String value = text == null ? "" : text;
        return value.matches("(?is).*(岗位|职位|实习|招聘|任职要求|工作内容|职位描述|薪资).*" );
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
        String text = value == null ? "" : value;
        return text.substring(0, Math.min(text.length(), max));
    }
}
