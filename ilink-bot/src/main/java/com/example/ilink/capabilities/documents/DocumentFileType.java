package com.example.ilink.capabilities.documents;

import java.util.Locale;
import java.util.Set;

/** 文档输入、生成和编辑共同使用的格式契约。 */
public final class DocumentFileType {

    private static final Set<String> INPUT_TYPES = Set.of(
            "pdf", "doc", "docx", "txt", "md", "csv", "xls", "xlsx", "ppt", "pptx");
    private static final Set<String> GENERATABLE_TYPES = Set.of(
            "pdf", "docx", "txt", "md", "csv", "xlsx");
    private static final Set<String> EDITABLE_OUTPUT_TYPES = Set.of(
            "pdf", "docx", "txt", "md", "csv", "xlsx", "pptx");

    private DocumentFileType() {
    }

    public static boolean supportsInput(String type) {
        return INPUT_TYPES.contains(canonical(type));
    }

    public static boolean canGenerate(String type) {
        return GENERATABLE_TYPES.contains(canonical(type));
    }

    public static boolean canEditOutput(String type) {
        return EDITABLE_OUTPUT_TYPES.contains(canonical(type));
    }

    public static boolean isPresentation(String type) {
        String value = canonical(type);
        return "ppt".equals(value) || "pptx".equals(value);
    }

    public static String canonical(String type) {
        if (type == null) return "none";
        String value = type.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "word", "doc" -> "docx";
            case "excel", "xls" -> "xlsx";
            case "markdown" -> "md";
            case "ppt", "powerpoint" -> "pptx";
            default -> value.isBlank() ? "none" : value;
        };
    }

    /** 从自然语言中提取用户明确指定的输出格式。 */
    public static String fromUserText(String text) {
        if (text == null || text.isBlank()) return "none";
        String value = text.toLowerCase(Locale.ROOT);
        if (value.contains("pptx") || value.contains("powerpoint") || value.contains("ppt")) return "pptx";
        if (value.contains("markdown") || value.matches(".*(?:^|[^a-z])md(?:[^a-z]|$).*$")) return "md";
        if (value.contains("xlsx") || value.contains("excel") || value.matches(".*(?:^|[^a-z])xls(?:[^a-z]|$).*$")
                || value.contains("电子表格")) return "xlsx";
        if (value.contains("docx") || value.contains("word") || value.contains("doc 文件")) return "docx";
        if (value.contains("pdf")) return "pdf";
        if (value.contains("csv")) return "csv";
        if (value.contains("txt") || value.contains("纯文本文件")) return "txt";
        return "none";
    }

    /** 旧格式只作为输入；编辑后使用对应的现代格式。 */
    public static String defaultEditOutput(String inputType) {
        String value = inputType == null ? "" : inputType.toLowerCase(Locale.ROOT);
        return switch (value) {
            case "doc" -> "docx";
            case "xls" -> "xlsx";
            case "ppt" -> "pptx";
            default -> canEditOutput(value) ? value : "docx";
        };
    }

    public static String supportedInputLabel() {
        return "PDF、DOC、DOCX、TXT、MD、CSV、XLS、XLSX、PPT 和 PPTX";
    }

    public static String generatableLabel() {
        return "Word、PDF、Excel、TXT、Markdown 或 CSV";
    }
}
