package com.example.ilink.capabilities.memory;

import java.net.http.HttpClient;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 只提取用户明确表达的稳定资料，避免把一次性任务和闲聊污染长期记忆。
 * 规则优先，避免每条消息额外触发一次模型请求。
 */
public final class MemoryExtractor {

    private static final Pattern SENSITIVE = Pattern.compile("密码|身份证|银行卡|信用卡|验证码|支付口令");
    private static final Pattern NAME = Pattern.compile("(?:我叫|我的名字是|名字叫)([^，。！？]{1,20})");
    private static final Pattern HOME = Pattern.compile("(?:我住在|我常住在|我的常住地是|我家在)([^，。！？]{2,40})");
    private static final Pattern PREFERENCE = Pattern.compile("(?:记住|我一直|我通常|我平时).*(?:喜欢|偏好|习惯|不吃|忌口|过敏)");
    private static final Pattern GOAL = Pattern.compile("(?:长期目标|今年的目标|我准备长期|我打算长期)([^，。！？]{2,80})");

    private final MemoryService memoryService;

    public MemoryExtractor(MemoryService memoryService, HttpClient ignoredHttpClient) {
        this.memoryService = memoryService;
    }

    public void extract(String userId, String message) {
        if (userId == null || userId.isBlank() || message == null || message.isBlank()
                || SENSITIVE.matcher(message).find()) return;
        String text = message.trim();
        MemoryCandidate candidate = candidate(userId, text);
        if (candidate != null) memoryService.saveExtractedMemory(candidate);
    }

    private MemoryCandidate candidate(String userId, String text) {
        Matcher name = NAME.matcher(text);
        if (name.find()) return new MemoryCandidate(userId, "profile", "user_name", name.group(1).trim(), 9, "rule");
        Matcher home = HOME.matcher(text);
        if (home.find()) return new MemoryCandidate(userId, "location", "home_location", home.group(1).trim(), 9, "rule");
        Matcher goal = GOAL.matcher(text);
        if (goal.find()) return new MemoryCandidate(userId, "goal", stableKey("goal", goal.group(1)), goal.group(1).trim(), 8, "rule");
        if (PREFERENCE.matcher(text).find()) {
            return new MemoryCandidate(userId, "preference", stableKey("preference", text), text, 7, "rule");
        }
        return null;
    }

    private static String stableKey(String type, String value) {
        return type + "_" + Integer.toUnsignedString(value.toLowerCase(Locale.ROOT).hashCode(), 16);
    }
}
