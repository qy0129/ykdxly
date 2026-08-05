package com.changlu.planner.agent.subagents.image.tools;

import com.changlu.planner.agent.core.contract.AgentContext;
import com.changlu.planner.agent.core.contract.AgentResult;
import com.changlu.planner.agent.core.contract.AgentStatus;
import com.changlu.planner.agent.core.tool.RetryPolicy;
import com.changlu.planner.agent.core.tool.ToolCall;
import com.changlu.planner.agent.core.tool.ToolDefinition;
import com.changlu.planner.agent.core.tool.ToolHandler;
import com.changlu.planner.agent.core.tool.ToolRiskLevel;
import com.changlu.planner.agent.core.tool.ToolSideEffect;
import com.changlu.planner.agent.subagents.image.ImageGenerationRequest;
import com.changlu.planner.agent.subagents.image.ImageGenerationResult;
import com.changlu.planner.agent.subagents.image.ImagePrompt;
import com.changlu.planner.agent.subagents.image.ImageSchema;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 文生图工具：参数/操作校验、批量与删除的确认草案、调用 Service 生成并持久化。
 * 参数错误直接返回结构化失败；外部失败经 Service 有界重试后统一脱敏为可读消息。
 */
public final class ImageGenerationTool implements ToolHandler {
  public static final String NAME = "image.generate";

  private final ImageGenerationService service;
  private final ImagePrompt prompt;
  private final ToolDefinition definition;

  public ImageGenerationTool(ImageGenerationService service, ImagePrompt prompt) {
    this.service = service;
    this.prompt = prompt;
    this.definition = new ToolDefinition(
        NAME, "1.0.0",
        "根据文字描述调用文生图模型生成图片，返回图片 URL；支持尺寸与风格，批量生成或删除前请求用户确认",
        ImageSchema.load("input.schema.json"), ImageSchema.load("output.schema.json"),
        Set.of("image.generate"), ToolRiskLevel.HIGH_RISK_WRITE, ToolSideEffect.EXTERNAL_WRITE, false,
        Duration.ofSeconds(120), RetryPolicy.none());
  }

  @Override public ToolDefinition definition() { return definition; }

  @Override public AgentResult execute(ToolCall call, AgentContext context) {
    String traceId = context.traceId();
    JsonObject args = normalizedArguments(call.arguments());
    try {
      String rawPrompt = string(args, "prompt", "");
      if (rawPrompt.isBlank() || prompt.insufficient(rawPrompt)) {
        JsonObject data = new JsonObject();
        data.addProperty("hint", "请描述图片的主体和场景。");
        return AgentResult.waitingUser("请告诉我你想生成什么图片。", data, traceId);
      }
      String promptText = prompt.requirePrompt(rawPrompt);
      String size = prompt.requireSize(string(args, "size", null));
      String style = prompt.requireStyle(string(args, "style", null));
      int quality = prompt.requireQuality(intValue(args, "quality", 2));
      ImageGenerationRequest request = new ImageGenerationRequest(promptText, size, style, quality);

      String action = string(args, "action", "create");
      String mode = string(args, "mode", "single");
      int count = Math.max(1, intValue(args, "count", 1));
      boolean confirmed = bool(args, "confirmed", false);

      if ("delete".equals(action)) {
        return confirmed
            ? AgentResult.failed("IMAGE_DELETE_UNSUPPORTED", "删除已生成图片的能力尚未接入，暂不支持该操作。", false, traceId)
            : confirmation(traceId, request, "delete", mode, count);
      }
      if ("update".equals(action)) {
        return AgentResult.failed("IMAGE_UPDATE_UNSUPPORTED", "修改已生成图片的能力尚未接入，暂不支持该操作。", false, traceId);
      }
      if ("batch".equals(mode) || count > 1) {
        if (!confirmed) return confirmation(traceId, request, "batch", mode, count);
        return generateBatch(request, count, call.idempotencyKey(), traceId, context);
      }
      return generateSingle(request, call.idempotencyKey(), traceId, context);
    } catch (IllegalArgumentException error) {
      return AgentResult.failed("INVALID_ARGUMENT", "图片请求参数有误：" + safeMessage(error), false, traceId);
    }
  }

  /** Accept both direct tool calls and the AgentRuntime envelope. */
  private JsonObject normalizedArguments(JsonObject raw) {
    JsonObject args = raw == null ? new JsonObject() : raw.deepCopy();
    if (!args.has("prompt") && args.has("arguments") && args.get("arguments").isJsonObject()) {
      args = args.getAsJsonObject("arguments").deepCopy();
    }
    if (!args.has("prompt") && args.has("message") && !args.get("message").isJsonNull()) {
      args.addProperty("prompt", args.get("message").getAsString());
    }
    return args;
  }

  private AgentResult generateSingle(ImageGenerationRequest request, String idempotencyKey,
                                     String traceId, AgentContext context) {
    try {
      ImageGenerationResult result = service.generate(request, idempotencyKey, traceId, context.identity());
      if (!result.success()) {
        return AgentResult.failed("IMAGE_GENERATION_FAILED", userMessage(), false, traceId);
      }
      JsonObject data = new JsonObject();
      data.addProperty("requestId", result.requestId());
      data.addProperty("status", "SUCCESS");
      data.addProperty("imageUrl", result.imageUrl());
      data.addProperty("size", result.size());
      data.addProperty("style", result.style());
      data.addProperty("durationMillis", result.durationMillis());
      return AgentResult.completed("已为你生成图片。", data, traceId);
    } catch (ImageGenerationException error) {
      return AgentResult.failed(code(error), userMessage(error), false, traceId);
    } catch (Exception error) {
      return AgentResult.failed("IMAGE_GENERATION_FAILED", "图片生成失败，请稍后再试。", false, traceId);
    }
  }

