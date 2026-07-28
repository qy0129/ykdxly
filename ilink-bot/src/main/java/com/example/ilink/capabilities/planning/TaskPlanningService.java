package com.example.ilink.capabilities.planning;

import com.example.ilink.bootstrap.Config;
import com.example.ilink.capabilities.planning.PlanTask;
import com.example.ilink.capabilities.planning.TaskPlan;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 任务规划业务服务。
 *
 * <p>使用文本模型把自然语言目标拆分为结构化任务，再由本地代码按照截止日期、
 * 可用时间和优先级完成排期。调整计划和进度统计也统一放在本服务中。</p>
 */
public final class TaskPlanningService {

    private static final Pattern DURATION_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(小时|分钟)");

    private final HttpClient httpClient;
    private final Gson gson = new Gson();

    /** 注入项目共享的 HTTP 客户端。 */
    public TaskPlanningService(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * 将用户目标拆分成结构化任务。
     *
     * <p>模型请求失败时返回一组通用执行阶段，保证计划流程仍然可以继续展示。</p>
     */
    public List<PlanTask> decompose(String goal) {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("model", Config.MODEL);
            body.addProperty("temperature", 0.1);
            body.addProperty("enable_thinking", false);

            JsonArray messages = new JsonArray();
            JsonObject system = new JsonObject();
            system.addProperty("role", "system");
            system.addProperty("content", "你是任务拆分器。只输出JSON，不要Markdown和解释。"
                    + "把目标拆成3到8个可执行任务，每个任务必须有title、description、estimated_minutes和priority。"
                    + "estimated_minutes为15到480的整数，priority只能是high、medium或low。"
                    + "输出格式：{\"tasks\":[{\"title\":\"\",\"description\":\"\","
                    + "\"estimated_minutes\":60,\"priority\":\"medium\"}]}。");
            messages.add(system);

            JsonObject user = new JsonObject();
            user.addProperty("role", "user");
            user.addProperty("content", "需要完成的目标：" + goal);
            messages.add(user);
            body.add("messages", messages);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(Config.API_BASE_URL))
                    .timeout(Config.REQ_TIMEOUT)
                    .header("Authorization", "Bearer " + Config.API_KEY)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("HTTP " + response.statusCode());
            }

