package com.example.ilink.feature.audio;

import com.example.ilink.config.Config;
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

public final class AudioFileStore {
    public Path saveOriginal(String userId, byte[] audioBytes) throws IOException {
        String safeUserId = userId.replaceAll("[^a-zA-Z0-9._-]", "_");
        Path userDir = Config.AUDIO_DIR.resolve(safeUserId);
        Files.createDirectories(userDir);
        Path path = userDir.resolve(System.currentTimeMillis() + "-" + UUID.randomUUID() + ".audio");
        return Files.write(path, audioBytes, StandardOpenOption.CREATE_NEW);
    }


}
