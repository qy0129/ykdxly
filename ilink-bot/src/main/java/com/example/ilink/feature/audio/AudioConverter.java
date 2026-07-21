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

public final class AudioConverter {
    public void convertToWav(Path source, Path target) throws Exception {
        if (isSilk(source)) {
            decodeSilk(source, target);
            return;
        }
        Process process = new ProcessBuilder(
                Config.FFMPEG_COMMAND,
                "-hide_banner",
                "-loglevel", "error",
                "-y",
                "-i", source.toAbsolutePath().toString(),
                "-ar", "16000",
                "-ac", "1",
                "-c:a", "pcm_s16le",
                target.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .start();

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("音频格式转换失败: " + output.trim());
        }
    }

    private void decodeSilk(Path source, Path target) throws Exception {
        Path pcmFile = Files.createTempFile("ilink-voice-", ".pcm");
        try {
            Process process = new ProcessBuilder(
                    resolveSilkDecoder(),
                    source.toAbsolutePath().toString(),
                    pcmFile.toAbsolutePath().toString(),
                    "-Fs_API", "16000")
                    .redirectErrorStream(true)
                    .start();

            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("SILK 解码失败: " + output.trim());
            }

            convertPcmToWav(pcmFile, target);
        } finally {
            Files.deleteIfExists(pcmFile);
        }
    }

    private void convertPcmToWav(Path source, Path target) throws Exception {
        Process process = new ProcessBuilder(
                Config.FFMPEG_COMMAND,
                "-hide_banner",
                "-loglevel", "error",
                "-y",
                "-f", "s16le",
                "-ar", "16000",
                "-ac", "1",
                "-i", source.toAbsolutePath().toString(),
                target.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .start();

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("PCM 转 WAV 失败: " + output.trim());
        }
    }

    private String resolveSilkDecoder() throws IOException {
        if (!"auto".equalsIgnoreCase(Config.SILK_DECODER_COMMAND)) {
            return Config.SILK_DECODER_COMMAND;
        }

        return resolveSilkBinary("decoder.exe");
    }

    private String resolveSilkBinary(String binaryName) throws IOException {
        if (!"auto".equalsIgnoreCase(Config.SILK_DECODER_COMMAND)) {
            Path configured = Path.of(Config.SILK_DECODER_COMMAND);
            if (configured.getFileName().toString().equalsIgnoreCase("decoder.exe")) {
                configured = configured.resolveSibling(binaryName);
            }
            return configured.toString();
        }

        String appData = System.getenv("APPDATA");
        if (appData != null) {
            Path binary = Path.of(appData, "npm", "node_modules", "@binsee", "wx-voice",
                    "node_modules", "@binsee", "wx-voice-silk-win32-x64", binaryName);
            if (Files.exists(binary)) {
                return binary.toString();
            }
        }
        throw new IOException("未找到 SILK 工具，请先安装 @binsee/wx-voice: " + binaryName);
    }

    private boolean isSilk(Path file) throws IOException {
        byte[] header = Files.readAllBytes(file);
        byte[] silkHeader = "#!SILK_V3".getBytes(StandardCharsets.US_ASCII);
        int searchLimit = Math.min(4, header.length - silkHeader.length);
        for (int offset = 0; offset <= searchLimit; offset++) {
            boolean matched = true;
            for (int i = 0; i < silkHeader.length; i++) {
                if (header[offset + i] != silkHeader[i]) {
                    matched = false;
                    break;
                }
            }
            if (matched) return true;
        }
        return false;
    }


}
