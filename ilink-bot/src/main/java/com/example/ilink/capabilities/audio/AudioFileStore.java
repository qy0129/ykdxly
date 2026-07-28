package com.example.ilink.capabilities.audio;

import com.example.ilink.bootstrap.Config;
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
 * 原始语音文件存储器。
 *
 * <p>只负责保存微信收到的原始字节，不负责格式转换和语音识别。</p>
 */
public final class AudioFileStore {
    /** 按用户目录保存原始音频字节，并返回保存路径。 */
    public Path saveOriginal(String userId, byte[] audioBytes) throws IOException {
        // 每条语音使用时间戳加 UUID 命名，避免并发接收时发生覆盖。
        String safeUserId = userId.replaceAll("[^a-zA-Z0-9._-]", "_");
        Path userDir = Config.AUDIO_DIR.resolve(safeUserId);
        Files.createDirectories(userDir);
        Path path = userDir.resolve(System.currentTimeMillis() + "-" + UUID.randomUUID() + ".audio");
        return Files.write(path, audioBytes, StandardOpenOption.CREATE_NEW);
    }


}
