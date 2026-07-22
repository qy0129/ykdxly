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

/**
 * 文档生成器。
 *
 * <p>将纯文本内容生成 DOCX 或 PDF 字节。生成器只关心文件格式，
 * 不负责意图识别、模型调用和媒体文件落盘。</p>
 */
public final class DocumentGenerator {
    /** 生成包含标题和正文的 DOCX 字节数组。 */
    public byte[] createDocx(String title, String content) throws IOException {
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

    /** 生成包含标题和正文的 PDF 字节数组。 */
    public byte[] createPdf(String title, String content) throws IOException {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var font = loadPdfFont(document);
            addPdfPage(document, font, title, content);
            document.save(output);
            return output.toByteArray();
        }
    }

    /** 把一页标题和正文写入 PDF 文档。 */
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

    /** 优先加载项目中的中文字体，找不到时退回 PDF 内置字体。 */
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

    /** 设置 DOCX 页面为 A4 纸张和统一页边距。 */
    private void configureA4Page(XWPFDocument document) {
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

    /** 清理 PDF 内置字体无法编码的字符，避免写入失败。 */
    private String safePdfText(String text) {
        return text.replace("\t", " ").replace("\u0000", "");
    }


}
