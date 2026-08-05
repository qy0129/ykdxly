package com.changlu.planner.features.export;

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
import java.util.ArrayList;
import java.util.List;

/** 生成计划统计 PDF：先给结论，再给趋势、热力图和可分页的明细表。 */
public final class StatsPdfGenerator {
  private static final float PAGE_WIDTH = PDRectangle.A4.getWidth();
  private static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
  private static final float LEFT = 48;
  private static final float RIGHT = 48;
  private static final float CONTENT_WIDTH = PAGE_WIDTH - LEFT - RIGHT;

  private static final Color INK = new Color(47, 41, 35);
  private static final Color MUTED = new Color(125, 108, 92);
  private static final Color BROWN = new Color(104, 75, 52);
  private static final Color GOLD = new Color(211, 154, 36);
  private static final Color SAGE = new Color(115, 128, 106);
  private static final Color BRICK = new Color(184, 95, 66);
  private static final Color LINE = new Color(230, 218, 199);
  private static final Color PALE = new Color(249, 244, 235);
  private static final Color WHITE = Color.WHITE;

  public record ReportRow(String first, String second, String third, String fourth, String fifth) {}

  private record ReportSection(String title, String[] headers, float[] widths, List<ReportRow> rows) {}

  private static final class DetailsPage {
    private final PDPage page;
    private final PDPageContentStream stream;
    private final int number;
    private float y;

    private DetailsPage(PDPage page, PDPageContentStream stream, int number, float y) {
      this.page = page;
      this.stream = stream;
      this.number = number;
      this.y = y;
    }
  }

