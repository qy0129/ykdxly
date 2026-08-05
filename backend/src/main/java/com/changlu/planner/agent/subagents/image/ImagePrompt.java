package com.changlu.planner.agent.subagents.image;

import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.Set;

/** ImagePrompt：文生图参数校验、自然语言意图解析、prompt 富化与信息不足检测。 */
public final class ImagePrompt {
  public static final Set<String> ALLOWED_SIZES = Set.of("1024x1024", "1792x1024", "1024x1792");
  public static final Set<String> ALLOWED_STYLES =
      Set.of("default", "realistic", "anime", "watercolor", "3d", "line-art", "minimalist");
  private static final int MAX_PROMPT_LENGTH = 1000;
  private static final int MIN_SUBJECT_LENGTH = 2;
  private static final String[] INTENT_STOPWORDS = {
      "生成", "帮我", "给我", "请帮我", "请", "我想要", "我想", "想要", "画一张", "画一个", "画张", "画",
      "绘制", "创作", "设计", "做一张", "做", "弄", "张", "个", "幅", "图片", "图像", "图画", "图",
      "吧", "了", "的", "啊", "呀", "呢", "吗", "么"
  };

  public String requirePrompt(String raw) {
    if (raw == null || raw.isBlank()) throw new IllegalArgumentException("IMAGE_PROMPT_REQUIRED");
    if (raw.trim().length() > MAX_PROMPT_LENGTH) throw new IllegalArgumentException("IMAGE_PROMPT_TOO_LONG");
    return raw.trim();
  }

  public String requireSize(String raw) {
    if (raw == null || raw.isBlank()) return "1024x1024";
    String value = raw.trim().toLowerCase(Locale.ROOT);
    if (!ALLOWED_SIZES.contains(value)) throw new IllegalArgumentException("IMAGE_SIZE_INVALID");
    return value;
  }

  public String requireStyle(String raw) {
    if (raw == null || raw.isBlank()) return "default";
    String value = raw.trim().toLowerCase(Locale.ROOT);
    if (!ALLOWED_STYLES.contains(value)) throw new IllegalArgumentException("IMAGE_STYLE_INVALID");
    return value;
  }

  public int requireQuality(Integer raw) {
    if (raw == null) return 2;
    if (raw < 1 || raw > 3) throw new IllegalArgumentException("IMAGE_QUALITY_INVALID");
    return raw;
  }

  /** 从用户自然语言提取 size/style 意图，未识别时按默认值处理。 */
  public JsonObject parse(String text) {
    JsonObject params = new JsonObject();
    String normalized = text == null ? "" : text.trim();
    String lower = normalized.toLowerCase(Locale.ROOT);
    if (lower.contains("16:9") || lower.contains("横屏") || lower.contains("横版") || lower.contains("宽幅")
        || lower.contains("横图")) {
      params.addProperty("size", "1792x1024");
    } else if (lower.contains("9:16") || lower.contains("竖屏") || lower.contains("竖版") || lower.contains("海报")) {
      params.addProperty("size", "1024x1792");
    }
    for (String style : ALLOWED_STYLES) {
      if (!"default".equals(style) && lower.contains(style)) {
        params.addProperty("style", style);
        break;
      }
    }
    if (lower.contains("批量") || lower.contains("多张") || lower.contains("几张")) {
      params.addProperty("mode", "batch");
    }
    params.addProperty("prompt", normalized);
    return params;
  }

  /** 按风格在原始描述后附加引导词，提升生成效果。 */
  public String enrich(String prompt, String style) {
    return switch (style) {
      case "realistic" -> prompt + ", photorealistic, 8k, detailed lighting";
      case "anime" -> prompt + ", anime style, vibrant colors, cel shading";
      case "watercolor" -> prompt + ", watercolor painting, soft edges, artistic";
      case "3d" -> prompt + ", 3d render, cg art, depth of field";
      case "line-art" -> prompt + ", line art, clean outlines, monochrome ink";
      case "minimalist" -> prompt + ", minimalist, clean composition, simple background";
      default -> prompt;
    };
  }

  /** 信息不足检测：去掉意图/语气词后若没剩下可作画面主体的内容，则判定模糊。 */
  public boolean insufficient(String raw) {
    if (raw == null) return true;
    String value = raw.replaceAll("[\\s，。！？、,.;:：；…·~-]", "");
    for (String word : INTENT_STOPWORDS) value = value.replace(word, "");
    return value.length() < MIN_SUBJECT_LENGTH;
  }
}