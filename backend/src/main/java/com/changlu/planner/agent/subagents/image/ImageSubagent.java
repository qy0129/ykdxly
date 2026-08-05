package com.changlu.planner.agent.subagents.image;

import com.changlu.planner.agent.core.contract.AgentContext;
import com.changlu.planner.agent.core.contract.AgentResult;
import com.changlu.planner.agent.core.contract.Subagent;
import com.changlu.planner.agent.core.contract.SubagentDefinition;
import com.changlu.planner.agent.core.contract.SubagentRequest;
import com.changlu.planner.agent.core.tool.ToolCall;
import com.changlu.planner.agent.core.tool.ToolRegistry;
import com.changlu.planner.agent.subagents.image.tools.ImageGenerationTool;
import com.google.gson.JsonObject;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 文生图 Subagent：领域编排。校验边界、解析意图，再经注册的 image.generate Tool 执行生成。 */
public final class ImageSubagent implements Subagent {
  public static final String NAME = "image.generation";

  private static final List<String> SUPPORTED = List.of(
      "生成图片", "画一张", "画个", "帮我画", "文生图", "插画", "海报", "头像", "壁纸", "漫画", "配图", "AI 绘图");
  private static final List<String> UNSUPPORTED = List.of(
      "健康状况诊断与用药建议",
      "金融投资决策",
      "基于图片内容的高级编辑（裁剪、抠图、修复局部）",
      "涉及他人肖像或敏感内容");

  private final ToolRegistry tools;
  private final ImagePrompt prompt;
  private final SubagentDefinition definition;

  public ImageSubagent(ToolRegistry tools, ImagePrompt prompt) {
    this.tools = tools;
    this.prompt = prompt;
    this.definition = new SubagentDefinition(
        NAME, "1.0.0",
        "根据文字描述生成 AI 图片，支持尺寸与风格，返回图片 URL；批量生成或删除图片前请求用户确认",
        SUPPORTED, UNSUPPORTED,
        ImageSchema.load("input.schema.json"), ImageSchema.load("output.schema.json"),
        Set.of(ImageGenerationTool.NAME), true, true, Duration.ofSeconds(120), 3);
  }

  @Override public SubagentDefinition definition() { return definition; }

  @Override public AgentResult execute(SubagentRequest request, AgentContext context) throws Exception {
    String traceId = context.traceId();
    String unsafe = refused(request.message());
    if (unsafe != null) {
      return AgentResult.failed("IMAGE_REFUSED", unsafe + "不在文生图能力范围内。", false, traceId);
    }

    boolean fromMessage = !request.arguments().has("prompt");
    String raw = fromMessage ? request.message() : request.arguments().get("prompt").getAsString();
    if (prompt.insufficient(raw)) {
      JsonObject data = new JsonObject();
      data.addProperty("hint", "请描述你想生成的画面主题与内容");
      return AgentResult.waitingUser("看起来还没有具体的画面描述。能否告诉我你想生成什么？", data, traceId);
    }

    try {
      JsonObject args = fromMessage ? prompt.parse(request.message()) : request.arguments().deepCopy();
      args.addProperty("prompt", prompt.requirePrompt(raw));
      String idempotencyKey = traceId + ":" + Integer.toHexString(raw.trim().hashCode());
      ToolCall call = new ToolCall(context.runId() + ":image", idempotencyKey, ImageGenerationTool.NAME, args);
      return tools.execute(call, context);
    } catch (IllegalArgumentException error) {
      return AgentResult.failed("INVALID_ARGUMENT", "图片请求参数有误：" + error.getMessage(), false, traceId);
    }
  }

  /** 健康/金融等受限意图直接拒绝，不进入生成。 */
  private String refused(String message) {
    if (message == null) return null;
    String normalized = message.replaceAll("\\s", "").toLowerCase(Locale.ROOT);
    if (normalized.contains("诊断") || normalized.contains("用药") || normalized.contains("处方")
        || normalized.contains("治疗方案") || normalized.contains("病情") || normalized.contains("症状")) {
      return "健康状况诊断与用药建议";
    }
    if (normalized.contains("买股票") || normalized.contains("投资") || normalized.contains("理财")
        || normalized.contains("收益预测")) {
      return "金融投资决策";
    }
    return null;
  }
}