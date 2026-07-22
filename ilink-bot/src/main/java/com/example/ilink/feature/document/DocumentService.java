package com.example.ilink.feature.document;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 文档业务门面。
 *
 * <p>组合解析、生成和 DOCX 编辑能力，为应用层提供稳定的文档接口，
 * 避免应用层直接依赖 Apache POI 或 PDFBox。</p>
 */
public final class DocumentService {

    private final DocumentParser parser = new DocumentParser();
    private final DocumentGenerator generator = new DocumentGenerator();
    private final DocxEditor editor = new DocxEditor();

    /** 解析 PDF、DOC、DOCX 或 TXT 文件。 */
    public ParsedDocument parse(Path path, String originalFileName) throws Exception {
        return parser.parse(path, originalFileName);
    }

    /** 调用生成器创建 DOCX 文件。 */
    public byte[] createDocx(String title, String content) throws IOException {
        return generator.createDocx(title, content);
    }

    /** 调用生成器创建 PDF 文件。 */
    public byte[] createPdf(String title, String content) throws IOException {
        return generator.createPdf(title, content);
    }

    /** 调用编辑器执行 DOCX 文本替换。 */
    public DocxEditResult editDocx(Path original, List<TextEdit> edits) throws IOException {
        return editor.editDocx(original, edits);
    }

    /** 对外提供统一的文件扩展名解析方法。 */
    public static String extension(String fileName) {
        return DocumentParser.extension(fileName);
    }

    /** 文档解析后的统一结果。 */
    public record ParsedDocument(String fileName, String extension, String text) {
    }

    /** 一条文本替换编辑指令。 */
    public record TextEdit(String type, String target, String replacement) {
    }

    /** DOCX 编辑结果及未匹配到的目标文本。 */
    public record DocxEditResult(byte[] document, int appliedEdits, List<String> unmatchedTargets) {
    }
}
