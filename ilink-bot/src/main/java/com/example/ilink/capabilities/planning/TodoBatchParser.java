package com.example.ilink.capabilities.planning;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 处理高频、低风险的复合待办快路径，避免把整句话存成一个标题。 */
public final class TodoBatchParser {
    private static final Pattern SEPARATOR = Pattern.compile("\\s*[，,；;。]\\s*|\\s*然后\\s*");
    private static final Pattern TIME_MARKER = Pattern.compile(
            "(今天|明天|后天|本周|下周|周[一二三四五六日天]|星期[一二三四五六日天]|\\d{1,2}月\\d{1,2}日|[上下]午|\\d{1,2}点|\\d{1,2}[:：]\\d{2})");
    private static final Pattern DATE_MARKER = Pattern.compile(
            "(今天|今日|明天|明日|后天|本周|这周|下周|下下周|周[一二三四五六日天]|星期[一二三四五六日天]|\\d{1,2}月\\d{1,2}(?:日|号)?)");
    private static final Pattern LINE_SEPARATOR = Pattern.compile("\\R+");
    private static final Pattern LIST_ITEM = Pattern.compile(
            "^\\s*(?:\\d{1,3}[.．、]|[-*•])\\s*(.+?)\\s*$");

    public List<TodoDraft> parse(String text) {
        if (text == null || text.isBlank()) return List.of();
        String normalized = text.trim();
        List<String> pieces = listItems(normalized);
        if (pieces.isEmpty()) {
            for (String line : LINE_SEPARATOR.split(normalized)) {
                for (String piece : SEPARATOR.split(line)) pieces.add(piece);
            }
        }
        List<TodoDraft> result = new ArrayList<>();
        String inheritedDate = "";
        for (String piece : pieces) {
            String source = piece.trim();
            if (source.isBlank()) continue;
            if (isInstructionOnly(source)) continue;
            var dateMatcher = DATE_MARKER.matcher(source);
            if (dateMatcher.find()) inheritedDate = dateMatcher.group(1);
            String title = cleanTitle(source);
            if (title.isBlank()) continue;
            String parseSource = DATE_MARKER.matcher(source).find() || inheritedDate.isBlank()
                    ? source : inheritedDate + " " + source;
            LocalDateTime dueAt = DateTimeParser.parse(parseSource);
            dueAt = DateTimeParser.applyPeriodDefault(source, dueAt);
            if (dueAt == null && isTodoContainerInstruction(source)) continue;
            result.add(new TodoDraft("todo_" + (result.size() + 1), source, title, dueAt));
        }
        return List.copyOf(result);
    }

    /** 从文档问答输出中提取编号或项目符号列表，供下一轮“创建这些待办”续接。 */
    public List<String> extractCandidateTitles(String text) {
        List<String> result = new ArrayList<>();
        for (String source : listItems(text)) {
            String title = cleanTitle(source).replaceFirst("[。；;]+$", "").trim();
            if (!title.isBlank() && !isInstructionOnly(title)) result.add(title);
        }
        return List.copyOf(result);
    }

    private List<String> listItems(String text) {
        List<String> result = new ArrayList<>();
        for (String line : LINE_SEPARATOR.split(text)) {
            var matcher = LIST_ITEM.matcher(line);
            if (matcher.matches()) result.add(matcher.group(1).trim());
        }
        return result;
    }

    public boolean looksLikeCompound(String text) {
        if (text == null || text.isBlank()) return false;
        boolean hasSeparator = SEPARATOR.matcher(text).find() || LINE_SEPARATOR.matcher(text).find();
        return hasSeparator
                && (text.contains("提醒") || text.contains("记得") || text.contains("别忘了")
                || text.contains("待办") || TIME_MARKER.matcher(text).find());
    }

    private String cleanTitle(String source) {
        String title = source
                .replaceFirst("^(请)?(帮我)?(设置|安排)(一下)?待办[：:，, ]*", "")
                .replaceFirst("^同时[：:，, ]*", "")
                .replaceAll("(上午|早上|中午|下午|晚上|傍晚|今晚)", "")
                .replaceAll("(?:[零一二三四五六七八九十两\\d]{1,3})\\s*点(?:\\s*[零一二三四五六七八九十两\\d]{1,2}\\s*分)?", "")
                .replaceFirst("^(请)?(帮我)?(添加|新增|创建|记)(一个|个)?待办[：:，, ]*", "")
                .replaceFirst("^待办[：:，, ]*", "")
                .replaceFirst("^(提醒我|请提醒我|别忘了|记得|记一下|帮我记住)[：:，, ]*", "")
                .replaceAll("(今天|今日|明天|明日|后天|\\d+天后|本周|这周|下周|下下周|周[一二三四五六日天]|星期[一二三四五六日天])", "")
                .replaceAll("(?:(?:\\d{4})年)?\\d{1,2}月\\d{1,2}(?:日|号)?", "")
                .replaceAll("(上午|中午|下午|傍晚|晚上|今晚)?[零一二三四五六七八九十两\\d]{1,3}点(半)?", "")
                .replaceAll("\\d{1,2}[：:]\\d{2}", "")
                .replaceAll("^(提醒我|请提醒我|提醒|安排|记得|别忘了)[：:，, ]*", "")
                .replaceFirst("[。；;\\s]*(?:创建|新建|新增)(?:这些|上述|以上|上面(?:的)?)(?:待办事项|待办|任务)[。！!\\s]*$", "")
                .replaceAll(" +", " ")
                .trim();
        return title;
    }

    private boolean isTodoContainerInstruction(String source) {
        return source.matches("^(请)?(帮我)?(新建|设置|安排)(以下)?(一下)?待办(事项)?[：:，, ]*$")
                || source.matches("^(请)?(帮我)?(添加|新增|创建|记(一个|个)?)待办[：:，, ]*$")
                || source.matches("^待办[：:，, ]*$");
    }

    private boolean isInstructionOnly(String source) {
        return isTodoContainerInstruction(source)
                || source.matches("^每条任务.*(提醒|推送).*$")
                || source.matches("^(后续|之后|以后).*(监督|复盘|检查|跟进).*$")
                || source.matches("^(每天|每日|每晚|每周|每星期|每礼拜).*(复盘|"
                        + "检查.*(?:完成情况|完成进度)|跟进.*(?:完成情况|完成进度)).*$");
    }
}
