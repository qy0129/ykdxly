package com.example.ilink.capabilities.visual;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.awt.image.BufferedImage;
import java.util.Map;

/** 将长链接转换为可被微信扫码识别的二维码。 */
public final class QrCodeService {

    public BufferedImage create(String content, int size) {
        if (content == null || content.isBlank()) return null;
        try {
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size,
                    Map.of(EncodeHintType.CHARACTER_SET, "UTF-8",
                            EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                            EncodeHintType.MARGIN, 1));
            return MatrixToImageWriter.toBufferedImage(matrix);
        } catch (WriterException e) {
            throw new IllegalArgumentException("二维码生成失败", e);
        }
    }
}
