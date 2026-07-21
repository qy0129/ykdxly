package com.example.ilink.config;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.Properties;

public class Config {

    public static final String API_KEY = loadApiKey();
    public static final String API_BASE_URL = "https://api.siliconflow.cn/v1/chat/completions";
    public static final String MODEL = "Qwen/Qwen3-8B";
    public static final String ROUTER_MODEL = loadProperty("router.model", "Qwen/Qwen3.5-9B");
    public static final String VISION_MODEL = "Qwen/Qwen3-VL-32B-Instruct";
    public static final String DRAW_API_URL = "https://api.siliconflow.cn/v1/images/generations";
    public static final String DRAW_MODEL = "Kwai-Kolors/Kolors";
    public static final String IMAGE_EDIT_MODEL = "Qwen/Qwen-Image-Edit-2509";
    public static final String AUDIO_TRANSCRIPTION_URL = "https://api.siliconflow.cn/v1/audio/transcriptions";
    public static final String AUDIO_TRANSCRIPTION_MODEL = "FunAudioLLM/SenseVoiceSmall";
    public static final String TTS_API_URL = "https://api.siliconflow.cn/v1/audio/speech";
    public static final String TTS_MODEL = "FunAudioLLM/CosyVoice2-0.5B";
    public static final String TTS_VOICE = loadProperty("tts.voice.default", "FunAudioLLM/CosyVoice2-0.5B:alex");
    public static final String TTS_VOICE_BOY = loadProperty("tts.voice.boy", TTS_VOICE);
    public static final String TTS_VOICE_GIRL = loadProperty("tts.voice.girl", TTS_VOICE);
    public static final String TTS_VOICE_MALE = loadProperty("tts.voice.male", TTS_VOICE);
    public static final String TTS_VOICE_FEMALE = loadProperty("tts.voice.female", TTS_VOICE);
    public static final String TTS_VOICE_WARM = loadProperty("tts.voice.warm", TTS_VOICE);
    public static final String TTS_VOICE_LIVELY = loadProperty("tts.voice.lively", TTS_VOICE);
    public static final String REPLY_MODE = loadProperty("reply.mode", "text");
    public static final boolean AUDIO_ANALYSIS_ONLY_WHEN_REQUESTED =
            Boolean.parseBoolean(loadProperty("audio.analysis.only_when_requested", "true"));
    public static final Path AUDIO_DIR = Path.of(loadProperty("audio.dir", "data/audio"));
    public static final Path MEDIA_DIR = Path.of(loadProperty("media.dir", "data/media"));
    public static final String FFMPEG_COMMAND = loadProperty("ffmpeg.command", "ffmpeg");
    public static final String SILK_DECODER_COMMAND = loadProperty("silk.decoder.command", "auto");
    public static final String DOCUMENT_MODEL = loadProperty("document.model", "Qwen/Qwen3.5-9B");
    public static final int DOCUMENT_MAX_TEXT_CHARS = Integer.parseInt(loadProperty("document.max_text_chars", "40000"));
    public static final Duration REQ_TIMEOUT = Duration.ofSeconds(
            Long.parseLong(loadProperty("request.timeout.seconds", "120")));
    public static final Duration DOCUMENT_REQ_TIMEOUT = Duration.ofSeconds(
            Long.parseLong(loadProperty("document.request.timeout.seconds", "240")));

    private static String loadApiKey() {
        try {
            Properties props = new Properties();
            Path path = Path.of("config.properties");
            if (Files.exists(path)) {
                try (InputStream in = Files.newInputStream(path)) {
                    props.load(in);
                    String key = props.getProperty("api.key");
                    if (key != null && !key.isBlank() && !key.contains("把你的key")) {
                        return key;
                    }
                }
            }
        } catch (Exception ignored) {}
        System.err.println("错误: 请创建 config.properties 文件，内容为: api.key=你的Key");
        System.err.println("参考 config.properties.example");
        System.exit(1);
        return null;
    }

    private static String loadProperty(String name, String defaultValue) {
        try {
            Properties props = new Properties();
            Path path = Path.of("config.properties");
            if (Files.exists(path)) {
                try (InputStream in = Files.newInputStream(path)) {
                    props.load(in);
                }
            }
            return props.getProperty(name, defaultValue);
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
