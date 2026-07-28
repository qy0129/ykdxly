package com.example.ilink.platform.network;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 管理 cloudflared 临时隧道，并读取本次运行的公网 HTTPS 地址。 */
public final class CloudflareTunnel implements AutoCloseable {

    private static final Pattern PUBLIC_URL = Pattern.compile(
            "https://[a-z0-9-]+\\.trycloudflare\\.com", Pattern.CASE_INSENSITIVE);

    private final String command;
    private final int localPort;
    private final Duration startupTimeout;
    private volatile Process process;

    public CloudflareTunnel(String command, int localPort, Duration startupTimeout) {
        this.command = command == null ? "" : command.trim();
        this.localPort = localPort;
        this.startupTimeout = startupTimeout;
    }

    public String start() {
        if (!commandAvailable()) return "";
        CompletableFuture<String> publicUrl = new CompletableFuture<>();
        try {
            process = new ProcessBuilder(command, "tunnel", "--url",
                    "http://127.0.0.1:" + localPort, "--no-autoupdate", "--loglevel", "info")
                    .redirectErrorStream(true)
                    .start();
            Thread.ofVirtual().name("dashboard-cloudflared-output").start(() -> readOutput(publicUrl));
            String url = publicUrl.get(startupTimeout.toMillis(), TimeUnit.MILLISECONDS);
            return process.isAlive() ? url : "";
        } catch (Exception error) {
            close();
            return "";
        }
    }

    private void readOutput(CompletableFuture<String> publicUrl) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String url = extractPublicUrl(line);
                if (!url.isBlank()) publicUrl.complete(url);
            }
        } catch (Exception ignored) {
            // 隧道关闭时输出流会同步结束。
        } finally {
            publicUrl.complete("");
        }
    }

    static String extractPublicUrl(String line) {
        if (line == null || line.isBlank()) return "";
        Matcher matcher = PUBLIC_URL.matcher(line);
        return matcher.find() ? matcher.group() : "";
    }

    private boolean commandAvailable() {
        if (command.isBlank()) return false;
        Path path = Path.of(command);
        return path.getNameCount() == 1 || Files.isRegularFile(path);
    }

    @Override
    public void close() {
        Process current = process;
        process = null;
        if (current == null || !current.isAlive()) return;
        current.destroy();
        try {
            if (!current.waitFor(2, TimeUnit.SECONDS)) current.destroyForcibly();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            current.destroyForcibly();
        }
    }
}
