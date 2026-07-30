package com.example.ilink.adapter.outbound.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Stores Web-channel output files behind opaque download identifiers. */
public final class WebArtifactStore {

    private final Path root;
    private final ConcurrentHashMap<String, Artifact> artifacts = new ConcurrentHashMap<>();

    public WebArtifactStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Artifact save(String ownerId, byte[] content, String fileName, String contentType) throws IOException {
        String id = UUID.randomUUID().toString().replace("-", "");
        String safeOwner = safeSegment(ownerId, "web-user");
        String safeName = safeFileName(fileName);
        Path ownerDirectory = root.resolve(safeOwner).normalize();
        if (!ownerDirectory.startsWith(root)) throw new IOException("Invalid artifact owner");
        Files.createDirectories(ownerDirectory);
        Path path = ownerDirectory.resolve(id + "-" + safeName).normalize();
        if (!path.startsWith(ownerDirectory)) throw new IOException("Invalid artifact path");
        byte[] bytes = content == null ? new byte[0] : content;
        Files.write(path, bytes, StandardOpenOption.CREATE_NEW);
        Artifact artifact = new Artifact(id, ownerId, path, safeName,
                contentType == null || contentType.isBlank() ? detectContentType(safeName) : contentType,
                bytes.length, Instant.now());
        artifacts.put(id, artifact);
        return artifact;
    }

    public Optional<Artifact> find(String ownerId, String artifactId) {
        Artifact artifact = artifacts.get(artifactId);
        if (artifact == null || !artifact.ownerId().equals(ownerId) || !Files.isRegularFile(artifact.path())) {
            return Optional.empty();
        }
        return Optional.of(artifact);
    }

    /** Re-registers an artifact referenced by persisted history after a service restart. */
    public Optional<Artifact> restore(String ownerId, String artifactId, String fileName,
                                      String contentType, long expectedSize) {
        if (ownerId == null || ownerId.isBlank() || artifactId == null
                || !artifactId.matches("[a-f0-9]{32}")) return Optional.empty();
        Artifact existing = artifacts.get(artifactId);
        if (existing != null) return find(ownerId, artifactId);
        String safeOwner = safeSegment(ownerId, "web-user");
        String safeName = safeFileName(fileName);
        Path ownerDirectory = root.resolve(safeOwner).normalize();
        Path path = ownerDirectory.resolve(artifactId + "-" + safeName).normalize();
        if (!ownerDirectory.startsWith(root) || !path.startsWith(ownerDirectory)
                || !Files.isRegularFile(path)) return Optional.empty();
        try {
            long actualSize = Files.size(path);
            if (expectedSize >= 0L && expectedSize != actualSize) return Optional.empty();
            Artifact restored = new Artifact(artifactId, ownerId, path, safeName,
                    safeContentType(contentType, safeName), actualSize,
                    Files.getLastModifiedTime(path).toInstant());
            Artifact raced = artifacts.putIfAbsent(artifactId, restored);
            Artifact result = raced == null ? restored : raced;
            return result.ownerId().equals(ownerId) ? Optional.of(result) : Optional.empty();
        } catch (IOException error) {
            return Optional.empty();
        }
    }

    private static String safeSegment(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String safe = value.replaceAll("[^a-zA-Z0-9._-]", "_");
        return safe.isBlank() ? fallback : safe;
    }

    private static String safeFileName(String value) {
        String name = value == null ? "artifact.bin" : Path.of(value).getFileName().toString();
        name = name.replaceAll("[\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        return name.isBlank() ? "artifact.bin" : name;
    }

    private static String detectContentType(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".txt") || lower.endsWith(".md")) return "text/plain; charset=utf-8";
        if (lower.endsWith(".docx")) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        if (lower.endsWith(".xlsx")) {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        }
        return "application/octet-stream";
    }

    private static String safeContentType(String value, String fileName) {
        if (value == null || !value.matches("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+(?:; charset=[a-zA-Z0-9_-]+)?")) {
            return detectContentType(fileName);
        }
        return value;
    }

    public record Artifact(String id, String ownerId, Path path, String fileName,
                           String contentType, long size, Instant createdAt) {
    }
}
