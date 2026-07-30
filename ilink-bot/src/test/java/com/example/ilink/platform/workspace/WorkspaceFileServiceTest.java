package com.example.ilink.platform.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceFileServiceTest {

    @TempDir Path root;

    @Test
    void confinesListingPreviewAndSearchToConfiguredRoot() throws Exception {
        Files.createDirectories(root.resolve("notes"));
        Files.writeString(root.resolve("notes/todo.txt"), "买菜和完成报告", StandardCharsets.UTF_8);
        WorkspaceFileService files = new WorkspaceFileService(List.of(root));

        assertEquals("todo.txt", files.list("0", "notes").getFirst().name());
        assertTrue(files.preview("0", "notes/todo.txt").content().contains("完成报告"));
        assertEquals("notes/todo.txt", files.search("0", "报告").getFirst().path());
        assertThrows(IllegalArgumentException.class, () -> files.preview("0", "../outside.txt"));
        assertThrows(IllegalArgumentException.class, () -> files.list("1", ""));
    }
}
