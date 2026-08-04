package com.changlu.planner.agent.subagents.document;

import com.changlu.planner.shared.config.EnvironmentConfig;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

/** 使用视觉模型识别图片和扫描版 PDF 中的文字。 */
final class VisionOcrTool {
  private final Gson gson = new Gson();
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
  private final String apiKey = EnvironmentConfig.value("PLANNER_AI_API_KEY", "api.key", "");
  private final String apiUrl = EnvironmentConfig.value(
      "PLANNER_AI_API_URL", "api.url", "https://api.siliconflow.cn/v1/chat/completions");
  private final String model = EnvironmentConfig.value(
      "PLANNER_AI_VISION_MODEL", "ai.vision.model", "Qwen/Qwen3-VL-32B-Instruct");
  private final int maxPdfPages = Integer.parseInt(EnvironmentConfig.value(
      "PLANNER_DOCUMENT_OCR_MAX_PAGES", "document.ocr.max.pages", "12"));

  boolean configured() { return !apiKey.isBlank(); }

  String recognizePdf(byte[] bytes) throws Exception {
    if (!configured()) return "";
    StringBuilder text = new StringBuilder();
    try (PDDocument document = Loader.loadPDF(bytes)) {
      PDFRenderer renderer = new PDFRenderer(document);
      int pages = Math.min(document.getNumberOfPages(), maxPdfPages);
      for (int index = 0; index < pages; index++) {
        BufferedImage image = renderer.renderImageWithDPI(index, 110);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        String page = recognizeImage(output.toByteArray());
        if (!page.isBlank()) text.append("----- 第 ").append(index + 1).append(" 页 -----\n")
            .append(page).append('\n');
      }
    }
    return text.toString().strip();
  }

  String recognizeImage(byte[] bytes) throws Exception {
    if (!configured()) throw new IllegalStateException("图片识别需要配置 PLANNER_AI_API_KEY");
    BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
    if (image == null) throw new IllegalArgumentException("无法读取图片内容");
    ByteArrayOutputStream normalized = new ByteArrayOutputStream();
    ImageIO.write(image, "png", normalized);

    JsonArray content = new JsonArray();
    JsonObject textPart = new JsonObject();
    textPart.addProperty("type", "text");
    textPart.addProperty("text", "提取图片中的全部文字，保持原有段落和表格顺序，只输出识别到的文字。");
    content.add(textPart);
    JsonObject imagePart = new JsonObject();
    imagePart.addProperty("type", "image_url");
    JsonObject imageUrl = new JsonObject();
    imageUrl.addProperty("url", "data:image/png;base64,"
        + Base64.getEncoder().encodeToString(normalized.toByteArray()));
    imagePart.add("image_url", imageUrl);
    content.add(imagePart);

    JsonObject user = new JsonObject();
    user.addProperty("role", "user");
    user.add("content", content);
    JsonArray messages = new JsonArray();
    messages.add(user);
    JsonObject body = new JsonObject();
    body.addProperty("model", model);
    body.addProperty("max_tokens", 3000);
    body.add("messages", messages);

    HttpRequest request = HttpRequest.newBuilder(URI.create(apiUrl)).timeout(Duration.ofSeconds(120))
        .header("Authorization", "Bearer " + apiKey)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8)).build();
    HttpResponse<String> response = http.send(request,
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() / 100 != 2) {
      throw new IllegalStateException("图片识别服务返回 " + response.statusCode());
    }
    return JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("choices").get(0)
        .getAsJsonObject().getAsJsonObject("message").get("content").getAsString().trim();
  }
}
