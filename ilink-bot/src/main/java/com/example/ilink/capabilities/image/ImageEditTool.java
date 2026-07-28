package com.example.ilink.capabilities.image;

import com.example.ilink.application.conversation.UserSessionStore;
import com.example.ilink.capabilities.image.ImageService;
import com.example.ilink.capabilities.image.GeneratedImage;
import com.example.ilink.application.tooling.Tool;
import com.example.ilink.application.tooling.ToolArguments;
import com.example.ilink.application.tooling.ToolContext;
import com.example.ilink.application.tooling.ToolDefinition;
import com.example.ilink.application.tooling.ToolResult;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;

/** Function Calling 图片编辑工具，包装 ImageService.editImage。 */
public final class ImageEditTool implements Tool {

    public static final String NAME = "edit_image";

    private final ImageService imageService;
    private final UserSessionStore sessions;
    private final ToolDefinition definition;

    /** 创建图片编辑工具。 */
    public ImageEditTool(ImageService imageService, UserSessionStore sessions) {
        this.imageService = imageService;
        this.sessions = sessions;

        JsonObject properties = new JsonObject();
        properties.add("prompt", ToolDefinition.stringProperty("对当前图片的完整修改要求"));
        this.definition = new ToolDefinition(
                NAME,
                "图片编辑",
                "按照用户要求修改最近发送的图片。没有当前图片时不要调用。",
                ToolDefinition.objectParameters(properties, "prompt"),
                true);
    }

    /** 返回图片编辑工具定义。 */
    @Override
    public ToolDefinition definition() {
        return definition;
    }

    /** 编辑当前图片并返回新图片字节。 */
    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) throws Exception {
        String pendingImage = sessions.peekPendingImage(context.userId());
        String imagePath = pendingImage != null ? pendingImage : sessions.getLastImage(context.userId());
        if (imagePath == null || !Files.exists(Path.of(imagePath))) {
            return ToolResult.failure("没有找到需要编辑的图片");
        }

        GeneratedImage image = imageService.editImage(
                Path.of(imagePath), ToolArguments.requireString(arguments, "prompt"));
        if (image == null) {
            return ToolResult.failure("图片编辑失败");
        }
        sessions.clearPendingImage(context.userId());
        return ToolResult.success("图片编辑完成", image);
    }
}
