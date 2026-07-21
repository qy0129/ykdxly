package com.example.ilink.feature.audio;

import com.example.ilink.config.Config;
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

public final class SpeechSynthesisService {

    private final HttpClient httpClient;
    private final Gson gson = new Gson();

    public SpeechSynthesisService(HttpClient httpClient) {
        this.httpClient = httpClient;
    }
    public byte[] synthesize(String text) throws Exception {
        return synthesize(text, "default");
    }

    public byte[] synthesize(String text, String voiceStyle) throws Exception {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("语音合成文本不能为空");
        }

        String voice = resolveVoice(voiceStyle);
        System.out.println("[TTS] inputLength=" + text.codePointCount(0, text.length()) + ", voice=" + voice);
        JsonObject body = new JsonObject();
        body.addProperty("model", Config.TTS_MODEL);
        body.addProperty("input", text);
        body.addProperty("voice", voice);
        body.addProperty("response_format", "mp3");
        body.addProperty("sample_rate", 32000);
        body.addProperty("stream", false);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(Config.TTS_API_URL))
                .timeout(Config.REQ_TIMEOUT)
                .header("Authorization", "Bearer " + Config.API_KEY)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("语音合成失败: HTTP " + response.statusCode() + " - "
                    + new String(response.body(), StandardCharsets.UTF_8));
        }
        System.out.println("[TTS] MP3 生成成功，字节数=" + response.body().length);
        return response.body();
    }

    private String resolveVoice(String voiceStyle) {
        String style = voiceStyle == null ? "default" : voiceStyle.toLowerCase(Locale.ROOT);
        String voice = switch (style) {
            case "boy" -> Config.TTS_VOICE_BOY;
            case "girl" -> Config.TTS_VOICE_GIRL;
            case "male" -> Config.TTS_VOICE_MALE;
            case "female" -> Config.TTS_VOICE_FEMALE;
            case "warm" -> Config.TTS_VOICE_WARM;
            case "lively" -> Config.TTS_VOICE_LIVELY;
            default -> Config.TTS_VOICE;
        };
        return voice == null || voice.isBlank() ? Config.TTS_VOICE : voice;
    }


}
