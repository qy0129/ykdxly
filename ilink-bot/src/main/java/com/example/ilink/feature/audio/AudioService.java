package com.example.ilink.feature.audio;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Path;

public final class AudioService {

    private final AudioFileStore fileStore = new AudioFileStore();
    private final AudioConverter converter = new AudioConverter();
    private final AudioTranscriptionService transcriptionService;
    private final SpeechSynthesisService synthesisService;

    public AudioService(HttpClient httpClient) {
        this.transcriptionService = new AudioTranscriptionService(httpClient, converter);
        this.synthesisService = new SpeechSynthesisService(httpClient);
    }

    public Path saveOriginal(String userId, byte[] audioBytes) throws IOException {
        return fileStore.saveOriginal(userId, audioBytes);
    }

    public String transcribe(Path originalAudio) throws Exception {
        return transcriptionService.transcribe(originalAudio);
    }

    public byte[] synthesize(String text) throws Exception {
        return synthesisService.synthesize(text);
    }

    public byte[] synthesize(String text, String voiceStyle) throws Exception {
        return synthesisService.synthesize(text, voiceStyle);
    }
}