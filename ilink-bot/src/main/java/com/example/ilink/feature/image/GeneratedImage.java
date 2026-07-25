package com.example.ilink.feature.image;

import java.util.Locale;

/** 图片服务返回的图片内容及真实媒体类型。 */
public record GeneratedImage(byte[] bytes, String extension, String contentType) {

    /** 根据响应头和文件签名确定图片格式。 */
    public static GeneratedImage from(byte[] bytes, String declaredContentType) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("图片内容为空");
        }
        String extension = detectExtension(bytes, declaredContentType);
        String contentType = switch (extension) {
            case "jpg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            default -> "image/png";
        };
        return new GeneratedImage(bytes, extension, contentType);
    }

    /** 使用真实扩展名生成发送文件名。 */
    public String fileName(String baseName) {
        return baseName + "." + extension;
    }

    private static String detectExtension(byte[] bytes, String declaredContentType) {
        if (isPng(bytes)) return "png";
        if (isJpeg(bytes)) return "jpg";
        if (isWebp(bytes)) return "webp";
        if (isGif(bytes)) return "gif";

        String contentType = declaredContentType == null
                ? "" : declaredContentType.toLowerCase(Locale.ROOT);
        if (contentType.contains("jpeg") || contentType.contains("jpg")) return "jpg";
        if (contentType.contains("webp")) return "webp";
        if (contentType.contains("gif")) return "gif";
        if (contentType.contains("png")) return "png";
        throw new IllegalArgumentException("无法识别图片格式: " + declaredContentType);
    }

    private static boolean isPng(byte[] bytes) {
        return bytes.length >= 8 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 0x50
                && bytes[2] == 0x4e && bytes[3] == 0x47;
    }

    private static boolean isJpeg(byte[] bytes) {
        return bytes.length >= 3 && (bytes[0] & 0xff) == 0xff
                && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff;
    }

    private static boolean isWebp(byte[] bytes) {
        return bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I'
                && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
    }

    private static boolean isGif(byte[] bytes) {
        return bytes.length >= 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F';
    }
}
