package com.example.ilink.capabilities.inbox.service;

import com.example.ilink.capabilities.inbox.model.ExtractedTask;
import com.example.ilink.capabilities.inbox.model.ExtractedTime;
import com.example.ilink.capabilities.inbox.model.ExtractionResult;
import com.example.ilink.capabilities.inbox.model.MessageSummary;
import com.example.ilink.capabilities.inbox.model.ProcessedMessage;
import com.example.ilink.capabilities.planning.DateTimeParser;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从摘要和原文中提取任务、时间与地点。 */
public final class ExtractorService {
    private static final Pattern LOCATION = Pattern.compile("在([^，。,.]{2,20}(?:室|楼|馆|中心|公司|学校|地点))");
    private long total;

    public ExtractionResult extract(MessageSummary summary, ProcessedMessage message) {
        total++;
        String content = message.cleanedContent();
        List<ExtractedTask> tasks = new ArrayList<>();
        List<ExtractedTime> times = new ArrayList<>();
        if (summary.messageType() == MessageSummary.MessageType.TASK) {
            LocalDateTime deadline = content.contains("截止") || content.contains("前提交")
                    ? DateTimeParser.parse(content) : null;
            for (String part : content.split("[，,；;]")) {
                if (part.matches(".*(完成(?!后)|提交|准备|安排|任务|报告|PPT).*")) {
                    tasks.add(new ExtractedTask(part.trim(), deadline));
                }
            }
            if (tasks.isEmpty()) tasks.add(new ExtractedTask(summary.summary(), deadline));
        }
        LocalDateTime resolved = summary.messageType() == MessageSummary.MessageType.CHAT
                ? null : DateTimeParser.parse(content);
        if (resolved != null) {
            times.add(new ExtractedTime(content, resolved,
                    content.contains("截止") || content.contains("前提交")));
        }
        List<String> locations = new ArrayList<>();
        Matcher matcher = LOCATION.matcher(content);
        if (matcher.find()) locations.add(matcher.group(1));
        return new ExtractionResult(tasks, times, List.of(), locations,
                summary.messageType(), summary.priority());
    }

    public ExtractorStats getStats() { return new ExtractorStats(total); }
    public record ExtractorStats(long total) { }
}
