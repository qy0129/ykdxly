package com.example.ilink.platform.media;

import com.example.ilink.bootstrap.Config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * 媒体文件存储器。
 *
 * <p>按用户和媒体类型创建目录，并使用随机文件名保存图片、音频和文档，
 * 返回实际生成的文件路径。</p>
 */
public class MediaStore {

    /** 按用户和媒体类型保存字节，并返回新文件路径。 */
    public Path save(String userId, String type, byte[] bytes, String extension) throws IOException {
        // 用户 ID 只用于目录归属，先清理特殊字符，避免生成非法路径。
        String safeUserId = userId.replaceAll("[^a-zA-Z0-9._-]", "_");
        Path directory = Config.MEDIA_DIR.resolve(safeUserId).resolve(type);
        Files.createDirectories(directory);
        Path path = directory.resolve(UUID.randomUUID() + "." + extension);
        return Files.write(path, bytes, StandardOpenOption.CREATE_NEW);
    }
}
