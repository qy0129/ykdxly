package com.example.ilink.capabilities.audio;

import com.example.ilink.capabilities.audio.AudioService;
import com.example.ilink.capabilities.audio.SynthesizedAudio;
import com.example.ilink.application.tooling.Tool;
import com.example.ilink.application.tooling.ToolArguments;
import com.example.ilink.application.tooling.ToolContext;
import com.example.ilink.application.tooling.ToolDefinition;
import com.example.ilink.application.tooling.ToolResult;
import com.google.gson.JsonObject;

/** Function Calling 文本转语音工具。 */
public final class SpeechTool implements Tool {

    public static final String NAME = "synthesize_speech";

    private final AudioService audioService;
    private final ToolDefinition definition;

    /** 创建语音合成工具。 */
    public SpeechTool(AudioService audioService) {
        this.audioService = audioService;

        JsonObject properties = new JsonObject();
        properties.add("text", ToolDefinition.stringProperty("需要转换为语音的完整文本"));
        properties.add("voice_style", ToolDefinition.enumStringProperty(
                "语音音色", "default", "boy", "girl", "male", "female", "warm", "lively"));
        this.definition = new ToolDefinition(
                NAME,
                "语音合成",
                "把指定文本转换为语音。仅当用户明确需要语音内容时调用。",
                ToolDefinition.objectParameters(properties, "text", "voice_style"),
                true);
    }

    /** 返回语音合成工具定义。 */
    @Override
    public ToolDefinition definition() {
        return definition;
    }

    /** 合成语音并返回实际音频格式和字节。 */
    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) throws Exception {
        SynthesizedAudio audio = audioService.synthesize(
                ToolArguments.requireString(arguments, "text"),
                ToolArguments.string(arguments, "voice_style", "default"));
        return ToolResult.success("语音已生成", audio);
    }
}
