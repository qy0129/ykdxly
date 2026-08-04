package com.changlu.planner.agent.subagents.document;

import com.changlu.planner.shared.config.EnvironmentConfig;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hslf.usermodel.HSLFShape;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextShape;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;

/** 将常见办公文件统一解析为可供模型和 RAG 使用的纯文本。 */
public final class DocumentParserTool {
  private static final Set<String> SUPPORTED = Set.of(
      "pdf", "doc", "docx", "txt", "md", "csv", "xls", "xlsx", "ppt", "pptx",
      "png", "jpg", "jpeg");

  private final VisionOcrTool ocr = new VisionOcrTool();
  private final int maxTextChars = Integer.parseInt(EnvironmentConfig.value(
      "PLANNER_DOCUMENT_MAX_TEXT_CHARS", "document.max.text.chars", "40000"));

  public ParsedDocument parse(byte[] bytes, String fileName, String mediaType) throws Exception {
    String extension = extension(fileName);
    if (!SUPPORTED.contains(extension)) {
      throw new IllegalArgumentException("暂不支持解析 ." + extension + " 文件");
    }
    String text = switch (extension) {
      case "txt", "md", "csv" -> new String(bytes, StandardCharsets.UTF_8).replace("\uFEFF", "");
      case "pdf" -> pdf(bytes);
      case "docx" -> docx(bytes);
      case "doc" -> doc(bytes);
      case "xls", "xlsx" -> workbook(bytes);
      case "pptx" -> pptx(bytes);
      case "ppt" -> ppt(bytes);
      case "png", "jpg", "jpeg" -> ocr.recognizeImage(bytes);
      default -> "";
    };
    text = normalize(text);
    if (text.isBlank()) throw new IllegalArgumentException("文件中没有识别到可用文字");
    if (text.length() > maxTextChars) text = text.substring(0, maxTextChars);
    return new ParsedDocument(fileName, mediaType == null ? "" : mediaType, extension, text);
  }

  public static String extension(String fileName) {
    if (fileName == null) return "";
    int dot = fileName.lastIndexOf('.');
    return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  public static String supportedLabel() {
    return "PDF、DOC、DOCX、TXT、MD、CSV、XLS、XLSX、PPT、PPTX、PNG、JPG";
  }

  private String pdf(byte[] bytes) throws Exception {
    String text;
    try (PDDocument document = Loader.loadPDF(bytes)) {
      text = new PDFTextStripper().getText(document);
    }
    return text == null || text.isBlank() ? ocr.recognizePdf(bytes) : text;
  }

  private String docx(byte[] bytes) throws Exception {
    StringBuilder text = new StringBuilder();
    try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
      for (XWPFParagraph paragraph : document.getParagraphs()) append(text, paragraph.getText());
      for (XWPFTable table : document.getTables()) {
        table.getRows().forEach(row -> row.getTableCells().forEach(cell -> append(text, cell.getText())));
      }
    }
    return text.toString();
  }

  private String doc(byte[] bytes) throws Exception {
    try (HWPFDocument document = new HWPFDocument(new ByteArrayInputStream(bytes));
         WordExtractor extractor = new WordExtractor(document)) {
      return extractor.getText();
    }
  }

  private String workbook(byte[] bytes) throws Exception {
    StringBuilder text = new StringBuilder();
    DataFormatter formatter = new DataFormatter(Locale.CHINA);
    try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
      for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
        Sheet sheet = workbook.getSheetAt(sheetIndex);
        text.append("----- ").append(sheet.getSheetName()).append(" -----\n");
        for (Row row : sheet) {
          StringBuilder line = new StringBuilder();
          row.forEach(cell -> {
            if (!line.isEmpty()) line.append('\t');
            line.append(formatter.formatCellValue(cell));
          });
          append(text, line.toString());
        }
      }
    }
    return text.toString();
  }

  private String pptx(byte[] bytes) throws Exception {
    StringBuilder text = new StringBuilder();
    try (XMLSlideShow slides = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
      for (int index = 0; index < slides.getSlides().size(); index++) {
        text.append("----- 第 ").append(index + 1).append(" 页 -----\n");
        for (XSLFShape shape : slides.getSlides().get(index).getShapes()) {
          if (shape instanceof XSLFTextShape value) append(text, value.getText());
        }
      }
    }
    return text.toString();
  }

  private String ppt(byte[] bytes) throws Exception {
    StringBuilder text = new StringBuilder();
    try (HSLFSlideShow slides = new HSLFSlideShow(new ByteArrayInputStream(bytes))) {
      for (int index = 0; index < slides.getSlides().size(); index++) {
        text.append("----- 第 ").append(index + 1).append(" 页 -----\n");
        for (HSLFShape shape : slides.getSlides().get(index).getShapes()) {
          if (shape instanceof HSLFTextShape value) append(text, value.getText());
        }
      }
    }
    return text.toString();
  }

  private void append(StringBuilder text, String line) {
    if (line != null && !line.isBlank()) text.append(line.strip()).append('\n');
  }

  private String normalize(String value) {
    return value == null ? "" : value.replace("\u0000", "").replaceAll("[\\t ]+", " ")
        .replaceAll("\\R{3,}", "\n\n").strip();
  }

  public record ParsedDocument(String fileName, String mediaType, String extension, String text) {}
}
