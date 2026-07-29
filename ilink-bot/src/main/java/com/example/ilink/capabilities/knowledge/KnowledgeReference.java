package com.example.ilink.capabilities.knowledge;

public record KnowledgeReference(
        String documentName,
        int chunkIndex,
        String content,
        double score) {

    public String toCitation() {
        return String.format("[%s 第%d段]", documentName, chunkIndex + 1);
    }

    public String toCitationWithScore() {
        return String.format("[%s 第%d段] (相似度:%.2f)", documentName, chunkIndex + 1, score);
    }
}
