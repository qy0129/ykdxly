package com.example.ilink.capabilities.workspace;

import com.example.ilink.application.tooling.Tool;
import com.example.ilink.application.tooling.ToolArguments;
import com.example.ilink.application.tooling.ToolContext;
import com.example.ilink.application.tooling.ToolDefinition;
import com.example.ilink.application.tooling.ToolResult;
import com.example.ilink.platform.workspace.WorkspaceApprovalStore;
import com.example.ilink.platform.workspace.WorkspaceFileService;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** Root-confined workspace search/read tool. Mutations only prepare an explicit confirmation. */
public final class WorkspaceFileTool implements Tool {
    public static final String NAME = "workspace_file";
    private static final int MAX_RESULTS = 30;

    private final WorkspaceFileService files;
    private final WorkspaceApprovalStore approvals;
    private final ToolDefinition definition;

    public WorkspaceFileTool(WorkspaceFileService files, WorkspaceApprovalStore approvals) {
        this.files = files;
        this.approvals = approvals;
        JsonObject properties = new JsonObject();
        properties.add("action", ToolDefinition.enumStringProperty(
                "操作：列出根目录、搜索、读取、准备修改或准备发送", "list", "search", "read", "prepare_write", "prepare_send"));
        properties.add("query", ToolDefinition.stringProperty("搜索关键词；其他操作传空字符串"));
        properties.add("root_id", ToolDefinition.stringProperty("工具返回的根目录 ID；跨根目录搜索时可传空字符串"));
        properties.add("path", ToolDefinition.stringProperty("根目录内的相对路径；list/search 可传空字符串"));
        properties.add("content", ToolDefinition.stringProperty("准备写入的完整文本；其他操作传空字符串"));
        definition = new ToolDefinition(NAME, "工作空间文件",
                "只访问配置允许的工作空间根目录。可列出根目录、搜索和读取文件。修改和发送只能准备待确认操作，"
                        + "绝不能自行确认、删除、移动或访问根目录外路径。准备后必须让用户明确回复“确认修改”或“确认发送”。",
                ToolDefinition.objectParameters(properties, "action", "query", "root_id", "path", "content"), true);
    }

    @Override public ToolDefinition definition() { return definition; }

    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) throws Exception {
        String action = ToolArguments.requireString(arguments, "action");
        String query = ToolArguments.string(arguments, "query", "").trim();
        String rootId = ToolArguments.string(arguments, "root_id", "").trim();
        String path = ToolArguments.string(arguments, "path", "").trim();
        String content = ToolArguments.string(arguments, "content", "");
        return switch (action) {
            case "list" -> listRoots();
            case "search" -> search(rootId, query);
            case "read" -> read(rootId, path);
            case "prepare_write" -> prepareWrite(context, rootId, path, content);
            case "prepare_send" -> prepareSend(context, rootId, path);
            default -> ToolResult.failure("不支持的工作空间操作");
        };
    }

    private ToolResult listRoots() {
        if (files.roots().isEmpty()) return ToolResult.failure("尚未配置 workspace.roots");
        StringBuilder output = new StringBuilder("可用工作空间：\n");
        files.roots().forEach(root -> output.append("- rootId=").append(root.id())
                .append(" name=").append(root.name()).append('\n'));
        return ToolResult.success(output.toString().trim());
    }

    private ToolResult search(String rootId, String query) throws Exception {
        if (query.isBlank()) return ToolResult.failure("请提供文件名或内容关键词");
        List<SearchHit> hits = new ArrayList<>();
        if (rootId.isBlank()) {
            for (WorkspaceFileService.Root root : files.roots()) addHits(hits, root.id(), files.search(root.id(), query));
        } else {
            addHits(hits, rootId, files.search(rootId, query));
        }
        if (hits.isEmpty()) return ToolResult.success("允许的工作空间内没有找到“" + query + "”");
        StringBuilder output = new StringBuilder("找到以下结果：\n");
        hits.stream().limit(MAX_RESULTS).forEach(hit -> output.append("- rootId=").append(hit.rootId())
                .append(" path=").append(hit.entry().path())
                .append(hit.entry().directory() ? " [目录]" : " [文件]").append('\n'));
        return ToolResult.success(output.toString().trim());
    }

    private ToolResult read(String rootId, String path) throws Exception {
        requireLocation(rootId, path);
        WorkspaceFileService.Preview preview = files.preview(rootId, path);
        String header = "rootId=" + rootId + " path=" + preview.path() + " type=" + preview.contentType()
                + " size=" + preview.size();
        return ToolResult.success(preview.text() ? header + "\n\n" + preview.content()
                : header + "\n该文件不是可直接读取的小型文本文件，可在用户确认后发送。" );
    }

    private ToolResult prepareWrite(ToolContext context, String rootId, String path, String content) throws Exception {
        requireLocation(rootId, path);
        String owner = WorkspaceApprovalStore.scope(context);
        WorkspaceFileService.PreparedWrite prepared = files.prepareWrite(owner, rootId, path, content);
        approvals.prepareWrite(context, rootId, path, prepared.token(), prepared.summary());
        return ToolResult.success("已准备修改 " + path + "：" + prepared.summary()
                + "。\n修改前预览：\n" + preview(prepared.before())
                + "\n修改后预览：\n" + preview(prepared.after())
                + "\n尚未写入文件，请向用户展示修改说明并要求明确回复“确认修改”；回复其他内容不能执行写入。" );
    }

    private ToolResult prepareSend(ToolContext context, String rootId, String path) throws Exception {
        requireLocation(rootId, path);
        WorkspaceFileService.Preview preview = files.preview(rootId, path);
        String summary = preview.name() + "（" + preview.size() + " 字节）";
        approvals.prepareSend(context, rootId, path, summary);
        return ToolResult.success("已准备发送 " + summary
                + "。尚未发送，请要求用户明确回复“确认发送”；回复其他内容不能外发。" );
    }

    private void addHits(List<SearchHit> hits, String rootId, List<WorkspaceFileService.Entry> entries) {
        for (WorkspaceFileService.Entry entry : entries) {
            if (hits.size() >= MAX_RESULTS) return;
            hits.add(new SearchHit(rootId, entry));
        }
    }

    private static void requireLocation(String rootId, String path) {
        if (rootId.isBlank() || path.isBlank()) throw new IllegalArgumentException("需要明确的 root_id 和相对路径 path");
    }

    private static String preview(String value) {
        if (value == null) return "";
        return value.length() <= 800 ? value : value.substring(0, 800) + "...";
    }

    private record SearchHit(String rootId, WorkspaceFileService.Entry entry) { }
}
