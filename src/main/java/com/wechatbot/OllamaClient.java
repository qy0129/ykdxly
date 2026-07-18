package com.wechatbot;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

public class OllamaClient {
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final String baseUrl;
    private final String model;

    private static final String SYSTEM_PROMPT = "用中文简短回答。";

    public OllamaClient(String baseUrl, String model) {
        this.baseUrl = baseUrl;
        this.model = model;
    }

    public String chat(String text) throws Exception {
        return chat(text, null);
    }

    public String chat(String text, byte[] imageData) throws Exception {
        var body = json.createObjectNode();
        body.put("model", model);
        body.put("prompt", text);
        body.put("system", SYSTEM_PROMPT);
        body.put("stream", false);
        var opts = json.createObjectNode();
        opts.put("num_predict", 256);
        body.set("options", opts);

        if (imageData != null) {
            var images = json.createArrayNode();
            images.add(Base64.getEncoder().encodeToString(imageData));
            body.set("images", images);
        }

        for (int i = 0; i < 2; i++) {
            try {
                var req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/generate"))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                        .build();
                var res = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() != 200) throw new RuntimeException("HTTP " + res.statusCode());
                var root = json.readTree(res.body());
                if (root.has("response")) return root.get("response").asText().trim();
            } catch (Exception e) {
                if (i < 1) {
                    System.out.println("Ollama 重试 " + (i + 1) + "/2: " + e.getMessage());
                    Thread.sleep(1000);
                } else {
                    throw e;
                }
            }
        }
        return "";
    }

    public String transcribe(byte[] audioData) {
        // 1) 保存为临时文件
        java.io.File tmpFile = null;
        java.io.File wavFile = null;
        try {
            tmpFile = java.io.File.createTempFile("voice_", ".silk");
            java.nio.file.Files.write(tmpFile.toPath(), audioData);
            wavFile = new java.io.File(tmpFile.getAbsolutePath().replace(".silk", ".wav"));

            // 2) 尝试 ffmpeg 转码 SILK → WAV
            boolean ffmpegOk = false;
            try {
                var pb = new ProcessBuilder("ffmpeg", "-y", "-i", tmpFile.getAbsolutePath(),
                        "-ar", "16000", "-ac", "1", "-sample_fmt", "s16", wavFile.getAbsolutePath());
                pb.redirectErrorStream(true);
                var proc = pb.start();
                proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
                ffmpegOk = proc.exitValue() == 0 && wavFile.exists() && wavFile.length() > 0;
            } catch (Exception ignored) {}

            byte[] wavBytes = ffmpegOk ? java.nio.file.Files.readAllBytes(wavFile.toPath()) : audioData;

            // 3) 调用 Ollama whisper API
            var body = json.createObjectNode();
            body.put("model", "whisper");
            body.put("prompt", "");
            body.put("stream", false);
            body.put("audio", Base64.getEncoder().encodeToString(wavBytes));

            var req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/generate"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            var res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 200) {
                var root = json.readTree(res.body());
                if (root.has("response")) return root.get("response").asText().trim();
            }
        } catch (Exception e) {
            System.out.println("Whisper 转写失败: " + e.getMessage());
        } finally {
            if (tmpFile != null) tmpFile.delete();
            if (wavFile != null) wavFile.delete();
        }
        return "";
    }

    public boolean isAlive() {
        try {
            var req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/tags"))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            var res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 200) {
                var root = json.readTree(res.body());
                if (root.has("models")) {
                    var names = new java.util.ArrayList<String>();
                    for (var m : root.get("models")) {
                        if (m.has("name")) names.add(m.get("name").asText());
                    }
                    System.out.println("已安装模型: " + String.join(", ", names));
                }
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }
}
