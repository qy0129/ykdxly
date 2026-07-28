package com.example.ilink.capabilities.memory;

import com.example.ilink.capabilities.memory.UserMemory;
import com.example.ilink.platform.persistence.MySqlStore;

import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 个人记忆的提取、保存、查询和遗忘服务。 */
public final class MemoryService {

    private static final Pattern LOCATION_PATTERN = Pattern.compile(
            "(?:我住在|我常住|我的常住地是|我所在的城市是|我家在)([^，。！？]{1,30})");
    private static final Pattern SENSITIVE_PATTERN = Pattern.compile(
            ".*(密码|身份证|银行卡|信用卡|验证码|支付口令).*", Pattern.CASE_INSENSITIVE);

    private static final Pattern NAME_PATTERN = Pattern.compile("(?:我叫|我的名字是|名字叫)([^，。！？]{1,20})");
    private static final Pattern HOME_PATTERN = Pattern.compile("(?:我住在|我常住在|我的常住地是|我家在)([^，。！？]{2,40})");
    private static final Pattern WORK_PATTERN = Pattern.compile("(?:我在)([^，。！？]{2,40})(?:上班|工作)");
    private static final Pattern DIET_PATTERN = Pattern.compile(".*(?:不吃|忌口|过敏).*");
    private static final Pattern STABLE_PREFERENCE_PATTERN = Pattern.compile(".*(?:我平时|我一直|我通常).*(?:喜欢|偏好|习惯).*");
    private static final Pattern LONG_TERM_GOAL_PATTERN = Pattern.compile(".*(?:长期目标|今年的目标|我的目标是).*");

    private final MySqlStore database = MySqlStore.getInstance();
    private final Map<String, List<UserMemory>> cache = new ConcurrentHashMap<>();

    public String remember(String userId, String request) {
        String content = cleanRememberRequest(request);
        if (content.isBlank()) return "你希望我记住什么呢？";
        if (SENSITIVE_PATTERN.matcher(content).matches()) {
            return "这类信息比较敏感，我不会把它保存到长期记忆里。";
        }

        MemoryValue memoryValue = classify(content);
        save(userId, memoryValue, request, 1.0);
        return "好，我记住了：" + memoryValue.value();
    }

    /** 从自然表达中提取稳定的个人信息，不保存临时位置和一次性任务。 */
    public void observe(String userId, String message) {
        if (userId == null || userId.isBlank() || message == null || message.isBlank()
                || SENSITIVE_PATTERN.matcher(message).matches()) return;

        String text = message.trim();
        Matcher name = NAME_PATTERN.matcher(text);
        if (name.find()) {
            save(userId, new MemoryValue("profile", "user_name", name.group(1).trim()), text, 0.95);
            return;
        }
        Matcher home = HOME_PATTERN.matcher(text);
        if (home.find()) {
            save(userId, new MemoryValue("location", "home_location", home.group(1).trim()), text, 0.95);
            return;
        }
        Matcher work = WORK_PATTERN.matcher(text);
        if (work.find()) {
            save(userId, new MemoryValue("profile", "work_place", work.group(1).trim()), text, 0.9);
            return;
        }
        if (DIET_PATTERN.matcher(text).matches()) {
            save(userId, new MemoryValue("preference", stableKey("diet", text), text), text, 0.9);
            return;
        }
        if (STABLE_PREFERENCE_PATTERN.matcher(text).matches()) {
            save(userId, new MemoryValue("preference", stableKey("preference", text), text), text, 0.8);
            return;
        }
        if (LONG_TERM_GOAL_PATTERN.matcher(text).matches()) {
            save(userId, new MemoryValue("goal", stableKey("goal", text), text), text, 0.8);
        }
    }

    public String forget(String userId, String request) {
        String keyword = request.replace("忘记", "").replace("忘掉", "")
                .replace("不要记得", "").replace("我的", "").trim();
        if (keyword.isBlank()) return "请告诉我需要忘掉哪一项记忆。";
        keyword = normalizeForgetKeyword(keyword);
        List<UserMemory> current = new ArrayList<>(load(userId));
        String finalKeyword = keyword;
        int before = current.size();
        current.removeIf(memory -> memory.key().contains(finalKeyword) || memory.value().contains(finalKeyword));
        int memoryCount = before - current.size();
        int count = Math.max(memoryCount, database.forgetMemories(userId, keyword));
        cache.put(userId, List.copyOf(current));
        return count > 0 ? "好的，我已经忘掉与“" + keyword + "”相关的记忆。"
                : "我没有找到与“" + keyword + "”相关的长期记忆。";
    }

