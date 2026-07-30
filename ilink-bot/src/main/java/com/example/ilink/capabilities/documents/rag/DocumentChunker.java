package com.example.ilink.capabilities.documents.rag;

import java.util.ArrayList;
import java.util.List;

public final class DocumentChunker {

    private static final int MAX_CHUNK_CHARS = 320;
    private static final int OVERLAP_CHARS = 60;

    public List<TextChunk> chunk(String fileName, String text) {
        List<TextChunk> chunks = new ArrayList<>();
        String normalized = text == null ? "" : text.replaceAll("[\\t ]+", " ").strip();
        int chunkIndex = 0;
        int start = 0;
        while (start < normalized.length()) {
            int hardEnd = Math.min(normalized.length(), start + MAX_CHUNK_CHARS);
            int end = boundary(normalized, start, hardEnd);
            String value = normalized.substring(start, end).strip();
            if (!value.isEmpty()) chunks.add(new TextChunk(fileName, chunkIndex++, value));
            if (end >= normalized.length()) break;
            start = Math.max(start + 1, end - OVERLAP_CHARS);
            while (start < normalized.length() && Character.isWhitespace(normalized.charAt(start))) start++;
        }

        return chunks;
    }

    private static int boundary(String text, int start, int hardEnd) {
        if (hardEnd >= text.length()) return text.length();
        int minimum = start + MAX_CHUNK_CHARS / 2;
        for (int index = hardEnd; index >= minimum; index--) {
            char value = text.charAt(index - 1);
            if (value == '。' || value == '！' || value == '？' || value == '\n'
                    || value == '.' || value == '!' || value == '?') return index;
        }
        return hardEnd;
    }
}
