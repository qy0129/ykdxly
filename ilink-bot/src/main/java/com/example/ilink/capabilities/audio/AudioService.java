package com.example.ilink.capabilities.audio;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Path;

/**
 * 音频功能门面。
 *
 * <p>统一组合原始音频保存、格式转换、语音转写和文本转语音服务，
 * 上层只需要依赖本类即可完成常用音频操作。</p>
 */
public final class AudioService {

    private final AudioFileStore fileStore = new AudioFileStore();
    private final AudioConverter converter = new AudioConverter();
    private final AudioTranscriptionService transcriptionService;
    private final SpeechSynthesisService synthesisService;

    /** 创建音频门面并共享同一个 HTTP 客户端。 */
    public AudioService(HttpClient httpClient) {
        this.transcriptionService = new AudioTranscriptionService(httpClient, converter);
        this.synthesisService = new SpeechSynthesisService(httpClient);
    }

    /** 保存用户发送的原始语音文件。 */
    public Path saveOriginal(String userId, byte[] audioBytes) throws IOException {
        return fileStore.saveOriginal(userId, audioBytes);
    }

    /** 把原始语音转写成文字。 */
    public String transcribe(Path originalAudio) throws Exception {
        return transcriptionService.transcribe(originalAudio);
    }

    /** 使用默认音色把文本合成为音频，优先 MP3，失败时返回 WAV。 */
    public SynthesizedAudio synthesize(String text) throws Exception {
        return synthesisService.synthesize(text);
    }

    /** 使用指定音色风格把文本合成为音频，优先 MP3，失败时返回 WAV。 */
    public SynthesizedAudio synthesize(String text, String voiceStyle) throws Exception {
        return synthesisService.synthesize(text, voiceStyle);
    }

    /** 显式生成 WAV，用于 MP3 文件发送失败后的重试。 */
    public SynthesizedAudio synthesizeWav(String text, String voiceStyle) throws Exception {
        return synthesisService.synthesizeWav(text, voiceStyle);
    }
}
