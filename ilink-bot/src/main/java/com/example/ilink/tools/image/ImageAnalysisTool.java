package com.example.ilink.tools.image;

import com.example.ilink.conversation.UserSessionStore;
import com.example.ilink.feature.image.ImageService;
import com.example.ilink.tools.core.Tool;
import com.example.ilink.tools.core.ToolArguments;
import com.example.ilink.tools.core.ToolContext;
import com.example.ilink.tools.core.ToolDefinition;
import com.example.ilink.tools.core.ToolResult;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/** Function Calling 图片分析工具，包装 ImageService.vision。 */
public final class ImageAnalysisTool implements Tool {

    public static final String NAME = "analyze_image";

    private final ImageService imageService;
    private final UserSessionStore sessions;
    private final ToolDefinition definition;

    /** 创建图片分析工具。 */
    public ImageAnalysisTool(ImageService imageService, UserSessionStore sessions) {
        this.imageService = imageService;
        this.sessions = sessions;

        JsonObject properties = new JsonObject();
        properties.add("request", ToolDefinition.stringProperty("用户对当前图片的完整问题或分析要求"));
        properties.add("mode", ToolDefinition.enumStringProperty(
                "analyze 表示分析图片，solve 表示解答图片中的题目", "analyze", "solve"));
        this.definition = new ToolDefinition(
                NAME,
                "图片分析",
                "分析用户最近发送的图片，或解答图片中的题目。没有当前图片时不要调用。",
                ToolDefinition.objectParameters(properties, "request", "mode"),
                true);
    }

    /** 返回图片分析工具定义。 */
    @Override
    public ToolDefinition definition() {
        return definition;
    }

    /** 从用户会话取得当前图片并调用视觉模型。 */
    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) throws Exception {
        String imagePath = currentImagePath(context.userId());
        if (imagePath == null || !Files.exists(Path.of(imagePath))) {
            return ToolResult.failure("没有找到需要分析的图片");
        }

        String request = ToolArguments.requireString(arguments, "request");
        String mode = ToolArguments.string(arguments, "mode", "analyze");
        String prompt = "solve".equals(mode)
                ? "请识别图片中的题目，并给出详细、准确的解题过程和答案。用户要求：" + request
                : "请根据用户要求分析这张图片：" + request;
        String reply = imageService.vision(prompt,
                Base64.getEncoder().encodeToString(Files.readAllBytes(Path.of(imagePath))));
        if (reply == null || reply.isBlank()) {
            return ToolResult.failure("图片分析失败");
        }
        sessions.clearPendingImage(context.userId());
        return ToolResult.success(reply, imagePath);
    }

    /** 优先返回待处理图片，否则返回最近图片。 */
    private String currentImagePath(String userId) {
        String pendingImage = sessions.peekPendingImage(userId);
        return pendingImage != null ? pendingImage : sessions.getLastImage(userId);
    }
}
