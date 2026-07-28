package com.example.ilink.capabilities.audio;

import com.example.ilink.bootstrap.Config;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 文本转语音服务。
 *
 * <p>优先生成 MP3；接口不支持或生成失败时自动改为 WAV。</p>
 */
public final class SpeechSynthesisService {

    private final HttpClient httpClient;

    /** 创建文本转语音服务。 */
    public SpeechSynthesisService(HttpClient httpClient) {
        this.httpClient = httpClient;
    }
    /** 使用配置中的默认音色合成语音。 */
    public SynthesizedAudio synthesize(String text) throws Exception {
        return synthesize(text, "default");
    }

    /** 根据音色风格优先生成 MP3，失败时自动生成 WAV。 */
    public SynthesizedAudio synthesize(String text, String voiceStyle) throws Exception {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("语音合成文本不能为空");
        }

        String voice = resolveVoice(voiceStyle);
        System.out.println("[TTS] inputLength=" + text.codePointCount(0, text.length()) + ", voice=" + voice);
        try {
            return synthesize(text, voice, "mp3");
        } catch (Exception mp3Error) {
            System.err.println("[TTS] MP3 生成失败，改用 WAV: " + mp3Error.getMessage());
            try {
                return synthesize(text, voice, "wav");
            } catch (Exception wavError) {
                wavError.addSuppressed(mp3Error);
                throw wavError;
            }
        }
    }

    /** 在 MP3 文件无法发送时，显式生成 WAV 重试。 */
    public SynthesizedAudio synthesizeWav(String text, String voiceStyle) throws Exception {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("语音合成文本不能为空");
        }
        return synthesize(text, resolveVoice(voiceStyle), "wav");
    }

    private SynthesizedAudio synthesize(String text, String voice, String format)
            throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("model", Config.TTS_MODEL);
        body.addProperty("input", text);
        body.addProperty("voice", voice);
        body.addProperty("response_format", format);
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
        if (response.body().length == 0) {
            throw new IOException("语音合成失败: 返回空音频");
        }
        System.out.println("[TTS] " + format.toUpperCase(Locale.ROOT)
                + " 生成成功，字节数=" + response.body().length);
        return new SynthesizedAudio(response.body(), format);
    }

    /** 把业务层音色名称映射为配置中的具体模型音色。 */
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
