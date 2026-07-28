package com.example.ilink.capabilities.documents;

import com.example.ilink.capabilities.image.VisionService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 文档业务门面。
 *
 * <p>组合解析和生成能力，为应用层提供稳定的文档接口，
 * 避免应用层直接依赖 Apache POI 或 PDFBox。</p>
 */
public final class DocumentService {

    private final DocumentParser parser = new DocumentParser();
    private final DocumentGenerator generator = new DocumentGenerator();
    private final DocumentFormatConverter formatConverter = new DocumentFormatConverter();

    public DocumentService(VisionService visionService) {
        if (visionService != null) {
            parser.setOcrService(new OcrService(visionService));
        }
    }

    public DocumentService() {
    }

    public ParsedDocument parse(Path path, String originalFileName) throws Exception {
        return parser.parse(path, originalFileName);
    }

    public ParsedDocument parse(byte[] bytes, String originalFileName) throws Exception {
        String ext = extension(originalFileName);
        Path tempFile = Files.createTempFile("document_parse_", ext.isBlank() ? ".tmp" : "." + ext);
        try {
            Files.write(tempFile, bytes);
            return parser.parse(tempFile, originalFileName);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    public byte[] createDocx(String title, String content) throws IOException {
        return generator.createDocx(title, content);
    }

    public byte[] createPlainText(String content) {
        return generator.createPlainText(content);
    }

    public byte[] createXlsx(String content) throws IOException {
        return generator.createXlsx(content);
    }

    public byte[] createPptx(String title, String content) throws IOException {
        return generator.createPptx(title, content);
    }

    public byte[] createPdf(String title, String content) throws IOException {
        return generator.createPdf(title, content);
    }

    public byte[] renderMarkdownDocx(String content) throws IOException {
        return generator.renderMarkdownDocx(content);
    }

    public byte[] renderMarkdownPdf(String content) throws IOException {
        return generator.renderMarkdownPdf(content);
    }

    /** 纯 Java 格式转换（POI + PDFBox），保留文字和图片。 */
    public byte[] convertFormat(Path inputPath, String inputExt, String outputExt) throws IOException {
        inputExt = inputExt.toLowerCase(Locale.ROOT);
        outputExt = outputExt.toLowerCase(Locale.ROOT);
        if ("docx".equals(inputExt) && "pdf".equals(outputExt)) {
            return formatConverter.docxToPdf(inputPath);
        } else if ("pdf".equals(inputExt) && "docx".equals(outputExt)) {
            return formatConverter.pdfToDocx(inputPath);
        } else if ("doc".equals(inputExt) || "doc".equals(outputExt)) {
            throw new IOException("旧版 DOC 二进制格式暂不支持可靠转换，请先另存为 DOCX");
        }
        throw new IOException("不支持的格式转换: " + inputExt + " → " + outputExt);
    }

    /** 将内存中的编辑结果继续转换，避免重新读取未编辑的原文件。 */
    public byte[] convertFormat(byte[] inputBytes, String inputExt, String outputExt) throws IOException {
        Path tempFile = Files.createTempFile("document_convert_", "." + inputExt);
        try {
            Files.write(tempFile, inputBytes);
            return convertFormat(tempFile, inputExt, outputExt);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    /** 提取文档结构摘要（段落编号 + 内容预览），供 AI 定位插入位置。 */
    public String extractStructure(Path path, String ext) {
        return extractStructure(path, ext, false);
    }

    public String extractStructure(Path path, String ext, boolean describeImages) {
        try {
            return parser.extractStructure(path, ext, describeImages);
        } catch (Exception e) {
            return null;
        }
    }

    public static String extension(String fileName) {
        return DocumentParser.extension(fileName);
    }

    public record ParsedDocument(String fileName, String extension, String text) {
    }
}
