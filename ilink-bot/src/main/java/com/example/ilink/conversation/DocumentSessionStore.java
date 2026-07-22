package com.example.ilink.conversation;

import com.example.ilink.model.DocumentRecord;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 当前文档会话存储。
 *
 * <p>每个用户最多关联一份当前文档，文档问答、总结和编辑请求都会从这里
 * 取得文件路径、扩展名和解析后的文本。</p>
 */
public final class DocumentSessionStore {

    private final Map<String, DocumentRecord> documents = new ConcurrentHashMap<>();

    /** 保存用户当前文档。 */
    public void set(String userId, DocumentRecord document) {
        // 新文档会覆盖该用户之前的当前文档。
        documents.put(userId, document);
    }

    /** 获取用户当前文档，没有文档时返回 null。 */
    public DocumentRecord get(String userId) {
        return documents.get(userId);
    }
}
