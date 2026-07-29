package com.example.ilink.application.tooling.mcp;

import com.example.ilink.application.tooling.ToolResult;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** 基于 HTTP JSON-RPC 的标准 MCP 客户端。 */
public final class HttpMcpClient implements McpClient {
    private final HttpClient httpClient;
    private final URI endpoint;
    private final String authorization;
    private final AtomicLong requestIds = new AtomicLong(1);
    private final Gson gson = new Gson();

    public HttpMcpClient(HttpClient httpClient, URI endpoint, String authorization) {
        this.httpClient = httpClient;
        this.endpoint = endpoint;
        this.authorization = authorization == null ? "" : authorization.trim();
    }

    @Override
    public List<McpTool> listTools() throws Exception {
        JsonObject result = request("tools/list", new JsonObject());
        JsonArray tools = result.getAsJsonArray("tools");
        if (tools == null) return List.of();
        List<McpTool> values = new ArrayList<>();
        for (JsonElement item : tools) {
            JsonObject tool = item.getAsJsonObject();
            JsonObject schema = tool.has("inputSchema") && tool.get("inputSchema").isJsonObject()
                    ? tool.getAsJsonObject("inputSchema") : new JsonObject();
            values.add(new McpTool(value(tool, "name"), value(tool, "description"), schema));
        }
        return List.copyOf(values);
    }

    @Override
    public ToolResult callTool(String toolName, JsonObject arguments) throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("name", toolName);
        params.add("arguments", arguments == null ? new JsonObject() : arguments);
        JsonObject result = request("tools/call", params);
        if (result.has("isError") && result.get("isError").getAsBoolean()) return ToolResult.failure(content(result));
        return ToolResult.success(content(result), result);
    }

    private JsonObject request(String method, JsonObject params) throws Exception {
        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", "2.0");
        request.addProperty("id", requestIds.getAndIncrement());
        request.addProperty("method", method);
        request.add("params", params);
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(request)));
        if (!authorization.isBlank()) builder.header("Authorization", authorization);
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("MCP HTTP " + response.statusCode());
        JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
        if (body.has("error")) throw new IllegalStateException(value(body.getAsJsonObject("error"), "message"));
        return body.getAsJsonObject("result");
    }

    private static String content(JsonObject result) {
        JsonArray content = result.getAsJsonArray("content");
        if (content == null || content.isEmpty()) return result.toString();
        StringBuilder text = new StringBuilder();
        for (JsonElement item : content) {
            JsonObject value = item.getAsJsonObject();
            if (value.has("text")) text.append(value.get("text").getAsString()).append('\n');
        }
        return text.toString().trim();
    }

    private static String value(JsonObject object, String name) {
        return object != null && object.has(name) ? object.get(name).getAsString() : "";
    }
}
