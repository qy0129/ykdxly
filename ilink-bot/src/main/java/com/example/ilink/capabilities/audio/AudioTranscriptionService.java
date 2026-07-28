package com.example.ilink.capabilities.audio;

import com.example.ilink.bootstrap.Config;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.UUID;

/**
 * 语音转文字服务。
 *
 * <p>先把输入音频统一转换为 WAV，再以 multipart/form-data 方式调用
 * SiliconFlow 的语音识别接口。</p>
 */
public final class AudioTranscriptionService {

    private final HttpClient httpClient;
    private final Gson gson = new Gson();
    private final AudioConverter converter;

    /** 注入 HTTP 客户端和音频转换器。 */
    public AudioTranscriptionService(HttpClient httpClient, AudioConverter converter) {
        this.httpClient = httpClient;
        this.converter = converter;
    }
    /** 转换原始音频并调用语音识别接口。 */
    public String transcribe(Path originalAudio) throws Exception {
        // 转换逻辑独立于 HTTP 请求，保证不同音频格式使用同一套识别流程。
        Path wavAudio = Files.createTempFile("ilink-voice-", ".wav");
        try {
            converter.convertToWav(originalAudio, wavAudio);
            return transcribeWav(Files.readAllBytes(wavAudio));
        } finally {
            Files.deleteIfExists(wavAudio);
        }
    }

    /** 将 WAV 字节封装为 multipart 请求并解析识别结果。 */
    private String transcribeWav(byte[] audioBytes) throws Exception {
        String boundary = "----ilink-audio-" + UUID.randomUUID();
        byte[] body = multipartBody(boundary, audioBytes);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(Config.AUDIO_TRANSCRIPTION_URL))
                .timeout(Config.REQ_TIMEOUT)
                .header("Authorization", "Bearer " + Config.API_KEY)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("语音识别失败: HTTP " + response.statusCode() + " - " + response.body());
        }

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        return json.has("text") ? json.get("text").getAsString() : response.body();
    }

    /** 构造语音识别接口需要的 multipart 请求体。 */
    private byte[] multipartBody(String boundary, byte[] audioBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writePart(out, boundary, "model", Config.AUDIO_TRANSCRIPTION_MODEL);
        writePart(out, boundary, "response_format", "json");
        out.write(("--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"voice.audio\"\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(audioBytes);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    /** 向 multipart 请求体写入一个文本字段。 */
    private void writePart(ByteArrayOutputStream out, String boundary, String name, String value) throws IOException {
        out.write(("--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" +
                value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }
}
