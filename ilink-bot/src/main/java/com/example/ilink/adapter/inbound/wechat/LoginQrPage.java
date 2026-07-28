package com.example.ilink.adapter.inbound.wechat;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Stream;

/** 将 SDK 返回的二维码渲染为本地浏览器可打开的扫码页面。 */
public final class LoginQrPage {

    private static final int EXPIRE_SECONDS = 300;
    private static final Path PAGE_DIRECTORY = Path.of(
            System.getProperty("java.io.tmpdir"), "ilink-bot-login-page");
    private static final Path PAGE_FILE = PAGE_DIRECTORY.resolve("templates").resolve("qrcode-page.html");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** 写入带二维码数据的 HTML 页面，返回可避免浏览器缓存的本地地址。 */
    public URI render(String qrcode) throws Exception {
        copyStaticResources();

        LocalDateTime now = LocalDateTime.now();
        String html = readResource("/templates/qrcode-page.html")
                .replace("${QR_CODE_DATA}", toDataUri(qrcode))
                .replace("${GEN_TIME}", now.format(TIME_FORMAT))
                .replace("${EXPIRE_TIME}", now.plusSeconds(EXPIRE_SECONDS).format(TIME_FORMAT))
                .replace("${QR_EXPIRE_SECONDS}", String.valueOf(EXPIRE_SECONDS));

        Files.createDirectories(PAGE_FILE.getParent());
        Files.writeString(PAGE_FILE, html, StandardCharsets.UTF_8);
        return URI.create(PAGE_FILE.toUri() + "?refresh=" + System.currentTimeMillis());
    }

    /** 删除程序运行期间创建的本地页面和静态资源。 */
    public void cleanup() {
        if (!Files.exists(PAGE_DIRECTORY)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(PAGE_DIRECTORY)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 临时页面删除失败不影响机器人停止。
                }
            });
        } catch (IOException ignored) {
            // 临时页面删除失败不影响机器人停止。
        }
    }

    /** 将 URL 二维码转成图片；SDK 已返回图片时直接规范为 Data URI。 */
    private String toDataUri(String qrcode) throws Exception {
        if (qrcode == null || qrcode.isBlank()) {
            throw new IllegalArgumentException("登录二维码为空");
        }
        if (qrcode.startsWith("http://") || qrcode.startsWith("https://")) {
            return generateQrImage(qrcode);
        }
        if (qrcode.startsWith("data:image/")) {
            return qrcode;
        }
        return "data:image/png;base64," + Base64.getEncoder()
                .encodeToString(Base64.getDecoder().decode(qrcode));
    }

    /** 使用 ZXing 将二维码内容生成 PNG Data URI。 */
    private String generateQrImage(String content) throws Exception {
        QRCodeWriter writer = new QRCodeWriter();
        BitMatrix matrix = writer.encode(content, BarcodeFormat.QR_CODE, 300, 300,
                Map.of(EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name()));
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            MatrixToImageWriter.writeToStream(matrix, "PNG", output);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
        }
    }

    /** 从 JAR 内复制 CSS 和 JavaScript，让本地 HTML 能完整展示页面。 */
    private void copyStaticResources() throws IOException {
        copyResource("/static/css/qrcode.css", PAGE_DIRECTORY.resolve("static/css/qrcode.css"));
        copyResource("/static/js/qrcode.js", PAGE_DIRECTORY.resolve("static/js/qrcode.js"));
    }

    /** 读取模板资源为 UTF-8 文本。 */
    private String readResource(String resource) throws IOException {
        try (InputStream input = LoginQrPage.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("找不到二维码页面资源: " + resource);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 将一个 classpath 资源复制至临时页面目录。 */
    private void copyResource(String resource, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try (InputStream input = LoginQrPage.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("找不到二维码页面资源: " + resource);
            }
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
