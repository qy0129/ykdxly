package com.changlu.planner.agent.subagents.image.tools;

import com.changlu.planner.shared.config.EnvironmentConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 将供应商临时图片下载到本地，避免历史消息依赖一个会过期的签名 URL。 */
public final class ImageAssetStore {
  private static final Logger LOG = LoggerFactory.getLogger(ImageAssetStore.class);
  private static final long MAX_BYTES = 20L * 1024 * 1024;
  private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

  /** 返回本地文件绝对路径；下载失败时返回 null，不阻断图片生成主流程。 */
  public String save(String remoteUrl, String requestId) {
    if (remoteUrl == null || remoteUrl.isBlank() || requestId == null || requestId.isBlank()) return null;
    try {
      URI uri = URI.create(remoteUrl);
      if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) return null;
      HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(uri)
          .timeout(Duration.ofSeconds(90)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
      byte[] body = response.body();
      if (response.statusCode() / 100 != 2 || body == null || body.length == 0 || body.length > MAX_BYTES) return null;
      Path directory = assetRoot();
      Files.createDirectories(directory);
      String extension = extension(response.headers().firstValue("Content-Type").orElse(""), uri.getPath());
      Path file = directory.resolve(requestId + extension).normalize();
      if (!file.startsWith(directory)) return null;
      Files.write(file, body);
      return file.toAbsolutePath().toString();
    } catch (Exception error) {
      LOG.warn("[AI图片缓存] 下载失败: {}", error.getMessage());
      return null;
    }
  }

  /** 图片文件不放进 web/dist，避免下一次 Vite 构建清空历史图片。 */
  public static Path assetRoot() {
    String configured = EnvironmentConfig.value("PLANNER_IMAGE_DIR", "image.directory", "");
    if (!configured.isBlank()) return Path.of(configured).toAbsolutePath().normalize();
    return Path.of("data", "ai-images").toAbsolutePath().normalize();
  }

  private String extension(String contentType, String path) {
    String type = contentType.toLowerCase(Locale.ROOT);
    if (type.contains("jpeg") || type.contains("jpg")) return ".jpg";
    if (type.contains("webp")) return ".webp";
    if (type.contains("gif")) return ".gif";
    if (type.contains("svg")) return ".svg";
    if (type.contains("png")) return ".png";
    String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
    for (String suffix : new String[]{".jpg", ".jpeg", ".webp", ".gif", ".svg", ".png"}) {
      if (lower.endsWith(suffix)) return suffix;
    }
    return ".png";
  }
}
