package com.example.ilink.application.conversation;

import com.example.ilink.capabilities.documents.DocumentRecord;

import java.time.Duration;
import java.time.Instant;
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
    private final Map<String, DocumentSession> documents = new ConcurrentHashMap<>();

    /** 保存用户当前文档。 */
    public void set(String userId, DocumentRecord document) {
        // 新文档会覆盖该用户之前的当前文档。
        documents.put(userId, new DocumentSession(document, Instant.now()));
    }

    /** 获取用户当前文档，没有文档时返回 null。 */
    public DocumentRecord get(String userId) {
        DocumentSession session = documents.get(userId);
        if (session == null) return null;
        if (session.createdAt().plus(SESSION_TTL).isBefore(Instant.now())) {
            documents.remove(userId, session);
            return null;
        }
        return session.document();
    }

    /** 用户进入其他媒体工作流时清除当前文档，避免旧状态污染意图判断。 */
    public void clear(String userId) {
        documents.remove(userId);
    }

    private record DocumentSession(DocumentRecord document, Instant createdAt) {
    }
}
