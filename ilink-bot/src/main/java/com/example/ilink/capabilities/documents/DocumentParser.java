package com.example.ilink.capabilities.documents;

import com.example.ilink.bootstrap.Config;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBody;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STBrType;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * 文档解析器。
 *
 * <p>根据扩展名解析 TXT、DOC、DOCX 和 PDF，统一返回纯文本，
 * 供文档问答、总结和编辑计划使用。</p>
 */
public final class DocumentParser {

    private static final int MAX_DESCRIBED_IMAGES = 6;
    private OcrService ocrService;

    /** 设置 OCR 服务，用于扫描版 PDF 降级识别。 */
    public void setOcrService(OcrService ocrService) {
        this.ocrService = ocrService;
    }

    /** 根据文件扩展名选择解析器，并限制返回文本长度。 */
    public DocumentService.ParsedDocument parse(Path path, String originalFileName) throws Exception {
        String extension = extension(originalFileName);
        String text = switch (extension) {
            case "txt", "md", "csv" -> Files.readString(path, StandardCharsets.UTF_8);
            case "docx" -> parseDocx(path);
            case "doc" -> parseDoc(path);
            case "pdf" -> parsePdf(path);
            case "xlsx" -> parseXlsx(path);
            case "xls" -> parseXls(path);
            case "pptx" -> parsePptx(path);
            default -> throw new IOException("暂不支持解析 " + extension + " 文件");
        };

        text = text == null ? "" : text.strip();
        if (text.isEmpty()) {
            if ("pdf".equals(extension) && ocrService != null) {
                text = ocrService.recognize(path);
            }
            if (text == null || text.isBlank()) {
                throw new IOException("文件中没有提取到可用文字，OCR 识别也未返回结果");
            }
        }
        String indexText = text;
        if (text.length() > Config.DOCUMENT_MAX_TEXT_CHARS) {
            text = text.substring(0, Config.DOCUMENT_MAX_TEXT_CHARS)
                    + "\n[文件内容过长，当前版本仅加载前 " + Config.DOCUMENT_MAX_TEXT_CHARS + " 个字符]";
        }
        return new DocumentService.ParsedDocument(originalFileName, extension, text, indexText);
    }

