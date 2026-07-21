package com.example.ilink.feature.document;

import com.example.ilink.config.Config;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBody;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class DocumentParser {
    public DocumentService.ParsedDocument parse(Path path, String originalFileName) throws Exception {
        String extension = extension(originalFileName);
        String text = switch (extension) {
            case "txt", "md", "csv" -> Files.readString(path, StandardCharsets.UTF_8);
            case "docx" -> parseDocx(path);
            case "doc" -> parseDoc(path);
            case "pdf" -> parsePdf(path);
            default -> throw new IOException("暂不支持解析 " + extension + " 文件");
        };

        text = text == null ? "" : text.strip();
        if (text.isEmpty()) {
            throw new IOException("文件中没有提取到可用文字，扫描版 PDF 需要后续接入 OCR");
        }
        if (text.length() > Config.DOCUMENT_MAX_TEXT_CHARS) {
            text = text.substring(0, Config.DOCUMENT_MAX_TEXT_CHARS)
                    + "\n[文件内容过长，当前版本仅加载前 " + Config.DOCUMENT_MAX_TEXT_CHARS + " 个字符]";
        }
        return new DocumentService.ParsedDocument(originalFileName, extension, text);
    }

    private String parseDocx(Path path) throws IOException {
        StringBuilder text = new StringBuilder();
        try (InputStream input = Files.newInputStream(path);
             XWPFDocument document = new XWPFDocument(input)) {
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                appendLine(text, paragraph.getText());
            }
            for (XWPFTable table : document.getTables()) {
                for (var row : table.getRows()) {
                    for (var cell : row.getTableCells()) {
                        appendLine(text, cell.getText());
                    }
                }
            }
        }
        return text.toString();
    }

    private String parseDoc(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path);
             HWPFDocument document = new HWPFDocument(input);
             WordExtractor extractor = new WordExtractor(document)) {
            return extractor.getText();
        }
    }

    private String parsePdf(Path path) throws IOException {
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            return new PDFTextStripper().getText(document);
        }
    }

    private void appendLine(StringBuilder text, String value) {
        if (value != null && !value.isBlank()) {
            text.append(value.strip()).append('\n');
        }
    }

    public static String extension(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }


}