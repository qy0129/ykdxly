package com.example.ilink.feature.visual;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** 使用本地 Java2D 稳定绘制中文 PNG 卡片。 */
public final class VisualCardRenderer {

    public static final int WIDTH = 1080;
    public static final int HEIGHT = 1440;
    private static final int MARGIN = 88;
    private static final Color INK = new Color(34, 39, 43);
    private static final Color MUTED = new Color(101, 110, 116);
    private final QrCodeService qrCodeService;

    public VisualCardRenderer(QrCodeService qrCodeService) {
        this.qrCodeService = qrCodeService;
    }

    public byte[] render(VisualCard card, int page, int total) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            configure(graphics);
            paintBackground(graphics, card.accent());
            int y = 115;
            graphics.setColor(card.accent());
            graphics.fillRoundRect(MARGIN, y, 92, 12, 6, 6);
            y += 82;

            Font titleFont = font(Font.BOLD, 66);
            graphics.setFont(titleFont);
            graphics.setColor(INK);
            y = drawLines(graphics, wrap(graphics, card.title(), WIDTH - MARGIN * 2), MARGIN, y, 82, 2);

            if (!card.subtitle().isBlank()) {
                y += 22;
                graphics.setFont(font(Font.PLAIN, 30));
                graphics.setColor(MUTED);
                y = drawLines(graphics, wrap(graphics, card.subtitle(), WIDTH - MARGIN * 2), MARGIN, y, 43, 2);
            }

            y += 54;
            graphics.setColor(new Color(224, 228, 226));
            graphics.fillRect(MARGIN, y, WIDTH - MARGIN * 2, 2);
            y += 58;

            int bodyBottom = card.qrUrl().isBlank() ? 1240 : 1090;
            graphics.setFont(font(Font.PLAIN, 34));
            graphics.setColor(INK);
            List<String> bodyLines = wrapParagraphs(graphics, card.body(), WIDTH - MARGIN * 2);
            int maxLines = Math.max(1, (bodyBottom - y) / 51);
            y = drawLines(graphics, bodyLines, MARGIN, y, 51, maxLines);

            if (!card.qrUrl().isBlank()) paintQr(graphics, card);
            paintFooter(graphics, card, page, total);
        } finally {
            graphics.dispose();
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("卡片图片编码失败", e);
        }
    }

    private void configure(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    private void paintBackground(Graphics2D graphics, Color accent) {
        graphics.setColor(new Color(247, 248, 245));
        graphics.fillRect(0, 0, WIDTH, HEIGHT);
        graphics.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 22));
        graphics.fill(new RoundRectangle2D.Double(48, 48, WIDTH - 96, HEIGHT - 96, 36, 36));
        graphics.setColor(new Color(255, 255, 255, 235));
        graphics.fill(new RoundRectangle2D.Double(58, 58, WIDTH - 116, HEIGHT - 116, 30, 30));
        graphics.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 110));
        graphics.setStroke(new BasicStroke(2));
        graphics.draw(new RoundRectangle2D.Double(58, 58, WIDTH - 116, HEIGHT - 116, 30, 30));
    }

    private void paintQr(Graphics2D graphics, VisualCard card) {
        BufferedImage qr = qrCodeService.create(card.qrUrl(), 230);
        if (qr == null) return;
        int x = WIDTH - MARGIN - 230;
        int y = 1080;
        graphics.drawImage(qr, x, y, null);
        graphics.setFont(font(Font.PLAIN, 25));
        graphics.setColor(MUTED);
        String label = card.qrLabel().isBlank() ? "微信扫码打开" : card.qrLabel();
        int labelWidth = graphics.getFontMetrics().stringWidth(label);
        graphics.drawString(label, x + (230 - labelWidth) / 2, y + 266);
    }

    private void paintFooter(Graphics2D graphics, VisualCard card, int page, int total) {
        graphics.setFont(font(Font.PLAIN, 24));
        graphics.setColor(MUTED);
        String footer = card.footer().isBlank() ? "ILINK BOT" : card.footer();
        graphics.drawString(footer, MARGIN, 1335);
        String pageText = Math.max(1, page) + " / " + Math.max(1, total);
        int width = graphics.getFontMetrics().stringWidth(pageText);
        graphics.drawString(pageText, WIDTH - MARGIN - width, 1335);
    }

    private int drawLines(Graphics2D graphics, List<String> lines, int x, int y,
                          int lineHeight, int maxLines) {
        int count = Math.min(lines.size(), maxLines);
        for (int index = 0; index < count; index++) {
            String line = lines.get(index);
            if (index == count - 1 && lines.size() > maxLines) line = ellipsis(graphics, line);
            graphics.drawString(line, x, y);
            y += lineHeight;
        }
        return y;
    }

    private List<String> wrapParagraphs(Graphics2D graphics, String text, int width) {
        List<String> result = new ArrayList<>();
        String[] paragraphs = (text == null ? "" : text).split("\\R", -1);
        for (String paragraph : paragraphs) {
            if (paragraph.isBlank()) {
                result.add(" ");
            } else {
                result.addAll(wrap(graphics, paragraph, width));
            }
        }
        return result.isEmpty() ? List.of("暂无内容") : result;
    }

    private List<String> wrap(Graphics2D graphics, String text, int width) {
        List<String> lines = new ArrayList<>();
        FontMetrics metrics = graphics.getFontMetrics();
        StringBuilder line = new StringBuilder();
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            String character = new String(Character.toChars(codePoint));
            if (!line.isEmpty() && metrics.stringWidth(line + character) > width) {
                lines.add(line.toString());
                line.setLength(0);
            }
            line.append(character);
            offset += Character.charCount(codePoint);
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines.isEmpty() ? List.of("") : lines;
    }

    private String ellipsis(Graphics2D graphics, String value) {
        String result = value;
        int maxWidth = WIDTH - MARGIN * 2;
        while (!result.isEmpty() && graphics.getFontMetrics().stringWidth(result + "...") > maxWidth) {
            result = result.substring(0, result.length() - 1);
        }
        return result + "...";
    }

    private Font font(int style, int size) {
        String[] names = {"Microsoft YaHei", "Microsoft JhengHei", "Noto Sans CJK SC", "SansSerif"};
        for (String name : names) {
            Font candidate = new Font(name, style, size);
            if (candidate.canDisplay('今')) return candidate;
        }
        return new Font(Font.SANS_SERIF, style, size);
    }
}
