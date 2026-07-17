package com.example.ilink;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.Properties;

public class Config {

    public static final String API_KEY = loadApiKey();
    public static final String API_BASE_URL = "https://api.siliconflow.cn/v1/chat/completions";
    public static final String MODEL = "Qwen/Qwen3-8B";
    public static final String VISION_MODEL = "Qwen/Qwen3-VL-32B-Instruct";
    public static final String DRAW_API_URL = "https://api.siliconflow.cn/v1/images/generations";
    public static final String DRAW_MODEL = "Kwai-Kolors/Kolors";
    public static final Duration REQ_TIMEOUT = Duration.ofSeconds(60);

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
}
