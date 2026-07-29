package com.example.ilink.application.executive;

import com.example.ilink.application.tooling.ToolContext;
import com.example.ilink.application.tooling.ToolManager;
import com.example.ilink.application.tooling.ToolResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 将持久化步骤转换为已有 ToolManager 调用。 */
public final class ToolCapabilityExecutor implements CapabilityExecutor {
    private static final Pattern STEP_OUTPUT = Pattern.compile("\\{\\{step:(\\d+)}}", Pattern.CASE_INSENSITIVE);
    private final ToolManager tools;

    public ToolCapabilityExecutor(ToolManager tools) {
        this.tools = tools;
    }

    @Override
    public ExecutionOutcome execute(ExecutiveTask task, ExecutiveStep step,
                                    List<ExecutiveStep> allSteps) {
        if (step.toolName().isBlank()) return ExecutionOutcome.failure("步骤没有配置工具：" + step.capability());
        JsonObject arguments;
        try {
            arguments = JsonParser.parseString(step.inputJson()).getAsJsonObject();
            arguments = expandObject(arguments, allSteps);
        } catch (Exception error) {
            return ExecutionOutcome.failure("工具参数不是有效 JSON：" + error.getMessage());
        }
        ToolResult result = tools.execute(step.toolName(), new ToolContext(task.userId(), task.id()), arguments);
        return result.success() ? ExecutionOutcome.success(result.output())
                : ExecutionOutcome.retry(result.output());
    }

    private JsonObject expandObject(JsonObject source, List<ExecutiveStep> steps) {
        JsonObject result = new JsonObject();
        for (var entry : source.entrySet()) result.add(entry.getKey(), expand(entry.getValue(), steps));
        return result;
    }

    private JsonElement expand(JsonElement value, List<ExecutiveStep> steps) {
        if (value == null || value.isJsonNull()) return value;
        if (value.isJsonObject()) return expandObject(value.getAsJsonObject(), steps);
        if (value.isJsonArray()) {
            JsonArray result = new JsonArray();
            for (JsonElement item : value.getAsJsonArray()) result.add(expand(item, steps));
            return result;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return value.deepCopy();
        String text = value.getAsString();
        Matcher matcher = STEP_OUTPUT.matcher(text);
        StringBuffer expanded = new StringBuffer();
        while (matcher.find()) {
            int sequence = Integer.parseInt(matcher.group(1));
            String output = steps.stream().filter(step -> step.sequence() == sequence)
                    .map(ExecutiveStep::outputText).findFirst().orElse("");
            matcher.appendReplacement(expanded, Matcher.quoteReplacement(output));
        }
        matcher.appendTail(expanded);
        return new com.google.gson.JsonPrimitive(expanded.toString());
    }
}
