package com.example.ilink.feature.document;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.poi.util.Units;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 纯 Java 文档格式转换（POI + PDFBox），保留文字和图片。
 * 不依赖任何外部软件。
 */
public final class DocumentFormatConverter {

    /** DOCX → PDF：读取段落、表格、图片，渲染为 PDF。 */
    public byte[] docxToPdf(Path docxPath) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(Files.newInputStream(docxPath));
             PDDocument pdf = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            PDFont font = loadFont(pdf);
            float margin = 50;
            float pageWidth = PDRectangle.A4.getWidth();
            float pageHeight = PDRectangle.A4.getHeight();
            float usableWidth = pageWidth - 2 * margin;

            PDPage currentPage = new PDPage(PDRectangle.A4);
            pdf.addPage(currentPage);
            PDPageContentStream stream = new PDPageContentStream(pdf, currentPage);
            float y = pageHeight - margin;

            for (IBodyElement element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph para) {
                    // 检查段落中的图片
                    for (XWPFRun run : para.getRuns()) {
                        for (XWPFPicture pic : run.getEmbeddedPictures()) {
                            byte[] imgBytes = pic.getPictureData().getData();
                            // 需要新页
                            if (y < margin + 150) {
                                stream.close();
                                currentPage = new PDPage(PDRectangle.A4);
                                pdf.addPage(currentPage);
                                stream = new PDPageContentStream(pdf, currentPage);
                                y = pageHeight - margin;
                            }

                            try {
                                PDImageXObject image = PDImageXObject.createFromByteArray(pdf, imgBytes, "image");
                                float imgWidth = image.getWidth();
                                float imgHeight = image.getHeight();
                                // 缩放到页面宽度内
                                if (imgWidth > usableWidth) {
                                    float scale = usableWidth / imgWidth;
                                    imgWidth *= scale;
                                    imgHeight *= scale;
                                }
                                if (imgHeight > y - margin) {
                                    float scale = (y - margin) / imgHeight;
                                    imgWidth *= scale;
                                    imgHeight *= scale;
                                }
                                y -= imgHeight;
                                stream.drawImage(image, margin, y, imgWidth, imgHeight);
                                y -= 10;
                            } catch (Exception ignored) {
                                // 图片格式不支持时跳过
                            }
                        }
                    }

                    // 写入文字
                    String text = para.getText();
                    if (text != null && !text.isBlank()) {
                        float fontSize = 11;
                        if (para.getStyle() != null) {
                            String style = para.getStyle();
                            if (style.contains("Title") || style.contains("Heading1")) fontSize = 18;
                            else if (style.contains("Heading2")) fontSize = 14;
                            else if (style.contains("Heading3")) fontSize = 12;
                        }
                        float lineHeight = fontSize + 6;
                        if (y < margin + lineHeight) {
                            stream.close();
                            currentPage = new PDPage(PDRectangle.A4);
                            pdf.addPage(currentPage);
                            stream = new PDPageContentStream(pdf, currentPage);
                            y = pageHeight - margin;
                        }

                        // 自动换行
                        List<String> lines = wrapText(text, font, fontSize, usableWidth);
                        for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
                            String line = lines.get(lineIndex);
                            if (y < margin + lineHeight) {
                                stream.endText();
                                stream.close();
                                currentPage = new PDPage(PDRectangle.A4);
                                pdf.addPage(currentPage);
                                stream = new PDPageContentStream(pdf, currentPage);
                                y = pageHeight - margin;
                                stream.beginText();
                                stream.setFont(font, fontSize);
                                stream.newLineAtOffset(margin, y);
                            } else if (lineIndex == 0) {
                                stream.beginText();
                                stream.setFont(font, fontSize);
                                stream.newLineAtOffset(margin, y);
                            } else {
                                stream.newLineAtOffset(0, -lineHeight);
                            }
                            stream.showText(safePdfText(line));
                            y -= lineHeight;
                        }
                        if (!lines.isEmpty()) {
                            stream.endText();
                        }
                    }

                } else if (element instanceof XWPFTable table) {
                    // 简单表格处理：每行转为一行文字
                    float lineHeight = 15;
                    for (XWPFTableRow row : table.getRows()) {
                        StringBuilder sb = new StringBuilder();
                        for (XWPFTableCell cell : row.getTableCells()) {
                            if (!sb.isEmpty()) sb.append(" | ");
                            sb.append(cell.getText());
                        }
                        if (y < margin + lineHeight) {
                            stream.close();
                            currentPage = new PDPage(PDRectangle.A4);
                            pdf.addPage(currentPage);
                            stream = new PDPageContentStream(pdf, currentPage);
                            y = pageHeight - margin;
                        }
                        stream.beginText();
                        stream.setFont(font, 10);
                        stream.newLineAtOffset(margin, y);
                        stream.showText(safePdfText(sb.toString()));
                        stream.endText();
                        y -= lineHeight;
                    }
                    y -= 10;
                }
            }

            stream.close();
            pdf.save(out);
            return out.toByteArray();
        }
    }

    /** PDF → DOCX：提取文字和图片，写入 DOCX。 */
    public byte[] pdfToDocx(Path pdfPath) throws IOException {
        try (PDDocument pdf = org.apache.pdfbox.Loader.loadPDF(pdfPath.toFile());
             XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            int pageCount = pdf.getNumberOfPages();

            for (int i = 0; i < pageCount; i++) {
                PDPage page = pdf.getPage(i);

                // 提取该页文字
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(i + 1);
                stripper.setEndPage(i + 1);
                String pageText = stripper.getText(pdf);

                if (pageText != null && !pageText.isBlank()) {
                    for (String line : pageText.split("\\R")) {
                        if (line.isBlank()) continue;
                        XWPFParagraph para = doc.createParagraph();
                        XWPFRun run = para.createRun();
                        run.setText(line);
                        run.setFontSize(11);
                    }
                }

                // 提取该页图片
                try {
                    var resources = page.getResources();
                    if (resources != null) {
                        for (var name : resources.getXObjectNames()) {
                            var xObject = resources.getXObject(name);
                            if (xObject instanceof org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject img) {
                                BufferedImage bImg = img.getImage();
                                if (bImg != null) {
                                    ByteArrayOutputStream imgOut = new ByteArrayOutputStream();
                                    ImageIO.write(bImg, "png", imgOut);
                                    byte[] imgBytes = imgOut.toByteArray();

                                    XWPFParagraph imgPara = doc.createParagraph();
                                    XWPFRun imgRun = imgPara.createRun();
                                    try (InputStream imgIn = new ByteArrayInputStream(imgBytes)) {
                                        int width = Math.min(bImg.getWidth(), 500);
                                        int height = (int) ((double) bImg.getHeight() / bImg.getWidth() * width);
                                        imgRun.addPicture(imgIn, XWPFDocument.PICTURE_TYPE_PNG,
                                                "image_" + i + "_" + name.getName() + ".png",
                                                Units.toEMU(width), Units.toEMU(height));
                                    } catch (Exception ignored) {
                                        // 图片插入失败时跳过
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {
                    // 某些 PDF 图片提取失败时跳过
                }

                // 页间分隔
                if (i < pageCount - 1) {
                    XWPFParagraph breakPara = doc.createParagraph();
                    breakPara.createRun().addBreak(BreakType.PAGE);
                }
            }

            doc.write(out);
            return out.toByteArray();
        }
    }

    private PDFont loadFont(PDDocument document) throws IOException {
        String[] candidates = {
                "C:/Windows/Fonts/simhei.ttf",
                "C:/Windows/Fonts/Deng.ttf",
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

    private List<String> wrapText(String text, PDFont font, float fontSize, float maxWidth) throws IOException {
        List<String> lines = new ArrayList<>();
        String remaining = text;
        while (!remaining.isEmpty()) {
            int end = remaining.length();
            float width = font.getStringWidth(safePdfText(remaining)) / 1000 * fontSize;
            if (width <= maxWidth) {
                lines.add(remaining);
                break;
            }
            // 二分查找换行点
            int lo = 1, hi = end;
            while (lo < hi) {
                int mid = (lo + hi + 1) / 2;
                float w = font.getStringWidth(safePdfText(remaining.substring(0, mid))) / 1000 * fontSize;
                if (w <= maxWidth) lo = mid;
                else hi = mid - 1;
            }
            lines.add(remaining.substring(0, lo));
            remaining = remaining.substring(lo);
        }
        return lines;
    }

    private String safePdfText(String text) {
        return text.replace("\t", " ").replace("\u0000", "")
                .replace("\r", "").replace("\n", "");
    }
}
