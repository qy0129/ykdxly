package com.changlu.planner.export;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/** 生成计划统计 PDF，图表和统计接口使用同一份数据。 */
public final class StatsPdfGenerator {
  public record ReportRow(String first, String second, String third, String fourth, String fifth) {}

  public byte[] generate(JsonObject stats, List<ReportRow> plans, List<ReportRow> schedules, List<ReportRow> todos) throws IOException {
    try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      PDFont font = loadFont(document);
      addOverviewPage(document, font, stats);
      addHeatmapPage(document, font, stats);
      addDetailsPage(document, font, plans, schedules, todos);
      document.save(output);
      return output.toByteArray();
    }
  }

  private void addOverviewPage(PDDocument document, PDFont font, JsonObject stats) throws IOException {
    PDPage page = new PDPage(PDRectangle.A4);
    document.addPage(page);
    try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
      title(stream, font, "长路计划 · 计划统计", "生成日期 " + LocalDate.now());
      JsonObject metrics = stats.getAsJsonObject("metrics");
      metric(stream, font, 48, 700, "本月完成率", metrics.get("completion").getAsString() + "%");
      metric(stream, font, 180, 700, "完成任务", metrics.get("completed").getAsString() + " / " + metrics.get("planned").getAsString());
      metric(stream, font, 312, 700, "专注时间", metrics.get("focusHours").getAsString() + "h");
      metric(stream, font, 444, 700, "连续完成", metrics.get("streak").getAsString() + " 天");
      drawDailyBars(stream, font, stats.getAsJsonArray("daily"), 48, 450, 500, 190);
      drawMonthlyLine(stream, font, stats.getAsJsonArray("monthly"), 48, 175, 500, 190);
      footer(stream, font, page);
    }
  }

  private void addHeatmapPage(PDDocument document, PDFont font, JsonObject stats) throws IOException {
    PDPage page = new PDPage(PDRectangle.A4);
    document.addPage(page);
    try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
      title(stream, font, "执行热力图", "近四个月每日完成记录");
      JsonArray heatmap = stats.getAsJsonArray("heatmap");
      float left = 54, top = 690, cell = 20, gap = 4;
      for (int i = 0; i < heatmap.size(); i++) {
        JsonObject item = heatmap.get(i).getAsJsonObject();
        int column = i / 7;
        int row = i % 7;
        int value = item.get("value").getAsInt();
        float x = left + column * (cell + gap);
        float y = top - row * (cell + gap);
        stream.setNonStrokingColor(heatColor(value));
        stream.addRect(x, y, cell, cell);
        stream.fill();
      }
      stream.setNonStrokingColor(new Color(70, 55, 40));
      text(stream, font, 54, 520, 14, "颜色越深，代表当天完成的计划越多");
      text(stream, font, 54, 490, 10, "数据范围：" + (heatmap.size() == 0 ? "暂无" : heatmap.get(0).getAsJsonObject().get("id").getAsString()) + " 至 " + (heatmap.size() == 0 ? "暂无" : heatmap.get(heatmap.size() - 1).getAsJsonObject().get("id").getAsString()));
      footer(stream, font, page);
    }
  }

  private void addDetailsPage(PDDocument document, PDFont font, List<ReportRow> plans, List<ReportRow> schedules, List<ReportRow> todos) throws IOException {
    PDPage page = new PDPage(PDRectangle.A4);
    document.addPage(page);
    try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
      title(stream, font, "计划明细", "长期计划、日程和一次性待办");
      float y = 700;
      y = section(stream, font, "长期计划", plans, y);
      y = section(stream, font, "日程安排", schedules, y);
      section(stream, font, "待办事项", todos, y);
      footer(stream, font, page);
    }
  }

  private float section(PDPageContentStream stream, PDFont font, String heading, List<ReportRow> rows, float y) throws IOException {
    text(stream, font, 54, y, 14, heading + "（" + rows.size() + "）");
    y -= 24;
    if (rows.isEmpty()) {
      text(stream, font, 64, y, 10, "暂无记录");
      return y - 28;
    }
    for (ReportRow row : rows) {
      if (y < 70) break;
      String line = String.join("  ·  ", row.first(), row.second(), row.third(), row.fourth(), row.fifth());
      text(stream, font, 64, y, 9, truncate(line, 92));
      y -= 17;
    }
    return y - 16;
  }

  private void drawDailyBars(PDPageContentStream stream, PDFont font, JsonArray values, float x, float y, float width, float height) throws IOException {
    text(stream, font, x, y + height + 22, 13, "本月每日完成情况");
    float chartY = y + 20;
    float chartHeight = height - 20;
    axis(stream, x, chartY, width, chartHeight);
    float slot = width / Math.max(1, values.size());
    for (int i = 0; i < values.size(); i++) {
      JsonObject item = values.get(i).getAsJsonObject();
      float planned = item.get("planned").getAsFloat();
      float completed = item.get("completed").getAsFloat();
      float max = Math.max(1, maxDaily(values));
      float barWidth = Math.max(2, slot * .30f);
      float base = x + i * slot + slot * .18f;
      stream.setNonStrokingColor(new Color(218, 198, 161)); stream.addRect(base, chartY, barWidth, chartHeight * planned / max); stream.fill();
      stream.setNonStrokingColor(new Color(211, 154, 36)); stream.addRect(base + barWidth + 1, chartY, barWidth, chartHeight * completed / max); stream.fill();
      if (i % 5 == 0) text(stream, font, base, chartY - 13, 7, item.get("day").getAsString());
    }
  }

  private void drawMonthlyLine(PDPageContentStream stream, PDFont font, JsonArray values, float x, float y, float width, float height) throws IOException {
    text(stream, font, x, y + height + 22, 13, "近六个月完成率趋势");
    float chartY = y + 20, chartHeight = height - 20;
    axis(stream, x, chartY, width, chartHeight);
    float step = width / Math.max(1, values.size() - 1);
    float previousX = 0, previousY = 0;
    for (int i = 0; i < values.size(); i++) {
      JsonObject item = values.get(i).getAsJsonObject();
      float value = item.get("completion").getAsFloat();
      float px = x + i * step, py = chartY + chartHeight * value / 100f;
      if (i > 0) { stream.setStrokingColor(new Color(115, 128, 106)); stream.setLineWidth(2); stream.moveTo(previousX, previousY); stream.lineTo(px, py); stream.stroke(); }
      stream.setNonStrokingColor(new Color(115, 128, 106)); stream.addRect(px - 3, py - 3, 6, 6); stream.fill();
      text(stream, font, px - 10, chartY - 13, 7, item.get("month").getAsString());
      previousX = px; previousY = py;
    }
  }

  private void axis(PDPageContentStream stream, float x, float y, float width, float height) throws IOException {
    stream.setStrokingColor(new Color(210, 196, 174)); stream.setLineWidth(0.6f);
    stream.moveTo(x, y); stream.lineTo(x + width, y); stream.stroke();
    stream.moveTo(x, y); stream.lineTo(x, y + height); stream.stroke();
  }

  private int maxDaily(JsonArray values) {
    int max = 0;
    for (var value : values) { JsonObject item = value.getAsJsonObject(); max = Math.max(max, item.get("planned").getAsInt()); max = Math.max(max, item.get("completed").getAsInt()); }
    return max;
  }

  private Color heatColor(int value) { return switch (Math.min(5, Math.max(0, value))) { case 0 -> new Color(242, 234, 220); case 1 -> new Color(232, 205, 145); case 2 -> new Color(220, 178, 87); case 3 -> new Color(196, 139, 45); case 4 -> new Color(151, 102, 35); default -> new Color(106, 72, 30); }; }

  private void title(PDPageContentStream stream, PDFont font, String heading, String subtitle) throws IOException { text(stream, font, 48, 790, 20, heading); text(stream, font, 48, 768, 10, subtitle); }
  private void metric(PDPageContentStream stream, PDFont font, float x, float y, String label, String value) throws IOException { stream.setNonStrokingColor(new Color(249, 244, 235)); stream.addRect(x, y, 118, 48); stream.fill(); text(stream, font, x + 9, y + 30, 8, label); text(stream, font, x + 9, y + 12, 14, value); }
  private void footer(PDPageContentStream stream, PDFont font, PDPage page) throws IOException { text(stream, font, 48, 28, 8, "长路计划 · 统计报告"); }
  private void text(PDPageContentStream stream, PDFont font, float x, float y, float size, String value) throws IOException { stream.beginText(); stream.setNonStrokingColor(new Color(70, 55, 40)); stream.setFont(font, size); stream.newLineAtOffset(x, y); stream.showText(safe(value)); stream.endText(); }
  private String truncate(String value, int max) { return value.length() <= max ? value : value.substring(0, max - 1) + "…"; }
  private String safe(String value) { return value == null ? "" : value.replace("\u0000", "").replace("\r", " ").replace("\n", " "); }

  private PDFont loadFont(PDDocument document) throws IOException {
    String[] candidates = { "C:/Windows/Fonts/simhei.ttf", "C:/Windows/Fonts/Deng.ttf", "C:/Windows/Fonts/Noto Sans SC (TrueType).otf", "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc" };
    for (String candidate : candidates) { Path path = Path.of(candidate); if (Files.exists(path)) return PDType0Font.load(document, path.toFile()); }
    return new PDType1Font(Standard14Fonts.FontName.HELVETICA);
  }
}
