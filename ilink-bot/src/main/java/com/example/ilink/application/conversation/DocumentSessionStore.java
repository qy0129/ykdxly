package com.example.ilink.application.conversation;

import com.example.ilink.capabilities.documents.DocumentRecord;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 当前文档会话存储。
 *
 * <p>每个用户最多关联一份当前文档，文档问答、总结和编辑请求都会从这里
 * 取得文件路径、扩展名和解析后的文本。</p>
 */
public final class DocumentSessionStore {

    private static final Duration SESSION_TTL = Duration.ofHours(1);
    private static final int MAX_DOCUMENT_HISTORY = 20;
    private final Map<String, List<DocumentSession>> documents = new ConcurrentHashMap<>();

    /** 保存用户当前文档。 */
    public void set(String userId, DocumentRecord document) {
        documents.compute(userId, (ignored, current) -> {
            List<DocumentSession> history = new ArrayList<>();
            if (current != null) {
                history.addAll(current.stream()
                        .filter(session -> !sameDocument(session.document(), document))
                        .toList());
            }
            history.add(0, new DocumentSession(document, Instant.now()));
            return List.copyOf(history.subList(0, Math.min(MAX_DOCUMENT_HISTORY, history.size())));
        });
    }

    /** 获取用户当前文档，没有文档时返回 null。 */
    public DocumentRecord get(String userId) {
        List<DocumentSession> active = activeSessions(userId);
        return active.isEmpty() ? null : active.getFirst().document();
    }

    /** 按用户原话中出现的文件名选择文件；没有明确文件名时始终返回最新文件。 */
    public DocumentRecord resolve(String userId, String request) {
        List<DocumentSession> active = activeSessions(userId);
        if (active.isEmpty()) return null;
        String value = request == null ? "" : request.toLowerCase(Locale.ROOT);
        return active.stream()
                .filter(session -> mentionsFile(value, session.document().fileName()))
                .map(DocumentSession::document)
                .findFirst()
                .orElse(active.getFirst().document());
    }

    /** 用户进入其他媒体工作流时清除当前文档，避免旧状态污染意图判断。 */
    public void clear(String userId) {
        documents.remove(userId);
    }

    private List<DocumentSession> activeSessions(String userId) {
        List<DocumentSession> current = documents.getOrDefault(userId, List.of());
        Instant cutoff = Instant.now().minus(SESSION_TTL);
        List<DocumentSession> active = current.stream()
                .filter(session -> session.createdAt().isAfter(cutoff))
                .toList();
        if (active.size() != current.size()) {
            if (active.isEmpty()) documents.remove(userId);
            else documents.put(userId, active);
        }
        return active;
    }

    private static boolean mentionsFile(String request, String fileName) {
        if (request == null || request.isBlank() || fileName == null || fileName.isBlank()) return false;
        String fullName = fileName.toLowerCase(Locale.ROOT);
        if (request.contains(fullName)) return true;
        int extension = fullName.lastIndexOf('.');
        String baseName = extension > 0 ? fullName.substring(0, extension) : fullName;
        return baseName.length() >= 2 && request.contains(baseName);
    }

    private static boolean sameDocument(DocumentRecord left, DocumentRecord right) {
        return left.path().equals(right.path());
    }

    private record DocumentSession(DocumentRecord document, Instant createdAt) {
    }
}