  private AgentResult generateBatch(ImageGenerationRequest request, int count, String idempotencyKey,
                                    String traceId, AgentContext context) {
    JsonArray images = new JsonArray();
    int failed = 0;
    for (int index = 1; index <= count; index++) {
      String key = idempotencyKey == null || idempotencyKey.isBlank()
          ? traceId + ":" + index : idempotencyKey + ":" + index;
      try {
        ImageGenerationResult result = service.generate(request, key, traceId, context.identity());
        if (!result.success()) { failed++; continue; }
        JsonObject row = new JsonObject();
        row.addProperty("index", index);
        row.addProperty("imageUrl", result.imageUrl());
        row.addProperty("requestId", result.requestId());
        images.add(row);
      } catch (ImageGenerationException error) {
        failed++;
      } catch (Exception error) {
        failed++;
      }
    }
    JsonObject data = new JsonObject();
    data.add("images", images);
    data.addProperty("total", images.size());
    data.addProperty("failed", failed);
    if (images.isEmpty()) {
      return AgentResult.failed("IMAGE_GENERATION_FAILED", "批量生成失败，请稍后再试。", false, traceId);
    }
    String reply = failed == 0
        ? "已为你生成 " + images.size() + " 张图片。"
        : "已为你生成 " + images.size() + " 张图片，另有 " + failed + " 张生成失败。";
    return AgentResult.completed(reply, data, traceId);
  }

  private AgentResult confirmation(String traceId, ImageGenerationRequest request, String action,
                                   String mode, int count) {
    String id = UUID.randomUUID().toString();
    JsonObject draft = new JsonObject();
    draft.addProperty("id", id);
    draft.addProperty("type", "image." + action + ".generate");
    draft.addProperty("action", action);
    JsonArray items = new JsonArray();
    JsonObject item = new JsonObject();
    item.addProperty("prompt", request.prompt());
    item.addProperty("size", request.size());
    item.addProperty("style", request.style());
    items.add(item);
    draft.add("items", items);
    draft.addProperty("impactScope", "delete".equals(action) ? "删除已生成图片" : "按张数调用文生图付费服务");
    draft.addProperty("estimatedCost", "按生成张数与规格计费");
    draft.addProperty("risk", "外部付费调用，不可回滚；确认后执行");
    JsonObject data = new JsonObject();
    data.add("draft", draft);
    // AgentRuntime 确认自定义草案时需要原始参数，避免再次让模型猜测提示词。
    JsonObject draftRequest = new JsonObject();
    draftRequest.addProperty("prompt", request.prompt());
    draftRequest.addProperty("size", request.size());
    draftRequest.addProperty("style", request.style());
    draftRequest.addProperty("quality", request.quality());
    draftRequest.addProperty("mode", mode);
    draftRequest.addProperty("count", count);
    data.add("request", draftRequest);
    data.addProperty("requiresConfirmation", true);
    return new AgentResult("1.0", AgentStatus.WAITING_CONFIRMATION,
        "delete".equals(action) ? "删除图片需要你的确认。" : "批量生成图片需要你的确认。",
        data, List.of(), traceId, true, id);
  }

  private String code(ImageGenerationException error) {
    return switch (error.code()) {
      case "RATE_LIMITED" -> "IMAGE_RATE_LIMITED";
      case "AUTH_FAILED" -> "IMAGE_AUTH_FAILED";
      case "INVALID_ARGUMENT" -> "IMAGE_PROVIDER_REJECTED";
      case "EXTERNAL_SERVICE_UNAVAILABLE" -> "IMAGE_SERVICE_UNAVAILABLE";
      default -> "IMAGE_GENERATION_FAILED";
    };
  }

  private String userMessage(ImageGenerationException error) {
    return switch (error.code()) {
      case "RATE_LIMITED" -> "生成过于频繁，请稍后再试。";
      case "AUTH_FAILED" -> "文生图服务未授权，请联系管理员检查 image.api.key 与模型订阅。";
      case "INVALID_ARGUMENT" -> "图片请求未被绘图服务接受，请调整描述后重试。";
      case "EXTERNAL_SERVICE_UNAVAILABLE" -> "文生图服务暂时不可用，请稍后再试。";
      default -> "图片生成失败，请稍后再试。";
    };
  }

  private String userMessage() { return "图片生成失败，请稍后再试。"; }

  private String safeMessage(IllegalArgumentException error) {
    String text = error.getMessage();
    return text == null ? "参数不合法" : text;
  }

  private String string(JsonObject args, String name, String fallback) {
    return args.has(name) && !args.get(name).isJsonNull() ? args.get(name).getAsString() : fallback;
  }

  private int intValue(JsonObject args, String name, int fallback) {
    return args.has(name) && !args.get(name).isJsonNull() ? args.get(name).getAsInt() : fallback;
  }

  private boolean bool(JsonObject args, String name, boolean fallback) {
    return args.has(name) && !args.get(name).isJsonNull() ? args.get(name).getAsBoolean() : fallback;
  }
}
