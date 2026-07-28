package com.example.ilink.application.extractor;

import com.example.ilink.capabilities.memory.MemoryService;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从用户对话中提取长期记忆。
 *
 * <p>唯一允许写入 {@link MemoryService} 的系统组件。
 * 在 Agent 处理完成后调用，提取值得保存的用户信息。</p>
 */
public final class MemoryExtractor {

    private static final Pattern SENSITIVE_PATTERN = Pattern.compile(
            ".*(密码|身份证|银行卡|信用卡|验证码|支付口令).*", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME_PATTERN = Pattern.compile("(?:我叫|我的名字是|名字叫)([^，。！？]{1,20})");
    private static final Pattern HOME_PATTERN = Pattern.compile("(?:我住在|我常住在|我的常住地是|我家在)([^，。！？]{2,40})");
    private static final Pattern WORK_PATTERN = Pattern.compile("(?:我在)([^，。！？]{2,40})(?:上班|工作)");
    private static final Pattern DIET_PATTERN = Pattern.compile(".*(?:不吃|忌口|过敏).*");
    private static final Pattern STABLE_PREFERENCE_PATTERN = Pattern.compile(".*(?:我平时|我一直|我通常).*(?:喜欢|偏好|习惯).*");
    private static final Pattern LONG_TERM_GOAL_PATTERN = Pattern.compile(".*(?:长期目标|今年的目标|我的目标是).*");

    private final MemoryService memoryService;

    public MemoryExtractor(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    /** 从用户消息中提取记忆，仅保存高确定性的稳定信息。 */
    public void extract(String userId, String message) {
        if (userId == null || userId.isBlank() || message == null || message.isBlank()
                || SENSITIVE_PATTERN.matcher(message).matches()) return;

        String text = message.trim();
        Matcher name = NAME_PATTERN.matcher(text);
        if (name.find()) {
            memoryService.saveFromExtractor(userId, "profile", "user_name", name.group(1).trim(), 0.95);
            return;
        }
        Matcher home = HOME_PATTERN.matcher(text);
        if (home.find()) {
            memoryService.saveFromExtractor(userId, "location", "home_location", home.group(1).trim(), 0.95);
            return;
        }
        Matcher work = WORK_PATTERN.matcher(text);
        if (work.find()) {
            memoryService.saveFromExtractor(userId, "profile", "work_place", work.group(1).trim(), 0.9);
            return;
        }
        if (DIET_PATTERN.matcher(text).matches()) {
            memoryService.saveFromExtractor(userId, "preference", stableKey("diet", text), text, 0.9);
            return;
        }
        if (STABLE_PREFERENCE_PATTERN.matcher(text).matches()) {
            memoryService.saveFromExtractor(userId, "preference", stableKey("preference", text), text, 0.8);
            return;
        }
        if (LONG_TERM_GOAL_PATTERN.matcher(text).matches()) {
            memoryService.saveFromExtractor(userId, "goal", stableKey("goal", text), text, 0.8);
        }
    }

    private String stableKey(String prefix, String content) {
        return prefix + "_" + Integer.toUnsignedString(content.toLowerCase(java.util.Locale.ROOT).hashCode(), 16);
    }
}
