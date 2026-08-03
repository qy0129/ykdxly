package com.changlu.planner.wechat;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.awt.Desktop;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

/** 将 SDK 返回的二维码转换为可在浏览器中扫码的本地页面。 */
public final class QrLoginPage {
  private final Path file = Path.of(System.getProperty("java.io.tmpdir"), "changlu-planner-login.html");

  public Path render(String code) throws Exception {
    String dataUri = toDataUri(code);
    String html = """
        <!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>长路计划 · 微信登录</title>
        <style>body{margin:0;min-height:100vh;display:grid;place-items:center;background:#f3eadb;color:#4b3725;font-family:system-ui,'Microsoft YaHei',sans-serif}.panel{width:min(420px,calc(100vw - 40px));padding:36px 28px;text-align:center;background:#fffaf2;border:1px solid #ddcdb5;box-shadow:0 18px 50px #8d6b3d22}.qr{width:300px;max-width:100%;border:10px solid #fff;margin:22px auto 14px}.muted{color:#92795d;font-size:14px;line-height:1.7}h1{margin:0;font-size:26px}</style></head>
        <body><main class="panel"><h1>长路计划</h1><p class="muted">请使用微信扫描二维码登录 Bot</p><img class="qr" src="%s" alt="微信登录二维码"><p class="muted">扫码成功后本页面可以关闭，Bot 会在微信中发送计划工作台链接。</p></main></body></html>
        """.replace("%s", dataUri);
    Files.writeString(file, html, StandardCharsets.UTF_8);
    return file;
  }

  public void open(Path page) {
    if (!Boolean.parseBoolean(System.getenv().getOrDefault("PLANNER_OPEN_QR_PAGE", "true"))) return;
    try {
      if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) Desktop.getDesktop().browse(page.toUri());
    } catch (Exception error) { System.err.println("[微信登录] 无法自动打开二维码页面: " + error.getMessage()); }
  }

  private String toDataUri(String code) throws Exception {
    if (code == null || code.isBlank()) throw new IllegalArgumentException("登录二维码为空");
    if (code.startsWith("data:image/")) return code;
    if (code.startsWith("http://") || code.startsWith("https://")) {
      BitMatrix matrix = new QRCodeWriter().encode(code, BarcodeFormat.QR_CODE, 300, 300, Map.of(EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name()));
      try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
        MatrixToImageWriter.writeToStream(matrix, "PNG", output);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
      }
    }
    return "data:image/png;base64," + Base64.getEncoder().encodeToString(Base64.getDecoder().decode(code));
  }
}