    /** 读取 DOCX 段落、表格文本和嵌入图片文字。 */
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
            if (ocrService != null) {
                recognizeDocxImages(document, text);
            }
        }
        return text.toString();
    }

    /** 遍历 DOCX 的 /word/media/ 图片，调用 OCR 提取文字。 */
    private void recognizeDocxImages(XWPFDocument document, StringBuilder text) {
        var parts = getPackageParts(document.getPackage());
        if (parts == null) return;
        for (var part : parts) {
            if (!part.getPartName().getName().startsWith("/word/media/")) continue;
            try (InputStream imgInput = part.getInputStream()) {
                byte[] imageBytes = imgInput.readAllBytes();
                String imageText = ocrService.recognizeImageBytes(imageBytes);
                if (imageText != null && !imageText.isBlank()) {
                    text.append("\n[图片文字] ").append(imageText);
                }
            } catch (Exception e) {
                System.err.println("[DocumentParser] DOCX 图片 OCR 失败: " + e.getMessage());
            }
        }
    }

    /** 读取旧版 DOC 文档中的段落文本。 */
    private String parseDoc(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path);
             HWPFDocument document = new HWPFDocument(input);
             WordExtractor extractor = new WordExtractor(document)) {
            return extractor.getText();
        }
    }

    /** 使用 PDFBox 提取 PDF 页面中的文字。 */
    private String parsePdf(Path path) throws IOException {
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            return new PDFTextStripper().getText(document);
        }
    }

    /** 读取 XLSX 工作簿，逐行逐单元格提取文本。 */
    private String parseXlsx(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path);
             Workbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(input)) {
            return extractWorkbookText(workbook);
        }
    }

    /** 读取 XLS 工作簿，逐行逐单元格提取文本。 */
    private String parseXls(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path);
             Workbook workbook = new org.apache.poi.hssf.usermodel.HSSFWorkbook(input)) {
            return extractWorkbookText(workbook);
        }
    }

    /** 统一提取工作簿中所有 sheet 的文本。 */
    private String extractWorkbookText(Workbook workbook) {
        StringBuilder text = new StringBuilder();
        for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
            Sheet sheet = workbook.getSheetAt(s);
            if (s > 0) text.append('\n');
            text.append("----- ").append(sheet.getSheetName()).append(" -----\n");
            for (Row row : sheet) {
                StringBuilder rowText = new StringBuilder();
                for (Cell cell : row) {
                    if (!rowText.isEmpty()) rowText.append('\t');
                    rowText.append(getCellText(cell));
                }
                String line = rowText.toString().strip();
                if (!line.isEmpty()) text.append(line).append('\n');
            }
        }
        return text.toString();
    }

    /** 安全获取单元格文本，避免公式或空值异常。 */
    private String getCellText(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                double val = cell.getNumericCellValue();
                if (val == Math.floor(val) && !Double.isInfinite(val)) {
                    yield String.valueOf((long) val);
                }
                yield String.valueOf(val);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try {
                    yield String.valueOf((long) cell.getNumericCellValue());
                } catch (Exception e) {
                    try {
                        yield cell.getStringCellValue();
                    } catch (Exception e2) {
                        yield "";
                    }
                }
            }
            default -> "";
        };
    }

    /** 读取 PPTX 演示文稿，按页提取文字。 */
    private String parsePptx(Path path) throws IOException {
        StringBuilder text = new StringBuilder();
        try (InputStream input = Files.newInputStream(path);
             XMLSlideShow ppt = new XMLSlideShow(input)) {
            int page = 1;
            for (XSLFSlide slide : ppt.getSlides()) {
                text.append("----- 第 ").append(page++).append(" 页 -----\n");
                for (XSLFShape shape : slide.getShapes()) {
                    if (!(shape instanceof XSLFTextShape textShape)) continue;
                    String shapeText = textShape.getText();
                    if (shapeText != null && !shapeText.isBlank()) {
                        text.append(shapeText.strip()).append('\n');
                    }
                }
                text.append('\n');
            }
            if (ocrService != null) {
                recognizePptxImages(ppt, text);
            }
        }
        return text.toString();
    }

    /** 遍历 PPTX 的 /ppt/media/ 图片，调用 OCR 提取文字。 */
    private void recognizePptxImages(XMLSlideShow ppt, StringBuilder text) {
        var parts = getPackageParts(ppt.getPackage());
        if (parts == null) return;
        for (var part : parts) {
            if (!part.getPartName().getName().startsWith("/ppt/media/")) continue;
            try (InputStream imgInput = part.getInputStream()) {
                byte[] imageBytes = imgInput.readAllBytes();
                String imageText = ocrService.recognizeImageBytes(imageBytes);
                if (imageText != null && !imageText.isBlank()) {
                    text.append("\n[图片文字] ").append(imageText);
                }
            } catch (Exception e) {
                System.err.println("[DocumentParser] PPTX 图片 OCR 失败: " + e.getMessage());
            }
        }
    }

    /** 安全获取 OPCPackage 的所有部件，避免检查型异常漏抓。 */
    private static List<org.apache.poi.openxml4j.opc.PackagePart> getPackageParts(
            org.apache.poi.openxml4j.opc.OPCPackage pkg) {
        try {
            return pkg.getParts();
        } catch (Exception e) {
            System.err.println("[DocumentParser] 无法读取包结构: " + e.getMessage());
            return null;
        }
    }

    /** 追加非空文本行，并统一控制行尾格式。 */
    private void appendLine(StringBuilder text, String value) {
        if (value != null && !value.isBlank()) {
            text.append(value.strip()).append('\n');
        }
    }

    /** 从文件名提取小写扩展名，不包含点号。 */
    public static String extension(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 提取文档结构摘要：带编号的段落/页面列表，供 AI 定位插入位置。 */
    public String extractStructure(Path path, String ext) throws Exception {
        return extractStructure(path, ext, false);
    }

    public String extractStructure(Path path, String ext, boolean describeImages) throws Exception {
        return switch (ext) {
            case "docx" -> extractDocxStructure(path, describeImages);
            case "pdf" -> extractPdfStructure(path, describeImages);
            case "pptx" -> extractPptxStructure(path);
            default -> null;
        };
    }

    private String extractDocxStructure(Path path, boolean describeImages) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (InputStream input = Files.newInputStream(path);
             XWPFDocument doc = new XWPFDocument(input)) {
            int paraIdx = 0;
            int imageIdx = 0;
            int page = 1;
            boolean hasPageBoundaries = false;
            for (XWPFParagraph para : doc.getParagraphs()) {
                paraIdx++;
                if (paraIdx > 1 && para.isPageBreak()) {
                    page++;
                    hasPageBoundaries = true;
                }
                String text = para.getText();
                int imageCount = para.getRuns().stream()
                        .mapToInt(r -> r.getEmbeddedPictures().size())
                        .sum();
                boolean hasImage = imageCount > 0;
                String preview = (text == null || text.isBlank())
                        ? (hasImage ? "[图片]" : "[空行]")
                        : text.length() > 40 ? text.substring(0, 40) + "..." : text;
                sb.append("[页").append(page).append("-P").append(paraIdx).append("] ")
                        .append(preview).append("\n");
                for (XWPFRun run : para.getRuns()) {
                    for (var picture : run.getEmbeddedPictures()) {
                        imageIdx++;
                        String description = describeImages && imageIdx <= MAX_DESCRIBED_IMAGES
                                ? describeImage(picture.getPictureData().getData()) : "嵌入图片";
                        sb.append("[页").append(page).append("-IMG").append(imageIdx)
                                .append(" @ P").append(paraIdx).append("] ")
                                .append(description).append("\n");
                    }
                }
                int manualBreaks = para.getRuns().stream()
                        .mapToInt(run -> (int) run.getCTR().getBrList().stream()
                                .filter(br -> STBrType.PAGE.equals(br.getType())).count())
                        .sum();
                int renderedBreaks = para.getRuns().stream()
                        .mapToInt(run -> run.getCTR().sizeOfLastRenderedPageBreakArray())
                        .sum();
                int pageBreaks = Math.max(manualBreaks, renderedBreaks);
                if (pageBreaks > 0) {
                    page += pageBreaks;
                    hasPageBoundaries = true;
                }
            }
            int tableIdx = 0;
            for (XWPFTable table : doc.getTables()) {
                tableIdx++;
                int rows = table.getRows().size();
                int columns = rows == 0 ? 0 : table.getRow(0).getTableCells().size();
                sb.append("[TABLE").append(tableIdx).append("] ")
                        .append(rows).append("行")
                        .append(columns).append("列\n");
            }
            if (!hasPageBoundaries) {
                sb.insert(0, "[DOCX分页边界不可用：页码只能作为提示，请优先使用原文片段或段落锚点]\n");
            }
        }
        return sb.toString();
    }

    private String extractPdfStructure(Path path, boolean describeImages) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (PDDocument pdf = Loader.loadPDF(path.toFile())) {
            int pageCount = pdf.getNumberOfPages();
            int describedImages = 0;
            for (int i = 0; i < pageCount; i++) {
                PDPage page = pdf.getPage(i);
                sb.append("[页").append(i + 1).append("]\n");
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(i + 1);
                stripper.setEndPage(i + 1);
                String text = stripper.getText(pdf);
                int textIndex = 0;
                if (text != null) {
                    for (String line : text.split("\\R")) {
                        String value = line.strip();
                        if (value.isBlank()) continue;
                        textIndex++;
                        String preview = value.length() > 60 ? value.substring(0, 60) + "..." : value;
                        sb.append("  [页").append(i + 1).append("-T")
                                .append(textIndex).append("] ").append(preview).append("\n");
                    }
                }

                int imageIndex = 0;
                if (page.getResources() != null) {
                    for (var name : page.getResources().getXObjectNames()) {
                        if (page.getResources().getXObject(name) instanceof PDImageXObject image) {
                            imageIndex++;
                            describedImages++;
                            String description = "嵌入图片";
                            if (describeImages && describedImages <= MAX_DESCRIBED_IMAGES) {
                                try {
                                    ByteArrayOutputStream imageBytes = new ByteArrayOutputStream();
                                    ImageIO.write(image.getImage(), "png", imageBytes);
                                    description = describeImage(imageBytes.toByteArray());
                                } catch (Exception ignored) {
                                }
                            }
                            sb.append("  [页").append(i + 1).append("-IMG")
                                    .append(imageIndex).append("] ").append(description).append("\n");
                        }
                    }
                }
                if (textIndex == 0 && imageIndex == 0) {
                    sb.append("  [空白页]\n");
                }
            }
        }
        return sb.toString();
    }

    private String describeImage(byte[] imageBytes) {
        if (ocrService == null) return "嵌入图片";
        try {
            String text = ocrService.recognizeImageBytes(imageBytes);
            if (text == null || text.isBlank()) return "嵌入图片（未识别到文字）";
            String normalized = text.replaceAll("\\s+", " ").strip();
            return "图片文字：" + (normalized.length() > 80
                    ? normalized.substring(0, 80) + "..." : normalized);
        } catch (Exception e) {
            return "嵌入图片（识别失败）";
        }
    }

    private String extractPptxStructure(Path path) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (InputStream input = Files.newInputStream(path);
             XMLSlideShow ppt = new XMLSlideShow(input)) {
            int slideIdx = 0;
            for (XSLFSlide slide : ppt.getSlides()) {
                slideIdx++;
                StringBuilder slideText = new StringBuilder();
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape ts) {
                        String t = ts.getText();
                        if (t != null && !t.isBlank()) slideText.append(t.strip()).append(" ");
                    }
                }
                String preview = slideText.isEmpty() ? "[空白幻灯片]"
                        : slideText.length() > 50 ? slideText.substring(0, 50) + "..." : slideText.toString();
                sb.append("[Slide").append(slideIdx).append("] ").append(preview).append("\n");
            }
        }
        return sb.toString();
    }
}
