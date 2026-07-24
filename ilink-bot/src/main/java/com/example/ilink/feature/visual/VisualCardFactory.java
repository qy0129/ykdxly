package com.example.ilink.feature.visual;

import java.awt.Color;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** 把业务文本整理成适合手机阅读的卡片组。 */
public final class VisualCardFactory {

    private static final Color GREEN = new Color(42, 117, 91);
    private static final Color BLUE = new Color(48, 103, 166);
    private static final Color CORAL = new Color(190, 88, 74);
    private static final Color GOLD = new Color(166, 119, 42);

    public List<VisualCard> loginDeck(String briefing, String dashboardUrl) {
        List<String> paragraphs = paragraphs(briefing);
        List<String> greeting = new ArrayList<>();
        List<String> weather = new ArrayList<>();
        List<String> schedule = new ArrayList<>();
        for (String paragraph : paragraphs) {
            if (paragraph.matches("(?s).*(天气|温度|气温|降水|出行|穿搭|风速|湿度).*")) {
                weather.add(paragraph);
            } else if (paragraph.matches("(?s).*(日历|安排|待办|计划|提醒|邮件|任务).*")) {
                schedule.add(paragraph);
            } else {
                greeting.add(paragraph);
            }
        }
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("M月d日 EEEE"));
        return List.of(
                card("欢迎回来", date, joinOrDefault(greeting, "今天也一起把生活安排得从容一点。"), GREEN),
                card("天气与出行", "出门前看一眼", joinOrDefault(weather, "暂时没有取得天气信息，出门前请再留意实时天气。"), BLUE),
                card("今天的安排", "日历、待办与计划", joinOrDefault(schedule, "今天没有必须赶着完成的安排，可以按自己的节奏来。"), CORAL),
                new VisualCard("未来七天", "完整计划与进度", dashboardUrl.isBlank()
                        ? "七日计划页面尚未配置公开地址，当前仍可在聊天里查看安排。"
                        : "扫码打开动态七日计划页，查看天气、日历、待办和学习计划。",
                        "每日登录简报", dashboardUrl, "扫码查看七日计划", GOLD));
    }

    public List<VisualCard> textDeck(String title, String subtitle, String text) {
        return textDeck(title, subtitle, text, "", "");
    }

    public List<VisualCard> textDeck(String title, String subtitle, String text,
                                     String qrUrl, String qrLabel) {
        List<String> pages = splitText(text, 650);
        List<VisualCard> cards = new ArrayList<>();
        Color[] accents = {GREEN, BLUE, CORAL, GOLD};
        for (int index = 0; index < pages.size(); index++) {
            String pageTitle = pages.size() == 1 ? title : title + " " + (index + 1);
            VisualCard card = card(pageTitle, subtitle, pages.get(index), accents[index % accents.length]);
            if (index == pages.size() - 1 && qrUrl != null && !qrUrl.isBlank()) {
                card = card.withQr(qrUrl, qrLabel);
            }
            cards.add(card);
        }
        return cards;
    }

    public VisualCard linkCard(String title, String subtitle, String body, String url, Color accent) {
        return new VisualCard(title, subtitle, body, "扫码后在浏览器中打开", url,
                "微信扫码打开", accent);
    }

    private VisualCard card(String title, String subtitle, String body, Color accent) {
        return VisualCard.of(title, subtitle, body).withAccent(accent).withFooter("ILINK BOT");
    }

    private List<String> paragraphs(String text) {
        if (text == null || text.isBlank()) return List.of();
        return List.of(text.trim().split("(?:\\R\\s*){2,}"));
    }

    private String joinOrDefault(List<String> values, String defaultValue) {
        return values.isEmpty() ? defaultValue : String.join("\n\n", values);
    }

    private List<String> splitText(String text, int maxChars) {
        String value = text == null || text.isBlank() ? "暂无内容" : text.trim();
        List<String> pages = new ArrayList<>();
        StringBuilder page = new StringBuilder();
        for (String line : value.split("\\R")) {
            if (!page.isEmpty() && page.length() + line.length() + 1 > maxChars) {
                pages.add(page.toString().trim());
                page.setLength(0);
            }
            if (line.length() > maxChars) {
                for (int start = 0; start < line.length(); start += maxChars) {
                    if (!page.isEmpty()) {
                        pages.add(page.toString().trim());
                        page.setLength(0);
                    }
                    pages.add(line.substring(start, Math.min(line.length(), start + maxChars)));
                }
            } else {
                if (!page.isEmpty()) page.append('\n');
                page.append(line);
            }
        }
        if (!page.isEmpty()) pages.add(page.toString().trim());
        return pages.isEmpty() ? List.of("暂无内容") : pages;
    }
}
