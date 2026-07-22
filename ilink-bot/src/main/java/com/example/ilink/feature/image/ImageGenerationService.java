package com.example.ilink.feature.image;

import com.example.ilink.config.Config;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * 图片生成和编辑服务。
 *
 * <p>调用图片生成接口创建新图片，也可以将本地图片编码后提交给图片编辑模型，
 * 最终返回图片字节供 MediaStore 或微信 SDK 使用。</p>
 */
public final class ImageGenerationService {

    private final HttpClient httpClient;
    private final Gson gson = new Gson();

    /** 创建图片生成服务并注入 HTTP 客户端。 */
    public ImageGenerationService(HttpClient httpClient) {
        this.httpClient = httpClient;
    }
    /** 调用图片生成模型并下载返回的图片。 */
    public byte[] generateImage(String prompt, String imageSize) throws Exception {
        System.out.println("[绘图] 开始生成: " + prompt + " 尺寸: " + imageSize);
        JsonObject body = new JsonObject();
        body.addProperty("model", Config.DRAW_MODEL);
        body.addProperty("prompt", prompt);
        body.addProperty("image_size", imageSize);
        body.addProperty("batch_size", 1);
        body.addProperty("num_inference_steps", 20);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(Config.DRAW_API_URL))
                .timeout(Config.REQ_TIMEOUT)
                .header("Authorization", "Bearer " + Config.API_KEY)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            System.err.println("[绘图] API 错误: " + response.statusCode() + " " + response.body());
            return null;
        }

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        String imageUrl = json.getAsJsonArray("images").get(0).getAsJsonObject().get("url").getAsString();
        System.out.println("[绘图] 图片URL: " + imageUrl);

        HttpRequest downloadReq = HttpRequest.newBuilder()
                .uri(URI.create(imageUrl))
                .timeout(Config.REQ_TIMEOUT)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .GET()
                .build();

        HttpResponse<byte[]> imgResponse = httpClient.send(downloadReq, HttpResponse.BodyHandlers.ofByteArray());

        if (imgResponse.statusCode() == 200) {
            String contentType = imgResponse.headers().firstValue("Content-Type").orElse("");
            System.out.println("[绘图] 下载成功: " + imgResponse.body().length + " bytes, Content-Type: " + contentType);
            if (contentType.startsWith("image/") || contentType.startsWith("application/octet-stream")) {
                return imgResponse.body();
            }
            System.err.println("[绘图] 返回的不是图片: " + contentType);
            System.err.println("[绘图] 内容前200字节: " + new String(imgResponse.body(), 0, Math.min(200, imgResponse.body().length)));
            return null;
        }

        System.err.println("[绘图] 下载失败: " + imgResponse.statusCode());
        return null;
    }

    /** 上传本地图片并调用图片编辑模型。 */
    public byte[] editImage(Path sourceImage, String prompt) throws Exception {
        byte[] imageBytes = Files.readAllBytes(sourceImage);
        JsonObject body = new JsonObject();
        body.addProperty("model", Config.IMAGE_EDIT_MODEL);
        body.addProperty("prompt", prompt);
        body.addProperty("image", "data:image/png;base64," + Base64.getEncoder().encodeToString(imageBytes));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(Config.DRAW_API_URL))
                .timeout(Config.REQ_TIMEOUT)
                .header("Authorization", "Bearer " + Config.API_KEY)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            System.err.println("[图生图] API 错误: " + response.statusCode() + " " + response.body());
            return null;
        }
        return downloadGeneratedImage(response.body());
    }

    /** 从图片接口响应中提取 URL 并下载图片字节。 */
    private byte[] downloadGeneratedImage(String responseBody) throws Exception {
        JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
        String imageUrl = json.getAsJsonArray("images").get(0).getAsJsonObject().get("url").getAsString();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(imageUrl))
                .timeout(Config.REQ_TIMEOUT)
                .header("User-Agent", "Mozilla/5.0")
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        return response.statusCode() == 200 ? response.body() : null;
    }
}
