package com.example.ilink.capabilities.workspace;

import com.example.ilink.application.tooling.ToolContext;
import com.example.ilink.platform.workspace.WorkspaceApprovalStore;
import com.example.ilink.platform.workspace.WorkspaceFileService;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceFileToolTest {

    @TempDir Path root;

    @Test
    void searchesAndPreparesUserScopedWriteWithoutMutatingBeforeConfirmation() throws Exception {
        Path file = root.resolve("notes.txt");
        Files.writeString(file, "旧内容");
        WorkspaceFileService files = new WorkspaceFileService(List.of(root));
        WorkspaceApprovalStore approvals = new WorkspaceApprovalStore();
        WorkspaceFileTool tool = new WorkspaceFileTool(files, approvals);
        ToolContext context = new ToolContext("user-a", "session-a");

        var search = tool.execute(context, arguments("search", "notes", "", "", ""));
        assertTrue(search.success());
        assertTrue(search.output().contains("notes.txt"));

        var prepared = tool.execute(context, arguments("prepare_write", "", "0", "notes.txt", "新内容"));
        assertTrue(prepared.success());
        assertEquals("旧内容", Files.readString(file));

        WorkspaceApprovalStore.Pending pending = approvals.consume(
                "user-a", "session-a", WorkspaceApprovalStore.Action.WRITE);
        assertNotNull(pending);
        assertThrows(IllegalArgumentException.class,
                () -> files.confirmWrite("user-b|session-b", pending.token()));
        files.confirmWrite(WorkspaceApprovalStore.scope("user-a", "session-a"), pending.token());
        assertEquals("新内容", Files.readString(file));
    }

    @Test
    void prepareSendRequiresMatchingExplicitApproval() throws Exception {
        Files.writeString(root.resolve("report.txt"), "report");
        WorkspaceFileService files = new WorkspaceFileService(List.of(root));
        WorkspaceApprovalStore approvals = new WorkspaceApprovalStore();
        WorkspaceFileTool tool = new WorkspaceFileTool(files, approvals);
        ToolContext context = new ToolContext("user-a", "session-a");

        assertTrue(tool.execute(context,
                arguments("prepare_send", "", "0", "report.txt", "")).success());
        assertEquals(null, approvals.consume("user-a", "other-session", WorkspaceApprovalStore.Action.SEND));
        assertNotNull(approvals.consume("user-a", "session-a", WorkspaceApprovalStore.Action.SEND));
    }

    private JsonObject arguments(String action, String query, String rootId, String path, String content) {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("action", action);
        arguments.addProperty("query", query);
        arguments.addProperty("root_id", rootId);
        arguments.addProperty("path", path);
        arguments.addProperty("content", content);
        return arguments;
    }
}
