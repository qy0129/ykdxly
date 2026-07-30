package com.example.ilink.capabilities.knowledge;

import com.example.ilink.capabilities.documents.rag.Retriever;
import com.example.ilink.capabilities.documents.rag.VectorStore;

import java.util.ArrayList;
import java.util.List;

public final class KnowledgeQueryService {

    private static final int TOP_K = 4;

    private final Retriever retriever;

    public KnowledgeQueryService(Retriever retriever) {
        this.retriever = retriever;
    }

    public KnowledgeResult query(String userId, String question) {
        if (question == null || question.isBlank()
                || userId == null || userId.isBlank()) {
            return KnowledgeResult.empty();
        }
        try {
            List<VectorStore.ScoredChunk> matches = retriever.retrieve(userId, question, TOP_K);
            if (matches.isEmpty()) return KnowledgeResult.empty();

            List<KnowledgeReference> references = new ArrayList<>();
            StringBuilder context = new StringBuilder("以下内容来自用户的知识库，请基于这些资料回答问题：\n\n");

            for (int i = 0; i < matches.size(); i++) {
                VectorStore.ScoredChunk match = matches.get(i);
                KnowledgeReference ref = new KnowledgeReference(
                        match.chunk().fileName(), match.chunk().chunkIndex(),
                        match.chunk().text(), match.score());
                references.add(ref);

                context.append("【参考").append(i + 1).append("】")
                        .append(ref.toCitation()).append("\n")
                        .append(match.chunk().text()).append("\n\n");
            }

            context.append("请基于以上参考资料回答用户问题。在回答中标注引用来源，格式为").append("[文件名 第N段]");
            return new KnowledgeResult(true, context.toString().trim(), references);
        } catch (Exception error) {
            System.err.println("[KnowledgeQuery] 检索失败: " + error.getMessage());
            return KnowledgeResult.empty();
        }
    }

    public boolean hasKnowledge(String userId) {
        return retriever.hasKnowledge(userId);
    }

    public record KnowledgeResult(boolean found, String contextPrompt, List<KnowledgeReference> references) {
        static KnowledgeResult empty() {
            return new KnowledgeResult(false, "", List.of());
        }
    }
}
