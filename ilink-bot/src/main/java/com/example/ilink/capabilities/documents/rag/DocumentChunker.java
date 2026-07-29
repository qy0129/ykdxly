package com.example.ilink.capabilities.documents.rag;

import java.util.ArrayList;
import java.util.List;

public final class DocumentChunker {

    private static final int MAX_CHUNK_CHARS = 320;

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

            if (stripped.length() > MAX_CHUNK_CHARS) {
                int start = 0;
                while (start < stripped.length()) {
                    int end = Math.min(start + MAX_CHUNK_CHARS, stripped.length());
                    if (current.length() > 0) current = new StringBuilder();
                    current.append(stripped, start, end);
                    chunks.add(new TextChunk(fileName, chunkIndex++, current.toString().strip()));
                    current = new StringBuilder();
                    start = end;
                }
            } else {
                current.append(stripped);
            }
        }

        if (!current.isEmpty()) {
            chunks.add(new TextChunk(fileName, chunkIndex, current.toString().strip()));
        }

        return chunks;
    }
}
