package com.changlu.planner.shared.config;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** 统一读取 .CC 配置文件，并允许环境变量覆盖。 */
public final class EnvironmentConfig {
  private static final Properties FILE = loadFile();

  private EnvironmentConfig() {}

  /** 环境变量优先于本地配置文件，便于部署时不把密钥写入仓库。 */
  public static String value(String envName, String propertyName, String fallback) {
    String env = System.getenv(envName);
    if (env != null && !env.isBlank()) return env.trim();
    String file = FILE.getProperty(propertyName);
    return file == null || file.isBlank() ? fallback : file.trim();
  }

  private static Properties loadFile() {
    Properties properties = new Properties();
    String configured = System.getenv().getOrDefault("PLANNER_CONFIG_FILE", "D:\\.CC\\backend\\config.properties");
    Path path = Path.of(configured);
    // 配置文件是可选的；没有文件时使用各调用方提供的默认值。
    try (InputStream input = Files.newInputStream(path);
         InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
      properties.load(reader);
    }
    catch (Exception ignored) { }
    return properties;
  }
}
