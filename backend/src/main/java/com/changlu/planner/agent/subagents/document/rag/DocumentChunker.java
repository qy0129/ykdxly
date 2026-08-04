package com.changlu.planner.agent.subagents.document.rag;

import java.util.ArrayList;
import java.util.List;

/** 按自然段边界切分文档，并保留少量重叠上下文。 */
final class DocumentChunker {
  private static final int MAX_CHUNK_CHARS = 1200;
  private static final int OVERLAP_CHARS = 150;

  List<String> chunk(String text) {
    String normalized = text == null ? "" : text.strip();
    List<String> chunks = new ArrayList<>();
    int start = 0;
    while (start < normalized.length()) {
      int hardEnd = Math.min(normalized.length(), start + MAX_CHUNK_CHARS);
      int end = boundary(normalized, start, hardEnd);
      String value = normalized.substring(start, end).strip();
      if (!value.isBlank()) chunks.add(value);
      if (end >= normalized.length()) break;
      start = Math.max(start + 1, end - OVERLAP_CHARS);
      while (start < normalized.length() && Character.isWhitespace(normalized.charAt(start))) start++;
    }
    return List.copyOf(chunks);
  }

  private int boundary(String text, int start, int hardEnd) {
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
