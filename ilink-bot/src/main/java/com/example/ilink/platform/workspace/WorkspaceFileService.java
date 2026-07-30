package com.example.ilink.platform.workspace;

import com.example.ilink.bootstrap.Config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.concurrent.ConcurrentHashMap;

/** Read-only-by-default workspace access with root confinement and explicit write confirmation. */
public final class WorkspaceFileService {
    private static final int MAX_PREVIEW_BYTES = 512 * 1024;
    private static final int MAX_LIST_ENTRIES = 300;
    private final List<Path> roots;
    private final Map<String, PendingWrite> pendingWrites = new ConcurrentHashMap<>();

    public WorkspaceFileService() {
        this(Config.WORKSPACE_ROOTS);
    }

    public WorkspaceFileService(List<Path> roots) {
        this.roots = roots.stream().map(path -> path.toAbsolutePath().normalize()).toList();
    }

    public List<Root> roots() {
        List<Root> values = new ArrayList<>();
        for (int index = 0; index < roots.size(); index++) values.add(new Root(String.valueOf(index), roots.get(index).getFileName().toString()));
        return values;
    }

    public List<Entry> list(String rootId, String relativePath) throws IOException {
        Path directory = resolve(rootId, relativePath, true);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("目标不是目录");
        try (var stream = Files.list(directory)) {
            return stream.filter(path -> !Files.isSymbolicLink(path)).sorted(Comparator.comparing(path -> !Files.isDirectory(path)))
                    .limit(MAX_LIST_ENTRIES).map(path -> entry(root(rootId), path)).toList();
        }
    }

    public Preview preview(String rootId, String relativePath) throws IOException {
        Path file = resolve(rootId, relativePath, true);
        if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("请选择文件");
        long size = Files.size(file);
        String type = Files.probeContentType(file);
        boolean text = isText(file, type) && size <= MAX_PREVIEW_BYTES;
        String content = text ? Files.readString(file, StandardCharsets.UTF_8) : "";
        return new Preview(rootId, relative(root(rootId), file), file.getFileName().toString(), type == null ? "application/octet-stream" : type,
                size, text, content, hash(file));
    }

    /** Bounded, name-first search. File contents are searched only for small text files. */
    public List<Entry> search(String rootId, String query) throws IOException {
        String needle = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        if (needle.isBlank()) return List.of();
        Path root = root(rootId);
        try (Stream<Path> paths = Files.walk(root, 6)) {
            return paths.filter(path -> !path.equals(root) && !Files.isSymbolicLink(path))
                    .filter(path -> matches(path, needle)).limit(MAX_LIST_ENTRIES)
                    .map(path -> entry(root, path)).toList();
        }
    }

    public PreparedWrite prepareWrite(String rootId, String relativePath, String content) throws IOException {
        return prepareWrite("", rootId, relativePath, content);
    }

    public PreparedWrite prepareWrite(String ownerId, String rootId, String relativePath, String content) throws IOException {
        Preview preview = preview(rootId, relativePath);
        if (!preview.text()) throw new IllegalArgumentException("只允许修改 512KB 以内的文本文件");
        String token = UUID.randomUUID().toString();
        String next = content == null ? "" : content;
        pendingWrites.put(token, new PendingWrite(ownerId, rootId, relativePath, preview.hash(), next));
        return new PreparedWrite(token, preview.content(), next, summary(preview.content(), next));
    }

    public Entry confirmWrite(String token) throws IOException {
        return confirmWrite("", token);
    }

    public Entry confirmWrite(String ownerId, String token) throws IOException {
        PendingWrite change = pendingWrites.get(token);
        if (change == null) throw new IllegalArgumentException("修改确认已过期，请重新预览");
        if (!change.ownerId().equals(ownerId) || !pendingWrites.remove(token, change)) {
            throw new IllegalArgumentException("修改确认不属于当前用户或已过期");
        }
        Path file = resolve(change.rootId(), change.relativePath(), true);
        if (!hash(file).equals(change.previousHash())) throw new IllegalArgumentException("文件已被其他程序修改，请重新预览");
        Path backup = Config.MEDIA_DIR.resolve("workspace-backups").resolve(UUID.randomUUID() + ".bak");
        Files.createDirectories(backup.getParent());
        Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(file, change.content(), StandardCharsets.UTF_8);
        return entry(root(change.rootId()), file);
    }

