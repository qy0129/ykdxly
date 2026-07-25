package com.example.ilink.feature.express;

import com.example.ilink.config.Config;
import com.example.ilink.feature.express.ExpressService.ExpressResult;
import com.example.ilink.feature.express.ExpressService.TrackingItem;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.ServerSocket;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ExpressPageService {

    public final int actualPort;
    private volatile String baseUrl;

    private final Map<String, ExpressResult> pageStore = new ConcurrentHashMap<>();

    public ExpressPageService() {
        this.actualPort = findAvailablePort(Config.EXPRESS_PORT);
        String configuredUrl = Config.EXPRESS_BASE_URL;
        if (configuredUrl != null && !configuredUrl.isBlank()) {
            baseUrl = configuredUrl.replaceAll("/$", "");
        } else {
            baseUrl = "http://localhost:" + actualPort;
        }
    }

    private static int findAvailablePort(int start) {
        int maxAttempts = 20;
        for (int port = start; port < start + maxAttempts; port++) {
            try (ServerSocket ss = new ServerSocket(port)) {
                return port;
            } catch (Exception ignored) {
            }
        }
        return start;
    }

    public String createPage(ExpressResult result) {
        if (result == null || !result.success()) return "";
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        pageStore.put(token, result);
        return baseUrl + "/express/view/" + token;
    }

    public void useBaseUrl(String value) {
        if (value == null || value.isBlank()) return;
        baseUrl = value.trim().replaceAll("/$", "");
    }

    public ExpressResult getResult(String token) {
        return pageStore.get(token);
    }

    public List<TrackingItem> getItems(String token) {
        ExpressResult result = pageStore.get(token);
        return result != null ? result.items() : List.of();
    }

    public byte[] generateQrCode(String url, int size) {
        try {
            Hashtable<EncodeHintType, String> hints = new Hashtable<>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            BitMatrix matrix = new QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, size, size, hints);
            BufferedImage image = MatrixToImageWriter.toBufferedImage(matrix);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", out);
            return out.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }
}
