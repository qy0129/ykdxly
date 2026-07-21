package com.example.ilink.storage;

import com.example.ilink.config.Config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

public class MediaStore {

    public Path save(String userId, String type, byte[] bytes, String extension) throws IOException {
        String safeUserId = userId.replaceAll("[^a-zA-Z0-9._-]", "_");
        Path directory = Config.MEDIA_DIR.resolve(safeUserId).resolve(type);
        Files.createDirectories(directory);
        Path path = directory.resolve(UUID.randomUUID() + "." + extension);
        return Files.write(path, bytes, StandardOpenOption.CREATE_NEW);
    }
}