    public byte[] readForDelivery(String rootId, String relativePath) throws IOException {
        Path file = resolve(rootId, relativePath, true);
        if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("请选择文件");
        if (Files.size(file) > Config.WORKSPACE_MAX_SEND_BYTES) throw new IllegalArgumentException("文件超过发送大小限制");
        return Files.readAllBytes(file);
    }

    public byte[] readForPreview(String rootId, String relativePath) throws IOException {
        Path file = resolve(rootId, relativePath, true);
        if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("请选择文件");
        if (Files.size(file) > Config.WORKSPACE_MAX_SEND_BYTES) throw new IllegalArgumentException("文件超过预览大小限制");
        return Files.readAllBytes(file);
    }

    public String fileName(String rootId, String relativePath) {
        try { return resolve(rootId, relativePath, true).getFileName().toString(); }
        catch (IOException error) { throw new IllegalArgumentException("文件路径无效"); }
    }

    private Path resolve(String rootId, String relativePath, boolean requireExisting) throws IOException {
        Path root = root(rootId);
        Path relative = Path.of(relativePath == null || relativePath.isBlank() ? "." : relativePath).normalize();
        if (relative.isAbsolute() || relative.startsWith("..")) throw new IllegalArgumentException("路径必须位于允许的工作空间内");
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) throw new IllegalArgumentException("路径超出允许的工作空间");
        if (requireExisting && !Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("文件不存在");
        Path current = root;
        for (Path part : root.relativize(target)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IllegalArgumentException("不允许访问符号链接");
        }
        if (requireExisting) {
            Path realRoot = root.toRealPath();
            Path realTarget = target.toRealPath();
            if (!realTarget.startsWith(realRoot)) throw new IllegalArgumentException("路径超出允许的工作空间");
        }
        return target;
    }

    private Path root(String rootId) {
        try {
            int index = Integer.parseInt(rootId);
            if (index >= 0 && index < roots.size()) return roots.get(index);
        } catch (Exception ignored) { }
        throw new IllegalArgumentException("工作空间不存在");
    }

    private Entry entry(Path root, Path path) {
        try {
            return new Entry(relative(root, path), path.getFileName().toString(), Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS),
                    Files.size(path), Files.getLastModifiedTime(path).toMillis(), Files.probeContentType(path));
        } catch (IOException error) { throw new IllegalStateException("无法读取文件信息", error); }
    }

    private boolean matches(Path path, String needle) {
        try {
            if (path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).contains(needle)) return true;
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_PREVIEW_BYTES) return false;
            String type = Files.probeContentType(path);
            return isText(path, type) && Files.readString(path, StandardCharsets.UTF_8)
                    .toLowerCase(java.util.Locale.ROOT).contains(needle);
        } catch (IOException ignored) {
            return false;
        }
    }

    private String relative(Path root, Path file) { return root.relativize(file).toString().replace('\\', '/'); }
    private static boolean isText(Path path, String type) { return (type != null && type.startsWith("text/")) || path.toString().matches("(?i).*\\.(md|txt|csv|json|xml|yml|yaml|java|js|css|html|properties)$"); }
    private static String summary(String before, String after) { return "原 " + before.length() + " 字符，修改后 " + after.length() + " 字符"; }
    private static String hash(Path file) throws IOException { try { return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))); } catch (Exception error) { throw new IOException(error); } }

    public record Root(String id, String name) { }
    public record Entry(String path, String name, boolean directory, long size, long modifiedAt, String contentType) { }
    public record Preview(String rootId, String path, String name, String contentType, long size, boolean text, String content, String hash) { }
    public record PreparedWrite(String token, String before, String after, String summary) { }
    private record PendingWrite(String ownerId, String rootId, String relativePath, String previousHash, String content) { }
}
