package com.example.ilink.capabilities.documents;

import com.example.ilink.capabilities.image.VisionService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Base64;

/**
 * OCR 服务。
 *
 * <p>将扫描版 PDF 每页渲染为图片，调用多模态视觉模型识别文字，
 * 拼接后返回整体文本。无需额外 OCR 引擎，复用 Qwen-VL API。</p>
 */
public final class OcrService {

    private final VisionService visionService;

    /** 创建 OCR 服务。 */
    public OcrService(VisionService visionService) {
        this.visionService = visionService;
    }

    /** 使用多模态模型识别 PDF 所有页面的文字。 */
    public String recognize(Path pdfPath) throws Exception {
        StringBuilder result = new StringBuilder();
        try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
            PDFRenderer renderer = new PDFRenderer(document);
            int totalPages = Math.min(document.getNumberOfPages(), 20);
            for (int i = 0; i < totalPages; i++) {
                try {
                    String pageText = recognizePage(renderer, i);
                    if (pageText != null && !pageText.isBlank()) {
                        result.append("----- 第 ").append(i + 1).append(" 页 -----\n");
                        result.append(pageText).append('\n');
                    } else {
                        System.err.println("[OcrService] 第 " + (i + 1) + " 页识别结果为空");
                    }
                } catch (Exception e) {
                    System.err.println("[OcrService] 第 " + (i + 1) + " 页识别失败: " + e.getMessage());
                }
            }
        }
        return result.toString().strip();
    }

    /** 将单页 PDF 渲染为图片后调用视觉模型识别。100 DPI 兼顾清晰度与传输大小。 */
    private String recognizePage(PDFRenderer renderer, int pageIndex) throws Exception {
        var image = renderer.renderImageWithDPI(pageIndex, 100);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        boolean written = ImageIO.write(image, "png", baos);
        if (!written) {
            throw new IOException("ImageIO 无法将渲染结果编码为 PNG");
        }
        return recognizeImageBytes(baos.toByteArray());
    }

    /** 对图片字节调用视觉模型提取文字。可直接用于 DOCX/PPTX 嵌入图片。 */
    public String recognizeImageBytes(byte[] imageBytes) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
        if (image == null) {
            throw new IOException("ImageIO 无法解码图片字节");
        }
        boolean written = ImageIO.write(image, "png", baos);
        if (!written) {
            throw new IOException("ImageIO 无法将图片编码为 PNG");
        }
        String base64 = Base64.getEncoder().encodeToString(baos.toByteArray());
        return visionService.vision("请提取这张图片中所有文字内容，保持原文段落格式，不要遗漏任何文字。", base64);
    }
}
