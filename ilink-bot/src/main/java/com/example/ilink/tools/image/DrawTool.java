package com.example.ilink.tools.image;

import com.example.ilink.feature.image.ImageService;
import com.example.ilink.feature.image.GeneratedImage;
import com.example.ilink.tools.core.Tool;
import com.example.ilink.tools.core.ToolArguments;
import com.example.ilink.tools.core.ToolContext;
import com.example.ilink.tools.core.ToolDefinition;
import com.example.ilink.tools.core.ToolResult;
import com.google.gson.JsonObject;

/** Function Calling 图片生成工具，包装 ImageService.generateImage。 */
public final class DrawTool implements Tool {

    public static final String NAME = "generate_image";

    private final ImageService imageService;
    private final ToolDefinition definition;

    /** 创建图片生成工具。 */
    public DrawTool(ImageService imageService) {
        this.imageService = imageService;

        JsonObject properties = new JsonObject();
        properties.add("prompt", ToolDefinition.stringProperty("用于生成图片的完整英文提示词"));
        properties.add("image_size", ToolDefinition.enumStringProperty(
                "图片尺寸", "1024x1024", "768x1024", "1024x576"));
        this.definition = new ToolDefinition(
                NAME,
                "图片生成",
                "根据用户明确提出的绘图要求生成一张新图片。仅描述图片或分析图片时不要调用。",
                ToolDefinition.objectParameters(properties, "prompt", "image_size"),
                true);
    }

    /** 返回图片生成工具定义。 */
    @Override
    public ToolDefinition definition() {
        return definition;
    }

    /** 调用图片服务并把图片字节放入 ToolResult.data。 */
    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) throws Exception {
        String prompt = ToolArguments.requireString(arguments, "prompt");
        String imageSize = ToolArguments.requireString(arguments, "image_size");
        GeneratedImage image = imageService.generateImage(prompt, imageSize);
        return image == null
                ? ToolResult.failure("图片生成失败")
                : ToolResult.success("图片已生成", image);
    }
}
