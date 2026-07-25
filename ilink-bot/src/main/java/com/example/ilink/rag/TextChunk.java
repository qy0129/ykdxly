package com.example.ilink.rag;

import java.util.List;

public record TextChunk(
        String id,
        String fileName,
        int chunkIndex,
        String text,
        String preview) {

    public TextChunk(String fileName, int chunkIndex, String text) {
        this(fileName + "#" + chunkIndex, fileName, chunkIndex, text,
                text.length() > 20 ? text.substring(0, 20) + "..." : text);
    }
}
