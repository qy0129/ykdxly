package com.example.ilink.tools.audio;

import com.example.ilink.conversation.AudioHistoryStore;
import com.example.ilink.feature.audio.AudioService;
import com.example.ilink.model.AudioRecord;
import com.example.ilink.model.AudioSource;
import com.example.ilink.tools.core.Tool;
import com.example.ilink.tools.core.ToolArguments;
import com.example.ilink.tools.core.ToolContext;
import com.example.ilink.tools.core.ToolDefinition;
import com.example.ilink.tools.core.ToolResult;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;

/** Function Calling 历史语音转文字工具。 */
public final class AudioTranscribeTool implements Tool {

    public static final String NAME = "transcribe_audio";

    private final AudioService audioService;
    private final AudioHistoryStore audioHistory;
    private final ToolDefinition definition;

    /** 创建语音转写工具。 */
    public AudioTranscribeTool(AudioService audioService, AudioHistoryStore audioHistory) {
        this.audioService = audioService;
        this.audioHistory = audioHistory;

        JsonObject properties = new JsonObject();
        properties.add("source", ToolDefinition.enumStringProperty(
                "语音来源：user 用户发送、bot 机器人发送、any 任意来源", "user", "bot", "any"));
        properties.add("index", ToolDefinition.integerProperty(
                "从最近一条开始计算的语音序号", 1, 100));
        this.definition = new ToolDefinition(
                NAME,
                "语音转文字",
                "把用户指定的历史语音转换成文字。用户没有明确要求转写语音时不要调用。",
                ToolDefinition.objectParameters(properties, "source", "index"),
                true);
    }

    /** 返回语音转写工具定义。 */
    @Override
    public ToolDefinition definition() {
        return definition;
    }

    /** 查找历史语音，必要时执行转写并缓存结果。 */
    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) throws Exception {
        AudioSource source = switch (ToolArguments.string(arguments, "source", "any")) {
            case "bot" -> AudioSource.BOT;
            case "user" -> AudioSource.USER;
            default -> AudioSource.ANY;
        };
        int index = Math.max(1, Math.min(ToolArguments.integer(arguments, "index", 1), 100));
        AudioRecord record = audioHistory.find(context.userId(), source, index);
        if (record == null) {
            return ToolResult.failure("没有找到指定的历史语音");
        }

        String transcript = record.transcript();
        if (transcript == null || transcript.isBlank()) {
            Path path = Path.of(record.path());
            if (!Files.exists(path)) {
                return ToolResult.failure("语音文件已经不存在");
            }
            transcript = audioService.transcribe(path);
            record.setTranscript(transcript);
        }

        String owner = record.source() == AudioSource.BOT ? "机器人" : "用户";
        return ToolResult.success("第" + index + "条" + owner + "语音文字：\n" + transcript, record);
    }
}
