package com.changlu.planner.agent.subagents.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DocumentParserToolTest {
  private final DocumentParserTool parser = new DocumentParserTool();

  @Test
  void parsesUtf8Text() throws Exception {
    DocumentParserTool.ParsedDocument document = parser.parse(
        "第一行\n第二行".getBytes(StandardCharsets.UTF_8), "notes.txt", "text/plain");

    assertEquals("txt", document.extension());
    assertEquals("第一行\n第二行", document.text());
  }

  @Test
  void rejectsUnsupportedFiles() {
    assertThrows(IllegalArgumentException.class,
        () -> parser.parse(new byte[]{1}, "archive.zip", "application/zip"));
  }
}