            String content = JsonParser.parseString(response.body()).getAsJsonObject()
                    .getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("content").getAsString();
            List<PlanTask> tasks = parseTasks(content);
            if (!tasks.isEmpty()) {
                return tasks;
            }
        } catch (Exception e) {
            System.err.println("[规划服务] AI任务拆分失败，使用基础拆分：" + e.getMessage());
        }
        return fallbackTasks(goal);
    }

    /** 按截止日期、优先级和每日可用时间生成完整计划。 */
    public TaskPlan createPlan(String goal, LocalDate deadline,
                               String availableTime, List<PlanTask> sourceTasks) {
        LocalDate today = LocalDate.now();
        LocalDate safeDeadline = deadline.isBefore(today) ? today : deadline;
        int dayCount = (int) Math.max(1, ChronoUnit.DAYS.between(today, safeDeadline) + 1);
        List<Integer> dailyCapacities = parseDailyCapacities(availableTime, dayCount);

        List<PlanTask> orderedTasks = new ArrayList<>(sourceTasks);
        orderedTasks.sort(Comparator.comparingInt(task -> priorityOrder(task.priority())));

        List<PlanTask> scheduledTasks = new ArrayList<>();
        int dayIndex = 0;
        int usedMinutes = 0;
        for (PlanTask task : orderedTasks) {
            int capacity = dailyCapacities.get(dayIndex);
            if (usedMinutes > 0 && usedMinutes + task.estimatedMinutes() > capacity && dayIndex < dayCount - 1) {
                dayIndex++;
                usedMinutes = 0;
                capacity = dailyCapacities.get(dayIndex);
            }
            scheduledTasks.add(task.scheduleOn(today.plusDays(dayIndex).toString()));
            usedMinutes += Math.min(task.estimatedMinutes(), capacity);
        }

        String planId = "PLAN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        return new TaskPlan(planId, goal, safeDeadline.toString(),
                availableTime, today.toString(), scheduledTasks);
    }

    /**
     * 根据用户的新说明调整当前计划。
     *
     * <p>包含“完成”并明确提到任务标题时标记该任务完成；包含“没做完、延期、推迟”时，
     * 将未完成任务从次日开始重新排期。</p>
     */
    public TaskPlan adjustPlan(TaskPlan plan, String changeRequest) {
        String request = changeRequest == null ? "" : changeRequest.trim();
        boolean delayRequested = request.contains("没做完")
                || request.contains("未完成")
                || request.contains("延期")
                || request.contains("推迟");
        boolean completionRequested = (request.contains("已完成") || request.contains("完成了"))
                && !delayRequested;

        List<PlanTask> adjustedTasks = new ArrayList<>();
        for (PlanTask task : plan.tasks()) {
            PlanTask adjusted = task;
            if (completionRequested && request.contains(task.title())) {
                adjusted = task.withStatus("completed");
            }
            if (delayRequested && !"completed".equals(adjusted.status())) {
                LocalDate scheduled = parseDate(adjusted.scheduledDate(), LocalDate.now());
                LocalDate earliest = LocalDate.now().plusDays(1);
                LocalDate shifted = scheduled.isBefore(earliest) ? earliest : scheduled.plusDays(1);
                adjusted = adjusted.scheduleOn(shifted.toString());
            }
            adjustedTasks.add(adjusted);
        }
        return plan.withTasks(adjustedTasks);
    }

    /** 生成当前计划完成率、下一项任务和逾期情况。 */
    public String buildProgress(TaskPlan plan) {
        long completed = plan.completedCount();
        int total = plan.tasks().size();
        int percent = total == 0 ? 0 : (int) Math.round(completed * 100.0 / total);
        PlanTask nextTask = plan.tasks().stream()
                .filter(task -> !"completed".equals(task.status()))
                .findFirst()
                .orElse(null);

        StringBuilder result = new StringBuilder();
        result.append("计划目标：").append(plan.goal()).append('\n')
                .append("完成进度：").append(completed).append('/').append(total)
                .append("（").append(percent).append("%）").append('\n');
        if (nextTask == null) {
            result.append("所有任务已经完成。");
        } else {
            result.append("下一项任务：").append(nextTask.title()).append('\n')
                    .append("安排日期：").append(nextTask.scheduledDate()).append('\n')
                    .append("预计耗时：").append(nextTask.estimatedMinutes()).append("分钟");
        }
        return result.toString();
    }

    /** 将任务列表转换为便于展示的中文文本。 */
    public String formatTasks(List<PlanTask> tasks) {
        StringBuilder text = new StringBuilder("任务已拆分为：\n");
        for (int index = 0; index < tasks.size(); index++) {
            PlanTask task = tasks.get(index);
            text.append(index + 1).append(". ").append(task.title())
                    .append("（预计").append(task.estimatedMinutes()).append("分钟）\n");
        }
        return text.toString().trim();
    }

    /** 解析模型返回的任务 JSON。 */
    private List<PlanTask> parseTasks(String content) {
        JsonArray array = parseJsonObject(content).getAsJsonArray("tasks");
        List<PlanTask> tasks = new ArrayList<>();
        if (array == null) return tasks;

        for (int index = 0; index < array.size(); index++) {
            JsonObject item = array.get(index).getAsJsonObject();
            String title = item.has("title") ? item.get("title").getAsString().trim() : "";
            if (title.isBlank()) continue;
            String description = item.has("description") ? item.get("description").getAsString() : "";
            int minutes = item.has("estimated_minutes") ? item.get("estimated_minutes").getAsInt() : 60;
            String priority = item.has("priority") ? normalizePriority(item.get("priority").getAsString()) : "medium";
            tasks.add(new PlanTask("TASK-" + (index + 1), title, description,
                    Math.min(480, Math.max(15, minutes)), priority, "", "pending"));
        }
        return tasks;
    }

    /** 生成模型不可用时仍可执行的基础任务阶段。 */
    private List<PlanTask> fallbackTasks(String goal) {
        return List.of(
                new PlanTask("TASK-1", "明确目标和验收标准",
                        "确认“" + goal + "”最终需要交付的内容。", 30, "high", "", "pending"),
                new PlanTask("TASK-2", "准备所需资料和环境",
                        "收集资料、检查配置并准备执行环境。", 45, "high", "", "pending"),
                new PlanTask("TASK-3", "完成核心任务",
                        "优先完成对最终结果影响最大的工作。", 120, "high", "", "pending"),
                new PlanTask("TASK-4", "联调和检查结果",
                        "检查各部分能否正常配合并修正问题。", 60, "medium", "", "pending"),
                new PlanTask("TASK-5", "整理交付和展示内容",
                        "准备最终文件、说明和演示流程。", 45, "medium", "", "pending"));
    }

    /** 从用户可用时间说明中提取每天可使用的分钟数。 */
    private List<Integer> parseDailyCapacities(String availableTime, int dayCount) {
        List<Integer> parsed = new ArrayList<>();
        Matcher matcher = DURATION_PATTERN.matcher(availableTime == null ? "" : availableTime);
        while (matcher.find()) {
            double amount = Double.parseDouble(matcher.group(1));
            int minutes = "小时".equals(matcher.group(2))
                    ? (int) Math.round(amount * 60) : (int) Math.round(amount);
            parsed.add(Math.max(30, minutes));
        }
        if (parsed.isEmpty()) parsed.add(120);

        List<Integer> capacities = new ArrayList<>();
        for (int day = 0; day < dayCount; day++) {
            capacities.add(parsed.get(Math.min(day, parsed.size() - 1)));
        }
        return capacities;
    }

    /** 将模型给出的优先级转换为固定枚举值。 */
    private String normalizePriority(String priority) {
        return switch (priority == null ? "" : priority.toLowerCase(Locale.ROOT)) {
            case "high", "medium", "low" -> priority.toLowerCase(Locale.ROOT);
            default -> "medium";
        };
    }

    /** 返回优先级排序值，数值越小越优先。 */
    private int priorityOrder(String priority) {
        return switch (priority) {
            case "high" -> 0;
            case "low" -> 2;
            default -> 1;
        };
    }

    /** 从 Markdown 代码块或普通文本中提取 JSON 对象。 */
    private JsonObject parseJsonObject(String content) {
        String json = content.trim();
        if (json.startsWith("```")) {
            int firstLineEnd = json.indexOf('\n');
            int closingFence = json.lastIndexOf("```");
            if (firstLineEnd >= 0 && closingFence > firstLineEnd) {
                json = json.substring(firstLineEnd + 1, closingFence).trim();
            }
        }
        int objectStart = json.indexOf('{');
        int objectEnd = json.lastIndexOf('}');
        if (objectStart >= 0 && objectEnd > objectStart) {
            json = json.substring(objectStart, objectEnd + 1);
        }
        return JsonParser.parseString(json).getAsJsonObject();
    }

    /** 解析日期字符串，失败时返回默认日期。 */
    private LocalDate parseDate(String value, LocalDate defaultDate) {
        try {
            return LocalDate.parse(value);
        } catch (Exception ignored) {
            return defaultDate;
        }
    }
}
