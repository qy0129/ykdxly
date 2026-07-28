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
 * 音频格式转换器。
 *
 * <p>普通音频通过 FFmpeg 转换为 16kHz、单声道 WAV；微信 SILK 音频先调用
 * SILK 解码器转为 PCM，再交给 FFmpeg 封装为 WAV，供语音识别服务使用。</p>
 */
public final class AudioConverter {
    /** 将任意支持的音频转换为语音识别所需的 WAV 格式。 */
    public void convertToWav(Path source, Path target) throws Exception {
        // 先识别 SILK，避免把微信语音直接交给 FFmpeg 导致格式识别失败。
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

    /** 使用外部 SILK 解码器把微信语音转成 PCM，再转为 WAV。 */
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

    /** 使用 FFmpeg 把 16kHz 单声道 PCM 封装成 WAV。 */
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

    /** 根据配置或系统安装位置定位 SILK 解码器。 */
    private String resolveSilkDecoder() throws IOException {
        if (!"auto".equalsIgnoreCase(Config.SILK_DECODER_COMMAND)) {
            return Config.SILK_DECODER_COMMAND;
        }

        return resolveSilkBinary("decoder.exe");
    }

    /** 在 Windows npm 工具目录中查找指定的 SILK 可执行文件。 */
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

    /** 检查文件头，判断输入是否为微信 SILK_V3 音频。 */
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
