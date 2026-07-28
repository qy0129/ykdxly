package com.example.ilink.capabilities.visual;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisualCardRendererTest {

    @Test
    void rendersReadablePngWithLongChineseText() throws Exception {
        VisualCardRenderer renderer = new VisualCardRenderer(new QrCodeService());
        String body = "今天的安排\n" + "先完成最重要的一件事，再处理其他事项。".repeat(80);
        byte[] bytes = renderer.render(VisualCard.of("今日简报", "2026年7月24日", body), 1, 4);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));

        assertNotNull(image);
        assertEquals(VisualCardRenderer.WIDTH, image.getWidth());
        assertEquals(VisualCardRenderer.HEIGHT, image.getHeight());
        assertTrue(bytes.length > 10_000);
    }

    @Test
    void createsDecodableQrCode() throws Exception {
        String url = "https://example.com/dashboard?token=abc123";
        BufferedImage qr = new QrCodeService().create(url, 360);
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(qr)));
        assertEquals(url, new MultiFormatReader().decode(bitmap).getText());
    }
}
