package com.example.ilink.capabilities.documents.rag;

import java.util.ArrayList;
import java.util.List;

public final class DocumentChunker {

    private static final int MAX_CHUNK_CHARS = 800;

    public List<TextChunk> chunk(String fileName, String text) {
        List<TextChunk> chunks = new ArrayList<>();
        String[] paragraphs = text.split("\\R\\R+");
        StringBuilder current = new StringBuilder();
        int chunkIndex = 0;

        for (String para : paragraphs) {
            String stripped = para.strip();
            if (stripped.isEmpty()) continue;

            if (current.length() + stripped.length() > MAX_CHUNK_CHARS && !current.isEmpty()) {
                chunks.add(new TextChunk(fileName, chunkIndex++, current.toString().strip()));
                current = new StringBuilder();
            }
            if (current.length() > 0) current.append('\n');
            current.append(stripped);
        }

        if (!current.isEmpty()) {
            chunks.add(new TextChunk(fileName, chunkIndex, current.toString().strip()));
        }

        return chunks;
    }
}