    public String describe(String userId) {
        List<UserMemory> memories = load(userId);
        if (memories.isEmpty()) return "我还没有保存你的个人偏好。你可以说“记住我住在杭州”。";
        StringBuilder text = new StringBuilder("我目前记得这些：\n");
        for (UserMemory memory : memories) text.append("- ").append(memory.value()).append('\n');
        return text.append("你随时可以让我忘掉其中任何一项。").toString().trim();
    }

    public String value(String userId, String key) {
        return load(userId).stream()
                .filter(memory -> key.equals(memory.key()))
                .map(UserMemory::value)
                .findFirst().orElse("");
    }

    /** 生成只包含稳定事实的上下文，不注入来源原文和敏感数据。 */
    public String prompt(String userId) {
        List<UserMemory> memories = load(userId);
        if (memories.isEmpty()) return "";
        StringBuilder prompt = new StringBuilder("用户已明确授权保存的长期记忆：\n");
        for (UserMemory memory : memories.stream().limit(20).toList()) {
            prompt.append("- ").append(memory.value()).append('\n');
        }
        return prompt.toString().trim();
    }

    private List<UserMemory> load(String userId) {
        return cache.computeIfAbsent(userId, database::loadMemories);
    }

    private void save(String userId, MemoryValue memoryValue, String source, double confidence) {
        List<UserMemory> existing = new ArrayList<>(load(userId));
        UserMemory previous = existing.stream().filter(memory -> memory.key().equals(memoryValue.key()))
                .findFirst().orElse(null);
        if (previous != null && previous.value().equals(memoryValue.value())) return;
        LocalDateTime now = LocalDateTime.now();
        UserMemory memory = new UserMemory(previous == null ? UUID.randomUUID().toString() : previous.id(), userId,
                memoryValue.type(), memoryValue.key(), memoryValue.value(), source, confidence,
                "active", previous == null ? now : previous.createdAt(), now, now);
        database.saveMemory(memory);
        existing.removeIf(item -> item.key().equals(memory.key()));
        existing.addFirst(memory);
        cache.put(userId, List.copyOf(existing));
    }

    private String cleanRememberRequest(String request) {
        if (request == null) return "";
        return request.replaceFirst("^(请)?(帮我)?记住[：:，, ]*", "")
                .replaceFirst("^以后记得[：:，, ]*", "").trim();
    }

    private MemoryValue classify(String content) {
        Matcher location = LOCATION_PATTERN.matcher(content);
        if (location.find()) {
            String city = location.group(1).trim();
            return new MemoryValue("location", "home_location", city);
        }
        if (content.contains("怕冷")) return new MemoryValue("preference", "temperature_preference", "我比较怕冷");
        if (content.contains("怕热")) return new MemoryValue("preference", "temperature_preference", "我比较怕热");
        if (content.matches(".*(不吃|忌口|过敏).*")) {
            return new MemoryValue("preference", stableKey("diet", content), content);
        }
        if (content.matches(".*(喜欢|偏好|习惯).*")) {
            return new MemoryValue("preference", stableKey("preference", content), content);
        }
        if (content.matches(".*(目标|计划).*")) return new MemoryValue("goal", stableKey("goal", content), content);
        return new MemoryValue("profile", stableKey("fact", content), content);
    }

    private String stableKey(String prefix, String content) {
        return prefix + "_" + Integer.toUnsignedString(content.toLowerCase(Locale.ROOT).hashCode(), 16);
    }

    private String normalizeForgetKeyword(String keyword) {
        if (keyword.matches(".*(住址|常住地|城市|居住地|家庭地址).*")) return "home_location";
        if (keyword.matches(".*(怕冷|怕热|温度偏好).*")) return "temperature_preference";
        return keyword;
    }

    private record MemoryValue(String type, String key, String value) {
    }
}
