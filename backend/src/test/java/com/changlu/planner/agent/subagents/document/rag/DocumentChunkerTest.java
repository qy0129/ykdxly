package com.changlu.planner.agent.subagents.document.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DocumentChunkerTest {
  @Test
  void keepsShortDocumentAsOneChunk() {
    assertEquals(1, new DocumentChunker().chunk("一段简短内容。第二句话。").size());
  }

  @Test
  void splitsLongDocumentWithoutDroppingTail() {
    String text = "内容。".repeat(600);
    var chunks = new DocumentChunker().chunk(text);

    assertTrue(chunks.size() > 1);
    assertTrue(chunks.getLast().endsWith("内容。"));
  }
}
