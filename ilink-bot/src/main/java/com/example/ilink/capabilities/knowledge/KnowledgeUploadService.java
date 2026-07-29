package com.example.ilink.capabilities.knowledge;

import com.example.ilink.capabilities.documents.rag.Retriever;

public final class KnowledgeUploadService {

    private final Retriever retriever;

    public KnowledgeUploadService(Retriever retriever) {
        this.retriever = retriever;
    }

    public UploadResult upload(String userId, String fileName, String text) {
        if (userId == null || userId.isBlank() || fileName == null || fileName.isBlank()) {
            return new UploadResult(false, "", 0, "参数无效");
        }
        if (text == null || text.isBlank()) {
            return new UploadResult(false, "", 0, "文件内容为空");
        }
        try {
            Retriever.IndexResult result = retriever.indexDocument(userId, fileName, text);
            if (result.indexed()) {
                return new UploadResult(true, fileName, result.chunkCount(), "索引成功");
            }
            return new UploadResult(true, fileName, 0, "文件已存在，跳过重复索引");
        } catch (Exception error) {
            System.err.println("[KnowledgeUpload] 索引失败: " + error.getMessage());
            return new UploadResult(false, fileName, 0, "索引失败: " + error.getMessage());
        }
    }

    public record UploadResult(boolean success, String fileName, int chunkCount, String message) {
    }
}
