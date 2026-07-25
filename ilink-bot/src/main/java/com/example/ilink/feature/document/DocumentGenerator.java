package com.example.ilink.feature.document;

import com.deepoove.poi.XWPFTemplate;
import com.deepoove.poi.config.Configure;
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
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBody;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;

import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;

import java.awt.Dimension;
import java.awt.Rectangle;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

public final class DocumentGenerator {

    private static byte[] defaultTemplateBytes;

    private static byte[] getDefaultTemplate() {
        if (defaultTemplateBytes == null) {
            defaultTemplateBytes = createDefaultTemplateBytes();
        }
        return defaultTemplateBytes;
    }

    /** 程序化生成默认模板（标题 + 正文区），含 {{title}} 和 {{content}} 占位变量。 */
    private static byte[] createDefaultTemplateBytes() {
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            configureA4Page(doc);

            XWPFParagraph titlePara = doc.createParagraph();
            titlePara.setStyle("Title");
            XWPFRun titleRun = titlePara.createRun();
            titleRun.setFontSize(18);
            titleRun.setBold(true);
            titleRun.setText("{{title}}");

            doc.createParagraph();

            XWPFParagraph contentPara = doc.createParagraph();
            XWPFRun contentRun = contentPara.createRun();
            contentRun.setFontSize(11);
            contentRun.setText("{{content}}");

            doc.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("无法创建默认模板", e);
        }
    }

    /** 生成纯文本文件（TXT/MD/CSV），使用 UTF-8 编码。 */
    public byte[] createPlainText(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    /** 将 AI 生成的表格文本转为真实的 XLSX 文件。优先解析 Markdown 管道表格，后备 CSV。 */
    public byte[] createXlsx(String content) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet sheet = workbook.createSheet("Sheet1");
            String[] lines = content.split("\\R");

            boolean hasPipeTable = false;
            for (String line : lines) {
                if (line.strip().startsWith("|")) {
                    hasPipeTable = true;
                    break;
                }
            }

            int rowIdx = 0;
            if (hasPipeTable) {
                for (String line : lines) {
                    line = line.strip();
                    if (!line.startsWith("|")) continue;
                    String noPipes = line.replace("|", "").strip();
                    if (noPipes.matches("[-\\s]+")) continue;
                    String[] cells = line.split("\\|", -1);
                    XSSFRow row = sheet.createRow(rowIdx++);
                    int cellIdx = 0;
                    for (int j = 1; j < cells.length - 1; j++) {
                        row.createCell(cellIdx++).setCellValue(cells[j].strip());
                    }
                }
            } else {
                for (String line : lines) {
                    line = line.strip();
                    if (line.isBlank()) continue;
                    String[] cells = line.split(",");
                    XSSFRow row = sheet.createRow(rowIdx++);
                    for (int i = 0; i < cells.length; i++) {
                        String val = cells[i].strip().replaceAll("^\"(.*)\"$", "$1");
                        row.createCell(i).setCellValue(val);
                    }
                }
            }

            XSSFRow headerRow = sheet.getRow(0);
            if (headerRow != null) {
                XSSFCellStyle headerStyle = workbook.createCellStyle();
                XSSFFont font = workbook.createFont();
                font.setBold(true);
                headerStyle.setFont(font);
                for (int i = 0; i < headerRow.getLastCellNum(); i++) {
                    XSSFCell cell = headerRow.getCell(i);
                    if (cell != null) cell.setCellStyle(headerStyle);
                }
            }

            for (int i = 0; i < 20; i++) {
                try { sheet.autoSizeColumn(i); } catch (Exception e) { break; }
            }

            workbook.write(out);
            return out.toByteArray();
        }
    }

    /** 将 Markdown 按标题分页生成 PPTX。一级/二级标题各占一页，正文作为项目符号。 */
    public byte[] createPptx(String title, String content) throws IOException {
        try (XMLSlideShow ppt = new XMLSlideShow();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ppt.setPageSize(new Dimension(720, 540));

            String[] lines = content.split("\\R");
            List<String> slideBuffer = new ArrayList<>();
            String slideTitle = null;

            for (String line : lines) {
                if (line.startsWith("# ") || line.startsWith("## ")) {
                    if (slideTitle != null) {
                        addPptxSlide(ppt, slideTitle, slideBuffer);
                    }
                    int headingLevel = line.startsWith("## ") ? 2 : 1;
                    slideTitle = line.substring(headingLevel + 1).strip();
                    slideBuffer.clear();
                } else {
                    slideBuffer.add(line);
                }
            }
            if (slideTitle != null) {
                addPptxSlide(ppt, slideTitle, slideBuffer);
            }

            if (ppt.getSlides().isEmpty()) {
                addPptxSlide(ppt, title, List.of(lines));
            }

            ppt.write(out);
            return out.toByteArray();
        }
    }

    private void addPptxSlide(XMLSlideShow ppt, String slideTitle, List<String> lines) {
        XSLFSlide slide = ppt.createSlide();

        XSLFTextBox titleBox = slide.createTextBox();
        titleBox.setAnchor(new Rectangle(50, 30, 620, 50));
        XSLFTextParagraph titlePara = titleBox.addNewTextParagraph();
        XSLFTextRun titleRun = titlePara.addNewTextRun();
        titleRun.setText(slideTitle);
        titleRun.setFontSize(28.0);
        titleRun.setBold(true);

        XSLFTextBox bodyBox = slide.createTextBox();
        bodyBox.setAnchor(new Rectangle(50, 90, 620, 420));
        for (String line : lines) {
            String text = line.strip();
            if (text.isBlank()) continue;
            XSLFTextParagraph para = bodyBox.addNewTextParagraph();
            boolean isBullet = text.startsWith("- ") || text.startsWith("* ");
            if (isBullet) {
                text = text.substring(2);
                para.setBullet(true);
            }
            XSLFTextRun run = para.addNewTextRun();
            run.setText(text);
            run.setFontSize(16.0);
        }
    }

    /** 使用 poi-tl 模板渲染生成 DOCX，失败时降级为 POI 原生方式。 */
    public byte[] createDocx(String title, String content) throws IOException {
        try {
            var templateBytes = getDefaultTemplate();
            var data = new HashMap<String, Object>();
            data.put("title", title);
            data.put("content", content);

            try (InputStream input = new ByteArrayInputStream(templateBytes);
                 XWPFTemplate template = XWPFTemplate.compile(input, Configure.createDefault());
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                template.render(data);
                template.write(out);
                return out.toByteArray();
            }
        } catch (Exception e) {
            System.err.println("[DocumentGenerator] poi-tl 模板渲染失败，降级为 POI 原生: " + e.getMessage());
            return createDocxNative(title, content);
        }
    }

    /** POI 原生方式生成 DOCX（poi-tl 降级备选）。 */
    private byte[] createDocxNative(String title, String content) throws IOException {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            configureA4Page(document);
            XWPFParagraph titleParagraph = document.createParagraph();
            titleParagraph.setStyle("Title");
            titleParagraph.createRun().setText(title);
            for (String line : content.split("\\R", -1)) {
                document.createParagraph().createRun().setText(line);
            }
            document.write(output);
            return output.toByteArray();
        }
    }

    /** 生成包含标题和正文的 PDF。 */
    public byte[] createPdf(String title, String content) throws IOException {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var font = loadPdfFont(document);
            addPdfPage(document, font, title, content);
            document.save(output);
            return output.toByteArray();
        }
    }

    private void addPdfPage(PDDocument document, Object font, String title, String content) throws IOException {
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        float margin = 48;
        float y = page.getMediaBox().getHeight() - margin;
        float lineHeight = 18;
        PDPageContentStream stream = new PDPageContentStream(document, page);
        stream.beginText();
        stream.setFont((org.apache.pdfbox.pdmodel.font.PDFont) font, 16);
        stream.newLineAtOffset(margin, y);
        stream.showText(safePdfText(title));
        stream.setFont((org.apache.pdfbox.pdmodel.font.PDFont) font, 10);
        stream.newLineAtOffset(0, -28);

        for (String rawLine : content.split("\\R", -1)) {
            String line = rawLine.isEmpty() ? " " : rawLine;
            while (!line.isEmpty()) {
                if (y < margin + lineHeight) {
                    stream.endText();
                    stream.close();
                    page = new PDPage(PDRectangle.A4);
                    document.addPage(page);
                    y = page.getMediaBox().getHeight() - margin;
                    stream = new PDPageContentStream(document, page);
                    stream.beginText();
                    stream.setFont((org.apache.pdfbox.pdmodel.font.PDFont) font, 10);
                    stream.newLineAtOffset(margin, y);
                }
                int length = Math.min(70, line.length());
                stream.showText(safePdfText(line.substring(0, length)));
                stream.newLineAtOffset(0, -lineHeight);
                y -= lineHeight;
                line = line.substring(length);
            }
        }
        stream.endText();
        stream.close();
    }

    private org.apache.pdfbox.pdmodel.font.PDFont loadPdfFont(PDDocument document) throws IOException {
        String[] candidates = {
                "C:/Windows/Fonts/simhei.ttf",
                "C:/Windows/Fonts/Deng.ttf",
                "C:/Windows/Fonts/Noto Sans SC (TrueType).otf",
                "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"
        };
        for (String candidate : candidates) {
            Path path = Path.of(candidate);
            if (Files.exists(path)) {
                return PDType0Font.load(document, path.toFile());
            }
        }
        return new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    }

    private static void configureA4Page(XWPFDocument document) {
        CTBody body = document.getDocument().getBody();
        CTSectPr section = body.isSetSectPr() ? body.getSectPr() : body.addNewSectPr();
        CTPageSz pageSize = section.isSetPgSz() ? section.getPgSz() : section.addNewPgSz();
        pageSize.setW(BigInteger.valueOf(11906));
        pageSize.setH(BigInteger.valueOf(16838));

        CTPageMar margins = section.isSetPgMar() ? section.getPgMar() : section.addNewPgMar();
        margins.setTop(BigInteger.valueOf(1440));
        margins.setRight(BigInteger.valueOf(1440));
        margins.setBottom(BigInteger.valueOf(1440));
        margins.setLeft(BigInteger.valueOf(1440));
    }

    private String safePdfText(String text) {
        return text.replace("\t", " ").replace("\u0000", "");
    }

    /** Markdown → DOCX 渲染器，支持标题/粗体/列表/管道表格。 */
    public byte[] renderMarkdownDocx(String content) throws IOException {
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            configureA4Page(doc);

            String[] lines = content.split("\\R", -1);
            boolean inList = false;

            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                boolean isNextList = i + 1 < lines.length
                        && (lines[i + 1].strip().matches("^[-*] .+") || lines[i + 1].strip().matches("^\\d+\\. .+"));
                boolean isLastLine = i == lines.length - 1;

                if (line.strip().isBlank()) {
                    if (inList) inList = false;
                    continue;
                }

                String trimmed = line.strip();

                // 表格
                if (trimmed.startsWith("|")) {
                    String noPipes = trimmed.replace("|", "").strip();
                    if (noPipes.matches("[-\\s]+")) continue;
                    String[] cells = trimmed.split("\\|", -1);
                    if (doc.getTables().isEmpty()
                            || doc.getTables().getLast().getRows().isEmpty()
                            || doc.getTables().getLast().getRow(0).getTableCells().size() != cells.length - 2) {
                        doc.createParagraph();
                    }
                    XWPFTable table = doc.getTables().isEmpty()
                            ? doc.createTable() : doc.getTables().getLast();
                    int destRow = table.getNumberOfRows();
                    if (table.getRow(destRow - 1).getTableCells().size() != cells.length - 2) {
                        table = doc.createTable();
                        destRow = 0;
                    }
                    XWPFTableRow tableRow = table.getRow(0).getTableCells().size() == cells.length - 2
                            ? table.createRow() : table.getRow(destRow);
                    for (int j = 1; j < cells.length - 1; j++) {
                        int ci = j - 1;
                        if (tableRow.getCell(ci) == null) tableRow.addNewTableCell();
                        tableRow.getCell(ci).setText(cells[j].strip());
                    }
                    inList = false;
                    continue;
                }

                // 标题
                if (trimmed.startsWith("# ")) {
                    XWPFParagraph p = doc.createParagraph();
                    p.setStyle("Title");
                    addMarkdownRun(p, trimmed.substring(2));
                    inList = false;
                    continue;
                }
                if (trimmed.startsWith("## ")) {
                    XWPFParagraph p = doc.createParagraph();
                    p.setStyle("Heading2");
                    addMarkdownRun(p, trimmed.substring(3));
                    inList = false;
                    continue;
                }
                if (trimmed.startsWith("### ")) {
                    XWPFParagraph p = doc.createParagraph();
                    p.setStyle("Heading3");
                    addMarkdownRun(p, trimmed.substring(4));
                    inList = false;
                    continue;
                }

                // 列表项
                boolean isListItem = false;
                String listText = trimmed;
                if (trimmed.matches("^[-*] .+")) {
                    isListItem = true;
                    listText = trimmed.substring(2);
                } else if (trimmed.matches("^\\d+\\. .+")) {
                    isListItem = true;
                    listText = trimmed.substring(trimmed.indexOf(' ') + 1);
                }

                if (isListItem) {
                    XWPFParagraph p = doc.createParagraph();
                    p.setIndentationLeft(420);
                    if (trimmed.matches("^\\d+\\. .+")) {
                        p.setNumID(new java.math.BigInteger("1"));
                    } else {
                        p.setNumID(new java.math.BigInteger("2"));
                    }
                    addMarkdownRun(p, listText);
                    inList = true;
                    continue;
                }

                if (inList && !isNextList && !isLastLine) {
                    inList = false;
                }

                // 普通段落
                XWPFParagraph p = doc.createParagraph();
                addMarkdownRun(p, trimmed);
                inList = false;
            }

            doc.write(out);
            return out.toByteArray();
        }
    }

    private void addMarkdownRun(XWPFParagraph p, String text) {
        int idx = 0;
        while (idx < text.length()) {
            int boldStart = text.indexOf("**", idx);
            if (boldStart < 0) {
                XWPFRun r = p.createRun();
                r.setText(text.substring(idx));
                r.setFontSize(11);
                break;
            }
            if (boldStart > idx) {
                XWPFRun r = p.createRun();
                r.setText(text.substring(idx, boldStart));
                r.setFontSize(11);
            }
            int boldEnd = text.indexOf("**", boldStart + 2);
            if (boldEnd < 0) {
                XWPFRun r = p.createRun();
                r.setText(text.substring(boldStart));
                r.setFontSize(11);
                break;
            }
            XWPFRun r = p.createRun();
            r.setText(text.substring(boldStart + 2, boldEnd));
            r.setBold(true);
            r.setFontSize(11);
            idx = boldEnd + 2;
        }
    }

    /** Markdown → PDF 渲染器（标题加大字号）。 */
    public byte[] renderMarkdownPdf(String content) throws IOException {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var font = loadPdfFont(doc);
            float margin = 48;
            float y = 0;
            PDPageContentStream stream = null;

            for (String rawLine : content.split("\\R", -1)) {
                String line = rawLine.strip();
                if (line.isBlank()) continue;

                float fontSize = 10;
                boolean isTitle = false;
                String text = line;

                if (line.startsWith("# ")) {
                    text = line.substring(2);
                    fontSize = 18;
                    isTitle = true;
                } else if (line.startsWith("## ")) {
                    text = line.substring(3);
                    fontSize = 14;
                    isTitle = true;
                } else if (line.startsWith("### ")) {
                    text = line.substring(4);
                    fontSize = 12;
                    isTitle = true;
                } else if (line.startsWith("- ") || line.startsWith("* ")) {
                    text = "  • " + line.substring(2);
                } else if (line.matches("^\\d+\\. .+")) {
                    text = "  " + line;
                }

                if (stream == null || y < margin + fontSize + 4) {
                    if (stream != null) { stream.endText(); stream.close(); }
                    PDPage page = new PDPage(PDRectangle.A4);
                    doc.addPage(page);
                    y = page.getMediaBox().getHeight() - margin;
                    stream = new PDPageContentStream(doc, page);
                    stream.beginText();
                    stream.setFont(font, fontSize);
                    stream.newLineAtOffset(margin, y);
                }

                if (!isTitle) stream.setFont(font, fontSize);
                stream.showText(safePdfText(text));
                stream.newLineAtOffset(0, -(fontSize + 4));
                y -= (fontSize + 4);
                if (isTitle) stream.setFont(font, 10);
            }

            if (stream != null) { stream.endText(); stream.close(); }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