  public byte[] generate(JsonObject stats, List<ReportRow> plans, List<ReportRow> schedules,
                         List<ReportRow> todos) throws IOException {
    try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      PDFont font = loadFont(document);
      addOverviewPage(document, font, stats);
      addHeatmapPage(document, font, stats);
      addDetailsPages(document, font, plans, schedules, todos);
      document.save(output);
      return output.toByteArray();
    }
  }

  private void addOverviewPage(PDDocument document, PDFont font, JsonObject stats) throws IOException {
    PDPage page = new PDPage(PDRectangle.A4);
    document.addPage(page);
    try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
      header(stream, font, "长路计划", "计划统计报告", "生成日期 " + LocalDate.now());
      JsonObject metrics = stats.getAsJsonObject("metrics");
      float cardY = 650;
      float gap = 10;
      float cardWidth = (CONTENT_WIDTH - gap * 3) / 4;
      metricCard(stream, font, LEFT, cardY, cardWidth, "本月完成率", value(metrics, "completion", "0") + "%", GOLD);
      metricCard(stream, font, LEFT + (cardWidth + gap), cardY, cardWidth, "完成任务", value(metrics, "completed", "0") + " / " + value(metrics, "planned", "0"), BROWN);
      metricCard(stream, font, LEFT + (cardWidth + gap) * 2, cardY, cardWidth, "专注时间", value(metrics, "focusHours", "0") + " 小时", SAGE);
      metricCard(stream, font, LEFT + (cardWidth + gap) * 3, cardY, cardWidth, "连续完成", value(metrics, "streak", "0") + " 天", BRICK);

      panel(stream, LEFT, 395, CONTENT_WIDTH, 220);
      text(stream, font, LEFT + 16, 588, 13, "每日完成情况", INK);
      legend(stream, font, LEFT + CONTENT_WIDTH - 112, 588, new String[]{"计划", "完成"}, new Color[]{new Color(218, 198, 161), GOLD});
      drawDailyBars(stream, font, stats.getAsJsonArray("daily"), LEFT + 24, 430, CONTENT_WIDTH - 48, 130);

      panel(stream, LEFT, 125, CONTENT_WIDTH, 220);
      text(stream, font, LEFT + 16, 318, 13, "近六个月完成率趋势", INK);
      drawMonthlyLine(stream, font, stats.getAsJsonArray("monthly"), LEFT + 24, 160, CONTENT_WIDTH - 48, 130);
      footer(stream, font, 1);
    }
  }

  private void addHeatmapPage(PDDocument document, PDFont font, JsonObject stats) throws IOException {
    PDPage page = new PDPage(PDRectangle.A4);
    document.addPage(page);
    try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
      header(stream, font, "执行分析", "完成热力图", "近四个月每日完成记录");
      JsonArray heatmap = stats.getAsJsonArray("heatmap");
      JsonObject metrics = stats.getAsJsonObject("metrics");
      int activeDays = countHeatmapDays(heatmap);
      int completed = sumHeatmap(heatmap);
      infoStrip(stream, font, 48, 665, CONTENT_WIDTH, "完成总数", completed + " 项", "活跃天数", activeDays + " 天", "连续完成", value(metrics, "streak", "0") + " 天");

      panel(stream, LEFT, 230, CONTENT_WIDTH, 390);
      text(stream, font, LEFT + 16, 588, 13, "每日完成密度", INK);
      text(stream, font, LEFT + 16, 570, 9, "颜色越深，代表当天完成的计划越多", MUTED);
      drawHeatmap(stream, font, heatmap, LEFT + 26, 500, CONTENT_WIDTH - 52);
      heatLegend(stream, font, LEFT + 26, 320);
      String range = heatmap.size() == 0 ? "暂无数据" : safe(heatmap.get(0).getAsJsonObject().get("id").getAsString())
          + " 至 " + safe(heatmap.get(heatmap.size() - 1).getAsJsonObject().get("id").getAsString());
      text(stream, font, LEFT + 26, 300, 9, "数据范围  " + range, MUTED);
      footer(stream, font, 2);
    }
  }

  private void addDetailsPages(PDDocument document, PDFont font, List<ReportRow> plans,
                               List<ReportRow> schedules, List<ReportRow> todos) throws IOException {
    List<ReportSection> sections = List.of(
        new ReportSection("长期计划", new String[]{"计划", "说明", "进度", "状态", "截止日期"}, new float[]{112, 120, 80, 64, 123}, plans),
        new ReportSection("日程安排", new String[]{"日期", "时间", "日程", "时长", "状态"}, new float[]{80, 60, 190, 64, 105}, schedules),
        new ReportSection("待办事项", new String[]{"日期", "时间", "待办", "优先级", "状态"}, new float[]{80, 60, 190, 64, 105}, todos));

    DetailsPage current = newDetailsPage(document, font, 3);
    for (ReportSection section : sections) {
      if (current.y < 150) {
        closeDetailsPage(current, font);
        current = newDetailsPage(document, font, current.number + 1);
      }
      text(current.stream, font, LEFT, current.y, 15, section.title() + "  " + section.rows().size() + " 项", INK);
      current.y -= 18;
      drawTableHeader(current.stream, font, current.y, section.headers(), section.widths());
      current.y -= 26;
      if (section.rows().isEmpty()) {
        text(current.stream, font, LEFT + 8, current.y, 10, "暂无记录", MUTED);
        current.y -= 30;
        continue;
      }
      for (ReportRow row : section.rows()) {
        if (current.y < 76) {
          closeDetailsPage(current, font);
          current = newDetailsPage(document, font, current.number + 1);
          text(current.stream, font, LEFT, current.y, 11, section.title() + "（续）", MUTED);
          current.y -= 18;
          drawTableHeader(current.stream, font, current.y, section.headers(), section.widths());
          current.y -= 26;
        }
        drawTableRow(current.stream, font, current.y, row, section.widths());
        current.y -= 34;
      }
      current.y -= 20;
    }
    closeDetailsPage(current, font);
  }

  private DetailsPage newDetailsPage(PDDocument document, PDFont font, int number) throws IOException {
    PDPage page = new PDPage(PDRectangle.A4);
    document.addPage(page);
    PDPageContentStream stream = new PDPageContentStream(document, page);
    header(stream, font, "执行明细", "计划与执行记录", number == 3 ? "长期计划、日程和一次性待办" : "明细续页");
    return new DetailsPage(page, stream, number, 690);
  }

  private void closeDetailsPage(DetailsPage page, PDFont font) throws IOException {
    footer(page.stream, font, page.number);
    page.stream.close();
  }

  private void drawTableHeader(PDPageContentStream stream, PDFont font, float y, String[] headers, float[] widths) throws IOException {
    stream.setNonStrokingColor(BROWN);
    stream.addRect(LEFT, y - 5, CONTENT_WIDTH, 24);
    stream.fill();
    float x = LEFT + 8;
    for (int i = 0; i < headers.length; i++) {
      text(stream, font, x, y + 3, 8.5f, headers[i], WHITE);
      x += widths[i];
    }
  }

  private void drawTableRow(PDPageContentStream stream, PDFont font, float y, ReportRow row, float[] widths) throws IOException {
    String[] values = {formatCell(row.first()), formatCell(row.second()), formatCell(row.third()), formatCell(row.fourth()), formatCell(row.fifth())};
    stream.setNonStrokingColor((int) (y / 34) % 2 == 0 ? PALE : WHITE);
    stream.addRect(LEFT, y - 10, CONTENT_WIDTH, 34);
    stream.fill();
    stream.setStrokingColor(LINE);
    stream.setLineWidth(.45f);
    stream.moveTo(LEFT, y - 10);
    stream.lineTo(LEFT + CONTENT_WIDTH, y - 10);
    stream.stroke();
    float x = LEFT + 8;
    for (int i = 0; i < values.length; i++) {
      List<String> lines = wrap(values[i], font, 8.5f, widths[i] - 14);
      text(stream, font, x, y + 4, 8.5f, lines.get(0), INK);
      if (lines.size() > 1) text(stream, font, x, y - 7, 7.5f, lines.get(1), MUTED);
      x += widths[i];
    }
  }

  private void drawDailyBars(PDPageContentStream stream, PDFont font, JsonArray values,
                             float x, float y, float width, float height) throws IOException {
    float max = Math.max(1, maxDaily(values));
    axisGrid(stream, font, x, y, width, height, max);
    float slot = width / Math.max(1, values.size());
    for (int i = 0; i < values.size(); i++) {
      JsonObject item = values.get(i).getAsJsonObject();
      float planned = item.get("planned").getAsFloat();
      float completed = item.get("completed").getAsFloat();
      float barWidth = Math.max(2.2f, slot * .25f);
      float base = x + i * slot + slot * .22f;
      stream.setNonStrokingColor(new Color(218, 198, 161));
      stream.addRect(base, y, barWidth, height * planned / max);
      stream.fill();
      stream.setNonStrokingColor(GOLD);
      stream.addRect(base + barWidth + 1.5f, y, barWidth, height * completed / max);
      stream.fill();
      if (i % 5 == 0) text(stream, font, base, y - 14, 7, safe(item.get("day").getAsString()), MUTED);
    }
  }

  private void drawMonthlyLine(PDPageContentStream stream, PDFont font, JsonArray values,
                               float x, float y, float width, float height) throws IOException {
    axisGrid(stream, font, x, y, width, height, 100);
    float step = width / Math.max(1, values.size() - 1);
    float previousX = 0, previousY = 0;
    for (int i = 0; i < values.size(); i++) {
      JsonObject item = values.get(i).getAsJsonObject();
      float value = item.get("completion").getAsFloat();
      float px = x + i * step;
      float py = y + height * Math.max(0, Math.min(100, value)) / 100f;
      if (i > 0) {
        stream.setStrokingColor(SAGE);
        stream.setLineWidth(2);
        stream.moveTo(previousX, previousY);
        stream.lineTo(px, py);
        stream.stroke();
      }
      stream.setNonStrokingColor(SAGE);
      stream.addRect(px - 3, py - 3, 6, 6);
      stream.fill();
      text(stream, font, px - 12, y - 14, 7, safe(item.get("month").getAsString()), MUTED);
      previousX = px;
      previousY = py;
    }
  }

  private void drawHeatmap(PDPageContentStream stream, PDFont font, JsonArray heatmap,
                           float x, float y, float width) throws IOException {
    int columns = Math.max(1, (heatmap.size() + 6) / 7);
    float gap = 4;
    float cell = Math.min(22, (width - gap * (columns - 1)) / columns);
    String[] weekdays = {"一", "二", "三", "四", "五", "六", "日"};
    for (int row = 0; row < 7; row++) text(stream, font, x - 18, y - row * (cell + gap) + 4, 7, weekdays[row], MUTED);
    for (int i = 0; i < heatmap.size(); i++) {
      JsonObject item = heatmap.get(i).getAsJsonObject();
      int column = i / 7;
      int row = i % 7;
      float cellX = x + column * (cell + gap);
      float cellY = y - row * (cell + gap);
      stream.setNonStrokingColor(heatColor(item.get("value").getAsInt()));
      stream.addRect(cellX, cellY, cell, cell);
      stream.fill();
    }
  }

  private void heatLegend(PDPageContentStream stream, PDFont font, float x, float y) throws IOException {
    text(stream, font, x, y, 8, "少", MUTED);
    for (int i = 0; i <= 5; i++) {
      stream.setNonStrokingColor(heatColor(i));
      stream.addRect(x + 22 + i * 20, y - 3, 14, 14);
      stream.fill();
    }
    text(stream, font, x + 150, y, 8, "多", MUTED);
  }

  private void axisGrid(PDPageContentStream stream, PDFont font, float x, float y, float width, float height, float max) throws IOException {
    stream.setStrokingColor(LINE);
    stream.setLineWidth(.5f);
    for (int i = 0; i <= 4; i++) {
      float lineY = y + height * i / 4f;
      stream.moveTo(x, lineY);
      stream.lineTo(x + width, lineY);
      stream.stroke();
      if (i < 4) text(stream, font, x - 24, lineY - 3, 7, Math.round(max * i / 4f) + "", MUTED);
    }
  }

  private void header(PDPageContentStream stream, PDFont font, String kicker, String heading, String subtitle) throws IOException {
    stream.setNonStrokingColor(BROWN);
    stream.addRect(0, PAGE_HEIGHT - 96, PAGE_WIDTH, 96);
    stream.fill();
    text(stream, font, LEFT, PAGE_HEIGHT - 38, 9, kicker, new Color(244, 222, 173));
    text(stream, font, LEFT, PAGE_HEIGHT - 66, 22, heading, WHITE);
    text(stream, font, PAGE_WIDTH - RIGHT - 150, PAGE_HEIGHT - 38, 8, subtitle, new Color(244, 232, 211));
  }

  private void panel(PDPageContentStream stream, float x, float y, float width, float height) throws IOException {
    stream.setNonStrokingColor(WHITE);
    stream.addRect(x, y, width, height);
    stream.fill();
    stream.setStrokingColor(LINE);
    stream.setLineWidth(.7f);
    stream.addRect(x, y, width, height);
    stream.stroke();
  }

  private void metricCard(PDPageContentStream stream, PDFont font, float x, float y, float width,
                          String label, String value, Color accent) throws IOException {
    stream.setNonStrokingColor(PALE);
    stream.addRect(x, y, width, 70);
    stream.fill();
    stream.setNonStrokingColor(accent);
    stream.addRect(x, y, 4, 70);
    stream.fill();
    text(stream, font, x + 12, y + 48, 8, label, MUTED);
    text(stream, font, x + 12, y + 20, 17, value, INK);
  }

  private void infoStrip(PDPageContentStream stream, PDFont font, float x, float y, float width,
                         String label1, String value1, String label2, String value2,
                         String label3, String value3) throws IOException {
    panel(stream, x, y, width, 48);
    float slot = width / 3;
    text(stream, font, x + 14, y + 29, 8, label1, MUTED);
    text(stream, font, x + 14, y + 12, 12, value1, INK);
    text(stream, font, x + slot + 14, y + 29, 8, label2, MUTED);
    text(stream, font, x + slot + 14, y + 12, 12, value2, INK);
    text(stream, font, x + slot * 2 + 14, y + 29, 8, label3, MUTED);
    text(stream, font, x + slot * 2 + 14, y + 12, 12, value3, INK);
  }

  private void legend(PDPageContentStream stream, PDFont font, float x, float y, String[] labels, Color[] colors) throws IOException {
    for (int i = 0; i < labels.length; i++) {
      stream.setNonStrokingColor(colors[i]);
      stream.addRect(x + i * 56, y, 8, 8);
      stream.fill();
      text(stream, font, x + 12 + i * 56, y + 1, 7, labels[i], MUTED);
    }
  }

  private void footer(PDPageContentStream stream, PDFont font, int pageNumber) throws IOException {
    stream.setStrokingColor(LINE);
    stream.setLineWidth(.5f);
    stream.moveTo(LEFT, 38);
    stream.lineTo(PAGE_WIDTH - RIGHT, 38);
    stream.stroke();
    text(stream, font, LEFT, 23, 7.5f, "长路计划  /  统计报告", MUTED);
    text(stream, font, PAGE_WIDTH - RIGHT - 40, 23, 7.5f, "第 " + pageNumber + " 页", MUTED);
  }

  private int countHeatmapDays(JsonArray values) {
    int count = 0;
    for (var value : values) if (value.getAsJsonObject().get("value").getAsInt() > 0) count++;
    return count;
  }

  private int sumHeatmap(JsonArray values) {
    int sum = 0;
    for (var value : values) sum += value.getAsJsonObject().get("value").getAsInt();
    return sum;
  }

  private int maxDaily(JsonArray values) {
    int max = 0;
    for (var value : values) {
      JsonObject item = value.getAsJsonObject();
      max = Math.max(max, item.get("planned").getAsInt());
      max = Math.max(max, item.get("completed").getAsInt());
    }
    return max;
  }

  private Color heatColor(int value) {
    return switch (Math.min(5, Math.max(0, value))) {
      case 0 -> new Color(242, 234, 220);
      case 1 -> new Color(232, 205, 145);
      case 2 -> new Color(220, 178, 87);
      case 3 -> new Color(196, 139, 45);
      case 4 -> new Color(151, 102, 35);
      default -> new Color(106, 72, 30);
    };
  }

  private String value(JsonObject object, String key, String fallback) {
    return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback;
  }

  private List<String> wrap(String value, PDFont font, float size, float maxWidth) throws IOException {
    String normalized = safe(value);
    if (normalized.isBlank()) return List.of("");
    List<String> lines = new ArrayList<>();
    StringBuilder line = new StringBuilder();
    for (int i = 0; i < normalized.length(); i++) {
      line.append(normalized.charAt(i));
      if (font != null && font.getStringWidth(line.toString()) / 1000f * size > maxWidth) {
        line.setLength(line.length() - 1);
        if (!line.isEmpty()) lines.add(line.toString());
        line = new StringBuilder().append(normalized.charAt(i));
        if (lines.size() == 2) break;
      }
    }
    if (lines.size() < 2 && !line.isEmpty()) lines.add(line.toString());
    if (lines.size() > 2) lines = lines.subList(0, 2);
    return lines;
  }

  private void text(PDPageContentStream stream, PDFont font, float x, float y, float size,
                    String value, Color color) throws IOException {
    if (font == null) return;
    stream.beginText();
    stream.setNonStrokingColor(color);
    stream.setFont(font, size);
    stream.newLineAtOffset(x, y);
    stream.showText(safe(value));
    stream.endText();
  }

  private String safe(String value) {
    return value == null ? "" : value.replace("\u0000", "").replace("\r", " ").replace("\n", " ");
  }

  private String formatCell(String value) {
    String normalized = safe(value);
    return switch (normalized) {
      case "", "null" -> "未设置";
      case "active" -> "进行中";
      case "paused" -> "已暂停";
      case "archived" -> "已归档";
      case "pending" -> "待完成";
      case "done", "completed" -> "已完成";
      case "cancelled" -> "已取消";
      case "high" -> "高";
      case "medium" -> "中";
      case "low" -> "低";
      default -> normalized;
    };
  }

  private PDFont loadFont(PDDocument document) throws IOException {
    String configured = System.getenv("PLANNER_PDF_FONT");
    List<String> candidates = new ArrayList<>();
    if (configured != null && !configured.isBlank()) candidates.add(configured);
    candidates.add("C:/Windows/Fonts/simhei.ttf");
    candidates.add("C:/Windows/Fonts/Deng.ttf");
    candidates.add("C:/Windows/Fonts/msyh.ttc");
    candidates.add("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc");
    for (String candidate : candidates) {
      Path path = Path.of(candidate);
      if (Files.isRegularFile(path)) return PDType0Font.load(document, path.toFile());
    }
    return new PDType1Font(Standard14Fonts.FontName.HELVETICA);
  }
}
