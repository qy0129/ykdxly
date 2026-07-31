package com.example.ilink.capabilities.documents;

import com.example.ilink.application.conversation.DocumentSessionStore;
import com.example.ilink.bootstrap.Config;
import com.example.ilink.capabilities.documents.DocumentAiService;
import com.example.ilink.capabilities.documents.DocumentService;
import com.example.ilink.capabilities.documents.DocumentRecord;
import com.example.ilink.application.tooling.Tool;
import com.example.ilink.application.tooling.ToolArguments;
import com.example.ilink.application.tooling.ToolContext;
import com.example.ilink.application.tooling.ToolDefinition;
import com.example.ilink.application.tooling.ToolResult;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public final class DocumentEditTool implements Tool {

    public static final String NAME = "edit_document";
    private static final Duration SYNTAX_CHECK_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_PROCESS_OUTPUT_BYTES = 64 * 1024;
    private static final Pattern FORMAT_CONVERSION_PATTERN = Pattern.compile(
            "(?i).*(?:转换(?:成|为)?|转成|转为|转|改成|导出(?:为|成)?|另存为).*(?:pdf|word|docx|excel|xlsx|ppt|pptx|txt|md|csv|格式).*"
    );
    private static final Pattern EDIT_ACTION_PATTERN = Pattern.compile(
            ".*(?:插入|添加|删除|移除|替换|改写|润色|翻译|排版|加粗|字体|字号|颜色|标题|水印|页眉|页脚|图片|表格|内容|文字).*"
    );
    private static final Pattern PAGE_REFERENCE = Pattern.compile("第\\s*([0-9一二两三四五六七八九十百]+)\\s*页");
    private static final Pattern TEXT_REFERENCE = Pattern.compile(
            "第\\s*([0-9一二两三四五六七八九十百]+)\\s*(?:段(?:文字)?|个?文字块)"
    );
    private static final Pattern IMAGE_REFERENCE = Pattern.compile(
            "第\\s*([0-9一二两三四五六七八九十百]+)\\s*(?:张|个)?\\s*(?:图片|图像|照片)"
    );

    private final DocumentAiService documentAiService;
    private final DocumentService documentService;
    private final DocumentSessionStore documentSessions;
    private final ToolDefinition definition;

    public DocumentEditTool(DocumentAiService documentAiService,
                            DocumentService documentService,
                            DocumentSessionStore documentSessions) {
        this.documentAiService = documentAiService;
        this.documentService = documentService;
        this.documentSessions = documentSessions;

        JsonObject properties = new JsonObject();
        properties.add("request", ToolDefinition.stringProperty("对当前文档的完整修改要求"));
        properties.add("output_type", ToolDefinition.enumStringProperty("修改后的输出格式", "docx", "pdf", "xlsx", "pptx", "txt", "md", "csv"));
        properties.add("image_path", ToolDefinition.stringProperty("要插入图片的本地路径（如不涉及插入图片则不填）"));
        this.definition = new ToolDefinition(
                NAME,
                "编辑文档",
                "修改或转换最近发送的文档（DOCX/PDF/PPTX/XLSX/TXT/MD/CSV），支持同格式编辑或跨格式转换（如 PDF 转 Word）。没有当前文档时不要调用。如果用户要求插入图片，需要传 image_path。",
                ToolDefinition.objectParameters(properties, "request", "output_type", "image_path"),
                true);
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) throws Exception {
        DocumentRecord document = documentSessions.get(context.userId());
        if (document == null) {
            return ToolResult.failure("请先发送需要修改的文档");
        }

        String request = ToolArguments.requireString(arguments, "request");
        String outputType = DocumentFileType.canonical(
                ToolArguments.string(arguments, "output_type", "docx"));
        if (!DocumentFileType.canEditOutput(outputType)) {
            return ToolResult.failure("暂不支持将文档编辑为 " + outputType + " 格式");
        }
        String imagePath = ToolArguments.string(arguments, "image_path", null);
        String inputExt = document.extension().toLowerCase();

        // 只有纯格式转换请求才直接转换；“修改并转格式”必须先完成编辑。
        boolean needsConversion = !inputExt.equals(outputType);
        if (needsConversion && isPureFormatConversion(request)) {
            try {
                byte[] converted = documentService.convertFormat(
                        Path.of(document.path()), inputExt, outputType);
                System.err.println("[DocumentEditTool] Java 格式转换成功");
                return buildSuccess(document, inputExt, outputType, converted);
            } catch (Exception e) {
                if (!java.util.Set.of("doc", "xls", "ppt").contains(inputExt)) {
                    return ToolResult.failure("格式转换失败: " + e.getMessage());
                }
                System.err.println("[DocumentEditTool] 旧格式无法直接转换，降级为内容重建: " + e.getMessage());
            }
        }

        // 提取文档结构信息供 AI 精确定位插入位置
        boolean hasInsertImage = imagePath != null && !imagePath.isBlank();
        String structure = documentService.extractStructure(
                Path.of(document.path()), inputExt, hasInsertImage);

        // ── 模板模式：AI 只返回操作类型+参数，用预置脚本执行 ──
        String opJson = documentAiService.parseEditOperation(request, structure, inputExt,
                hasInsertImage);
        opJson = refineEditOperation(request, inputExt, opJson);
        if (opJson != null && !opJson.isBlank()) {
            ScriptResult templateResult = tryTemplateExecution(
                    document, inputExt, inputExt, imagePath, opJson);
            if (templateResult != null && templateResult.success()) {
                System.err.println("[DocumentEditTool] 模板执行成功");
                return finishEditedResult(document, inputExt, outputType,
                        templateResult.bytes(), templateResult.errorLog());
            }
            if (templateResult != null) {
                System.err.println("[DocumentEditTool] 模板执行失败，降级为自由脚本: " + templateResult.errorLog());
                JsonObject operation = parseOperationJson(opJson);
                if (!Config.DOCUMENT_FREE_SCRIPT_ENABLED && operation != null
                        && operation.has("operation")) {
                    String operationName = operation.get("operation").getAsString();
                    if ("insert_image".equals(operationName)) {
                        return editViaMarkdown(document, outputType, request,
                                "图片插入模板执行失败，本次图片可能未实际插入");
                    }
                    if (isPreciseTemplateOperation(operationName)) {
                        return ToolResult.failure("没有找到可唯一确定的编辑位置，已停止修改："
                                + conciseTemplateError(templateResult.errorLog()));
                    }
                }
            }
        }

        // 自由脚本不是操作系统级沙箱，默认关闭；复杂请求直接走 Markdown IR。
        if (!Config.DOCUMENT_FREE_SCRIPT_ENABLED) {
            System.err.println("[DocumentEditTool] 自由脚本已关闭，降级为 Markdown IR");
            return editViaMarkdown(document, outputType, request);
        }

        // ── 自由脚本模式（显式启用后的兜底）──
        String script = documentAiService.generateEditScript(
                document.fileName(), inputExt, inputExt, document.text(), request, imagePath, structure);
        if (script == null || script.isBlank()) {
            return editViaMarkdown(document, outputType, request);
        }

        String safetyError = scriptSafetyError(script);
        if (safetyError != null) {
            System.err.println("[DocumentEditTool] 拒绝不安全的自由脚本: " + safetyError);
            return editViaMarkdown(document, outputType, request);
        }

        // AST 静态校验：先检查语法，连括号都没闭合就不必进沙箱运行
        String syntaxError = checkSyntax(script);
        if (syntaxError != null) {
            System.err.println("[DocumentEditTool] AST 语法检查失败，尝试修复...\n" + syntaxError);
            String fixed = documentAiService.repairEditScript(
                    script, syntaxError, inputExt, inputExt, document.fileName(), request);
            if (fixed == null || fixed.isBlank()) {
                return editViaMarkdown(document, outputType, request);
            }
            script = fixed;
            safetyError = scriptSafetyError(script);
            if (safetyError != null) {
                return editViaMarkdown(document, outputType, request);
            }
        }

        ScriptResult result = runScript(document, inputExt, inputExt, imagePath, script);
        if (result.success) {
            return finishEditedResult(document, inputExt, outputType, result.bytes, result.errorLog);
        }

        System.err.println("[DocumentEditTool] 运行失败，尝试修复...\n" + result.errorLog);

        String fixedScript = documentAiService.repairEditScript(
                script, result.errorLog, inputExt, inputExt, document.fileName(), request);
        if (fixedScript == null || fixedScript.isBlank()) {
            return editViaMarkdown(document, outputType, request);
        }

        safetyError = scriptSafetyError(fixedScript);
        if (safetyError != null) {
            return editViaMarkdown(document, outputType, request);
        }

        ScriptResult retryResult = runScript(document, inputExt, inputExt, imagePath, fixedScript);
        if (retryResult.success) {
            return finishEditedResult(document, inputExt, outputType,
                    retryResult.bytes, retryResult.errorLog);
        }

        System.err.println("[DocumentEditTool] 脚本重试失败，降级为 Markdown IR...\n" + retryResult.errorLog);

        return editViaMarkdown(document, outputType, request);
    }

    private ToolResult editViaMarkdown(DocumentRecord document, String outputType, String request) {
        return editViaMarkdown(document, outputType, request, "原始文档编辑路径未成功");
    }

    private ToolResult editViaMarkdown(DocumentRecord document, String outputType,
                                       String request, String reason) {
        try {
            String markdown = documentAiService.editViaMarkdown(
                    document.fileName(), outputType, document.text(), request);
            if (markdown == null || markdown.isBlank()) {
                return ToolResult.failure("AI 未能生成修改后的内容");
            }

            byte[] bytes = switch (outputType) {
                case "pdf" -> documentService.renderMarkdownPdf(markdown);
                case "xlsx" -> documentService.createXlsx(markdown);
                case "pptx" -> documentService.createPptx(document.fileName() + "修改版", markdown);
                case "txt", "md", "csv" -> documentService.createPlainText(markdown);
                case "docx" -> documentService.renderMarkdownDocx(markdown);
                default -> throw new IllegalArgumentException("不支持的输出格式: " + outputType);
            };

            String outputName = outputFileName(document.fileName(), document.extension(), outputType);
            String message = "已生成替代版本：" + reason
                    + "。当前文件由可提取的文字内容重新生成，可能不保留原版式、原有图片、表格和其他对象。";
            return ToolResult.success(message,
                    new DocumentToolOutput(bytes, outputType, outputName, markdown, message));
        } catch (Exception e) {
            return ToolResult.failure("Markdown 渲染失败: " + e.getMessage());
        }
    }

    private String checkSyntax(String script) {
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("astcheck_", ".py");
            Files.writeString(tempFile, sanitize(script), StandardCharsets.UTF_8);

            ProcessBuilder pb = new ProcessBuilder("python", "-I", "-X", "utf8",
                    "-c", "import ast, sys; ast.parse(open(sys.argv[1], encoding='utf-8').read())",
                    tempFile.toAbsolutePath().toString());
            ProcessResult result = runManagedProcess(pb, SYNTAX_CHECK_TIMEOUT);
            return result.exitCode() == 0 ? null : result.output();
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            if (tempFile != null) try { Files.deleteIfExists(tempFile); } catch (Exception ignored) {}
        }
    }

    private ScriptResult runScript(DocumentRecord document, String inputExt, String outputExt,
                                   String imagePath, String script) {
        try {
            Path tempDir = Files.createTempDirectory("edit_");
            try {
                Path inputCopy = tempDir.resolve("input." + inputExt);
                Path outputFile = tempDir.resolve("output." + outputExt);
                Path scriptFile = tempDir.resolve("edit.py");

                Files.copy(Path.of(document.path()), inputCopy);
                Files.writeString(scriptFile, sanitize(script), StandardCharsets.UTF_8);

                var cmd = new java.util.ArrayList<String>();
                cmd.add("python");
                cmd.add("-I");
                cmd.add("-X");
                cmd.add("utf8");
                cmd.add(scriptFile.toString());
                cmd.add(inputCopy.toAbsolutePath().toString());
                cmd.add(outputFile.toAbsolutePath().toString());
                if (imagePath != null && !imagePath.isBlank()) {
                    cmd.add(Path.of(imagePath).toAbsolutePath().toString());
                }
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.directory(tempDir.toFile());
                ProcessResult processResult = runManagedProcess(pb, Config.DOCUMENT_SCRIPT_TIMEOUT);
                if (processResult.exitCode() == 0 && Files.exists(outputFile) && Files.size(outputFile) > 0) {
                    byte[] bytes = Files.readAllBytes(outputFile);
                    return new ScriptResult(true, bytes, null);
                }
                return new ScriptResult(false, null,
                        "exit=" + processResult.exitCode() + "\n" + processResult.output());
            } finally {
                try (var files = Files.walk(tempDir)) {
                    files.sorted(Comparator.reverseOrder())
                            .forEach(p -> {
                                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                            });
                }
            }
        } catch (Exception e) {
            return new ScriptResult(false, null,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private ToolResult buildSuccess(DocumentRecord document, String inputExt, String outputExt, byte[] bytes) {
        return buildSuccess(document, inputExt, outputExt, bytes, null);
    }

    private ToolResult buildSuccess(DocumentRecord document, String inputExt, String outputExt,
                                    byte[] bytes, String warning) {
        String outputName = outputFileName(document.fileName(), inputExt, outputExt);
        String outputText = document.text();
        try {
            outputText = documentService.parse(bytes, outputName).text();
        } catch (Exception e) {
            System.err.println("[DocumentEditTool] 无法重新提取输出正文，保留原会话文本: " + e.getMessage());
        }
        String message = warning == null || warning.isBlank()
                ? "修改完成" : "修改完成。" + warning;
        return ToolResult.success(message,
                new DocumentToolOutput(bytes, outputExt, outputName, outputText, message));
    }

    private ToolResult finishEditedResult(DocumentRecord document, String inputExt,
                                           String outputExt, byte[] editedBytes, String warning) {
        if (inputExt.equals(outputExt)) {
            return buildSuccess(document, inputExt, outputExt, editedBytes, warning);
        }
        try {
            byte[] converted = documentService.convertFormat(editedBytes, inputExt, outputExt);
            return buildSuccess(document, inputExt, outputExt, converted, warning);
        } catch (Exception e) {
            return ToolResult.failure("文档编辑已完成，但无法转换为 " + outputExt + ": " + e.getMessage());
        }
    }

    static boolean isPureFormatConversion(String request) {
        if (request == null || request.isBlank()) return false;
        String normalized = request.replaceAll("\\s+", "");
        return FORMAT_CONVERSION_PATTERN.matcher(normalized).matches()
                && !EDIT_ACTION_PATTERN.matcher(normalized).matches();
    }

    static String scriptSafetyError(String script) {
        if (script == null || script.isBlank()) return "脚本为空";
        String lower = script.toLowerCase(Locale.ROOT);
        String[] forbidden = {
                "import os", "from os", "subprocess", "socket", "requests", "urllib",
                "http.client", "ftplib", "telnetlib", "ctypes", "winreg", "multiprocessing",
                "asyncio", "pickle", "marshal", "__import__", "eval(", "exec(", "compile(",
                "getattr(", "setattr(", "globals(", "locals(", "vars(", "shutil.rmtree",
                ".unlink(", ".rmdir(", "os.remove", "os.rename", "os.replace", "os.system", "os.popen"
        };
        for (String token : forbidden) {
            if (lower.contains(token)) return "包含禁止调用: " + token;
        }
        return null;
    }

    static String refineEditOperation(String request, String inputExt, String opJson) {
        JsonObject parsed = parseOperationJson(opJson);
        boolean looksLikeInsert = request != null
                && request.matches(".*(?:插入|添加|加到|放到|放入|放在).*(?:图片|图像|照片).*|.*(?:图片|图像|照片).*(?:插入|添加|放到|放入|放在).*" );
        if (parsed == null) {
            if (!looksLikeInsert) return opJson;
            parsed = new JsonObject();
            parsed.addProperty("operation", "insert_image");
        }
        if (!parsed.has("operation") || parsed.get("operation").isJsonNull()) return opJson;
        String operation = parsed.get("operation").getAsString();
        if (!"insert_image".equals(operation)) {
            if (!looksLikeInsert) return parsed.toString();
            parsed.addProperty("operation", "insert_image");
        }

        JsonObject params = parsed.has("params") && parsed.get("params").isJsonObject()
                ? parsed.getAsJsonObject("params") : new JsonObject();
        Integer page = extractOrdinal(PAGE_REFERENCE, request);
        Integer textIndex = extractOrdinal(TEXT_REFERENCE, request);
        Integer imageIndex = extractOrdinal(IMAGE_REFERENCE, request);
        boolean betweenTextAndImage = request != null
                && (request.contains("中间") || request.contains("之间"))
                && (request.contains("文字") || request.contains("段"))
                && (request.contains("图片") || request.contains("图像") || request.contains("照片"));

        if (page != null) params.addProperty("page", page);
        if ("pdf".equals(inputExt)) {
            if (betweenTextAndImage) {
                params.addProperty("position", "between_text_image");
                params.addProperty("text_index", textIndex == null ? 1 : textIndex);
                params.addProperty("image_index", imageIndex == null ? 1 : imageIndex);
            } else if (textIndex != null && containsAfter(request)) {
                params.addProperty("position", "after_text");
                params.addProperty("text_index", textIndex);
            } else if (imageIndex != null && request != null && request.contains("前")) {
                params.addProperty("position", "before_image");
                params.addProperty("image_index", imageIndex);
            } else if (page != null && request != null
                    && (request.contains("内容后") || request.contains("页末") || request.contains("底部"))) {
                params.addProperty("position", "after_content");
            } else if (page != null && !requestsNewPage(request)) {
                params.addProperty("position", "page_auto");
            }
            if (!params.has("position")) params.addProperty("position", "new_page");
        } else if ("docx".equals(inputExt)) {
            if (requestsNewPage(request)) {
                params.addProperty("position", "new_page");
            } else if (betweenTextAndImage) {
                params.addProperty("position", "between_paragraph_image");
                params.addProperty("paragraph_index", (textIndex == null ? 1 : textIndex) - 1);
                params.addProperty("image_index", imageIndex == null ? 1 : imageIndex);
            } else if (textIndex != null) {
                params.addProperty("position", request != null && request.contains("前")
                        ? "before_paragraph" : "after_paragraph");
                params.addProperty("paragraph_index", textIndex - 1);
            } else if (imageIndex != null) {
                params.addProperty("position", request != null && request.contains("后")
                        ? "after_image" : "before_image");
                params.addProperty("image_index", imageIndex);
            } else if (page != null) {
                params.addProperty("position", "page_auto");
            }
            if (!params.has("position")) params.addProperty("position", "end");
        }
        parsed.add("params", params);
        return parsed.toString();
    }

    private static JsonObject parseOperationJson(String opJson) {
        if (opJson == null || opJson.isBlank()) return null;
        try {
            String json = opJson.strip();
            if (json.startsWith("```")) {
                int first = json.indexOf('\n');
                int last = json.lastIndexOf("```");
                if (first > 0 && last > first) json = json.substring(first + 1, last).strip();
            }
            return com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer extractOrdinal(Pattern pattern, String request) {
        if (request == null) return null;
        var matcher = pattern.matcher(request);
        return matcher.find() ? parseOrdinal(matcher.group(1)) : null;
    }

    private static int parseOrdinal(String value) {
        if (value.matches("\\d+")) return Integer.parseInt(value);
        int total = 0;
        int current = 0;
        for (char c : value.toCharArray()) {
            int digit = "零一二三四五六七八九".indexOf(c);
            if (digit >= 0) {
                current = digit;
            } else if (c == '十') {
                total += (current == 0 ? 1 : current) * 10;
                current = 0;
            } else if (c == '百') {
                total += (current == 0 ? 1 : current) * 100;
                current = 0;
            }
        }
        return Math.max(1, total + current);
    }

    private static boolean containsAfter(String request) {
        return request != null && (request.contains("后") || request.contains("下面"));
    }

    private static boolean requestsNewPage(String request) {
        return request != null && (request.contains("新建一页") || request.contains("另起一页")
                || request.contains("单独一页") || request.contains("新增一页"));
    }

    private static String outputFileName(String fileName, String inputExt, String outputExt) {
        if (inputExt.equals(outputExt)) return fileName;
        String suffix = "." + inputExt;
        if (fileName.toLowerCase(Locale.ROOT).endsWith(suffix)) {
            return fileName.substring(0, fileName.length() - suffix.length()) + "." + outputExt;
        }
        return fileName + "." + outputExt;
    }

    private record ScriptResult(boolean success, byte[] bytes, String errorLog) {}

    private record ProcessResult(int exitCode, String output) {}

    private static ProcessResult runManagedProcess(ProcessBuilder pb, Duration timeout) throws Exception {
        pb.redirectErrorStream(true);
        pb.environment().put("PYTHONUTF8", "1");
        pb.environment().put("PYTHONDONTWRITEBYTECODE", "1");
        Process process = pb.start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = Thread.ofVirtual().start(() -> drainLimited(process.getInputStream(), output));

        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
        reader.join(2_000);
        String log = output.toString(StandardCharsets.UTF_8);
        if (!finished) {
            log = "执行超时（" + timeout.toSeconds() + " 秒）\n" + log;
            return new ProcessResult(-1, log);
        }
        return new ProcessResult(process.exitValue(), log);
    }

    private static void drainLimited(InputStream input, ByteArrayOutputStream output) {
        byte[] buffer = new byte[4096];
        int total = 0;
        try (input) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                int writable = Math.min(read, MAX_PROCESS_OUTPUT_BYTES - total);
                if (writable > 0) {
                    output.write(buffer, 0, writable);
                    total += writable;
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static String sanitize(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c) || Character.isLowSurrogate(c)) {
                if (i + 1 < s.length() && Character.isSurrogatePair(c, s.charAt(i + 1))) {
                    sb.append(c);
                    sb.append(s.charAt(i + 1));
                    i++;
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 尝试用预置模板执行操作。返回 null 表示不适用模板。 */
    private ScriptResult tryTemplateExecution(DocumentRecord document, String inputExt,
                                              String outputExt, String imagePath, String opJson) {
        try {
            // 解析 AI 返回的 JSON
            String json = opJson.strip();
            if (json.startsWith("```")) {
                int first = json.indexOf('\n');
                int last = json.lastIndexOf("```");
                if (first > 0 && last > first) json = json.substring(first + 1, last).strip();
            }
            var parsed = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            if (!parsed.has("operation") || parsed.get("operation").isJsonNull()) return null;
            String operation = parsed.get("operation").getAsString();
            var params = parsed.has("params") && !parsed.get("params").isJsonNull()
                    ? parsed.getAsJsonObject("params") : new JsonObject();

            if ("custom".equals(operation)) return null;

            // 选模板文件名
            String templateName = switch (operation) {
                case "insert_image" -> "insert_image_" + inputExt + ".py";
                case "delete_image" -> "delete_image_" + inputExt + ".py";
                case "insert_text", "delete_text", "replace_text" -> "edit_text_" + inputExt + ".py";
                default -> null;
            };
            if (templateName == null) return null;

            // 读取模板资源
            String templateScript;
            try (var is = getClass().getClassLoader()
                    .getResourceAsStream("document_templates/" + templateName)) {
                if (is == null) return null;
                templateScript = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }

            // 构造命令行参数
            Path tempDir = Files.createTempDirectory("tpl_");
            try {
                Path inputCopy = tempDir.resolve("input." + inputExt);
                Path outputFile = tempDir.resolve("output." + outputExt);
                Path scriptFile = tempDir.resolve("edit.py");

                Files.copy(Path.of(document.path()), inputCopy);
                Files.writeString(scriptFile, templateScript, StandardCharsets.UTF_8);

                var cmd = new java.util.ArrayList<String>();
                cmd.add("python");
                cmd.add("-I");
                cmd.add("-X");
                cmd.add("utf8");
                cmd.add(scriptFile.toString());
                cmd.add(inputCopy.toAbsolutePath().toString());
                cmd.add(outputFile.toAbsolutePath().toString());

                // 根据操作类型传递额外参数
                switch (operation) {
                    case "insert_image" -> {
                        if (imagePath == null || imagePath.isBlank()) return null;
                        cmd.add(Path.of(imagePath).toAbsolutePath().toString());
                        String defaultPosition = "pdf".equals(inputExt) ? "new_page" : "end";
                        if (!params.has("position") || params.get("position").isJsonNull()) {
                            params.addProperty("position", defaultPosition);
                        }
                        String encodedParams = Base64.getUrlEncoder().withoutPadding().encodeToString(
                                params.toString().getBytes(StandardCharsets.UTF_8));
                        cmd.add(encodedParams);
                    }
                    case "delete_image" -> {
                        String scope = params.has("scope") && !params.get("scope").isJsonNull()
                                ? params.get("scope").getAsString() : "all";
                        cmd.add(scope);
                    }
                    case "insert_text", "delete_text", "replace_text" -> {
                        params.addProperty("action", operation);
                        String encodedParams = Base64.getUrlEncoder().withoutPadding().encodeToString(
                                params.toString().getBytes(StandardCharsets.UTF_8));
                        cmd.add(encodedParams);
                    }
                }

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.directory(tempDir.toFile());
                ProcessResult processResult = runManagedProcess(pb, Config.DOCUMENT_SCRIPT_TIMEOUT);
                if (processResult.exitCode() == 0 && Files.exists(outputFile) && Files.size(outputFile) > 0) {
                    return new ScriptResult(true, Files.readAllBytes(outputFile),
                            extractWarning(processResult.output()));
                }
                return new ScriptResult(false, null,
                        "exit=" + processResult.exitCode() + "\n" + processResult.output());
            } finally {
                try (var files = Files.walk(tempDir)) {
                    files.sorted(Comparator.reverseOrder())
                            .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
                }
            }
        } catch (Exception e) {
            return new ScriptResult(false, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static String extractWarning(String output) {
        if (output == null || output.isBlank()) return null;
        return output.lines()
                .filter(line -> line.startsWith("[Warning]"))
                .map(line -> line.substring("[Warning]".length()).strip())
                .findFirst()
                .orElse(null);
    }

    private static boolean isPreciseTemplateOperation(String operation) {
        return "insert_text".equals(operation) || "delete_text".equals(operation)
                || "replace_text".equals(operation) || "delete_image".equals(operation);
    }

    private static String conciseTemplateError(String error) {
        if (error == null || error.isBlank()) return "模板未返回具体原因";
        return error.lines()
                .filter(line -> line.contains("RuntimeError") || line.startsWith("[Execution Error]"))
                .reduce((first, second) -> second)
                .orElse(error.lines().findFirst().orElse("模板执行失败"))
                .replace("RuntimeError:", "")
                .replace("[Execution Error]:", "")
                .strip();
    }
}
