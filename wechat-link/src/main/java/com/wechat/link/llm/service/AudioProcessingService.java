package com.wechat.link.llm.service;

import com.alibaba.dashscope.aigc.multimodalconversation.AudioParameters;
import com.alibaba.dashscope.aigc.multimodalconversation.AudioResult;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationOutput;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.utils.Constants;
import com.wechat.link.llm.config.LLMProperties;
import io.reactivex.Flowable;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.Base64;

/**
 * 语音处理服务
 * <p>
 * TTS（文字转语音）：通过 DashScope SDK {@link MultiModalConversation#streamCall}
 * 调用 qwen3-tts-flash，流式收集 Base64 音频数据。
 * </p>
 */
@Slf4j
@Service
public class AudioProcessingService {

    private final LLMProperties properties;

    public AudioProcessingService(LLMProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        Constants.baseHttpApiUrl = properties.getVoice().getTts().getBaseUrl() + "/api/v1";
        log.info("[AudioService] SDK base URL set to: {}", Constants.baseHttpApiUrl);
    }

    // ==================== TTS（文字转语音）远端调用 ====================

    /**
     * 通过 DashScope SDK {@link MultiModalConversation#streamCall} 将文字合成为语音
     * <p>
     * 使用 {@code qwen3-tts-instruct-flash} 模型（支持指令控制），流式模式下 SDK 自动从
     * {@link AudioResult#getData()} 返回 Base64 编码的音频片段，无需 OSS URL。
     * </p>
     * <p>
     * {@code instruction} 为自然语言语气描述（如"语气欢快充满喜悦"），
     * 对应百炼 Qwen-TTS 指令控制功能中 {@code parameters.instructions} 字段。
     * 为空时不传指令，由模型默认风格朗读。
     * </p>
     *
     * @param text        待合成语音的文字内容
     * @param instruction 自然语言语气描述（可选，为空则模型默认风格）
     * @return 音频字节数组（MP3 格式）；若 TTS 未启用、文本为空或调用失败则返回 null
     */
    public byte[] synthesizeSpeech(String text, String instruction) {
        LLMProperties.TtsConfig ttsConfig = properties.getVoice().getTts();
        if (!ttsConfig.getEnabled() || text == null || text.isBlank()) {
            return null;
        }

        log.info("【模型调用】TTS 合成 - model={}, 音色={}, 文本长度={}, 指令={}",
                ttsConfig.getModel(), ttsConfig.getVoice(), text.length(),
                instruction != null ? instruction : "无");

        try {
            MultiModalConversationParam.MultiModalConversationParamBuilder<?, ?> builder =
                    MultiModalConversationParam.builder()
                            .apiKey(ttsConfig.getApiKey())
                            .model(ttsConfig.getModel())
                            .text(text)
                            .voice(resolveVoice(ttsConfig.getVoice()));

            // 设置输出音频格式（mp3/wav/pcm）
            builder.parameter("format", ttsConfig.getResponseFormat());

            if (instruction != null && !instruction.isBlank()) {
                builder.parameter("instructions", instruction);
                builder.parameter("optimize_instructions", true);
            }

            MultiModalConversationParam param = builder.build();

            MultiModalConversation conv = new MultiModalConversation();
            Flowable<MultiModalConversationResult> stream = conv.streamCall(param);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            stream.blockingIterable().forEach(event -> {
                MultiModalConversationOutput output = event.getOutput();
                if (output == null) return;
                AudioResult audio = output.getAudio();
                if (audio == null) return;
                String data = audio.getData();
                if (data != null && !data.isEmpty()) {
                    try {
                        baos.write(Base64.getDecoder().decode(data));
                    } catch (Exception e) {
                        log.warn("[AudioService] Base64 解码失败: {}", e.getMessage());
                    }
                }
            });

            byte[] result = baos.toByteArray();
            if (result.length > 0) {
                log.info("[AudioService] TTS 合成成功，原始大小: {}KB", result.length / 1024);
                
                // 检测实际格式
                String detectedFormat = detectAudioFormat(result);
                log.info("[AudioService] 检测到的音频格式: {}", detectedFormat);
                
                // 百炼流式 TTS 返回 PCM 裸数据，需要封装为 WAV 才能播放
                if (detectedFormat.contains("PCM") || detectedFormat.contains("UNKNOWN")) {
                    log.info("[AudioService] PCM → WAV 封装，采样率=24000, 16bit, mono");
                    result = pcmToWav(result, 24000, 16, 1);
                }
                
                log.info("[AudioService] 最终音频大小: {}KB, 格式: {}",
                        result.length / 1024, detectAudioFormat(result));
                return result;
            }

            log.warn("[AudioService] TTS 流式响应中未找到音频数据");
            return null;

        } catch (Exception e) {
            log.error("[AudioService] TTS 调用失败", e);
            return null;
        }
    }

    /**
     * 单参数重载，向后兼容（不传指令，由模型默认风格朗读）
     */
    public byte[] synthesizeSpeech(String text) {
        return synthesizeSpeech(text, null);
    }

    /**
     * 将 PCM 裸数据封装为标准 WAV 文件（添加 44 字节 RIFF/WAV header）
     *
     * @param pcmData    PCM 音频数据（16-bit signed, little-endian）
     * @param sampleRate 采样率（百炼 TTS 通常为 24000Hz）
     * @param bitDepth   位深度（通常 16）
     * @param channels   声道数（通常 1 = mono）
     * @return 完整的 WAV 文件字节数组
     */
    private byte[] pcmToWav(byte[] pcmData, int sampleRate, int bitDepth, int channels) {
        int byteRate = sampleRate * channels * bitDepth / 8;
        int blockAlign = channels * bitDepth / 8;
        int dataSize = pcmData.length;
        int fileSize = 36 + dataSize;

        byte[] wav = new byte[44 + dataSize];
        
        // RIFF header
        wav[0] = 'R'; wav[1] = 'I'; wav[2] = 'F'; wav[3] = 'F';
        writeInt32LE(wav, 4, fileSize);
        wav[8] = 'W'; wav[9] = 'A'; wav[10] = 'V'; wav[11] = 'E';
        
        // fmt sub-chunk
        wav[12] = 'f'; wav[13] = 'm'; wav[14] = 't'; wav[15] = ' ';
        writeInt32LE(wav, 16, 16);           // sub-chunk size = 16 (PCM)
        writeInt16LE(wav, 20, 1);            // audio format = 1 (PCM)
        writeInt16LE(wav, 22, channels);     // channels
        writeInt32LE(wav, 24, sampleRate);   // sample rate
        writeInt32LE(wav, 28, byteRate);     // byte rate
        writeInt16LE(wav, 32, blockAlign);   // block align
        writeInt16LE(wav, 34, bitDepth);     // bits per sample
        
        // data sub-chunk
        wav[36] = 'd'; wav[37] = 'a'; wav[38] = 't'; wav[39] = 'a';
        writeInt32LE(wav, 40, dataSize);
        
        // PCM data
        System.arraycopy(pcmData, 0, wav, 44, dataSize);
        
        return wav;
    }

    private void writeInt32LE(byte[] buf, int offset, int value) {
        buf[offset]     = (byte) (value & 0xFF);
        buf[offset + 1] = (byte) ((value >> 8) & 0xFF);
        buf[offset + 2] = (byte) ((value >> 16) & 0xFF);
        buf[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    private void writeInt16LE(byte[] buf, int offset, int value) {
        buf[offset]     = (byte) (value & 0xFF);
        buf[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }

    /**
     * 检测音频数据的实际格式（根据文件头魔数判断）
     */
    private String detectAudioFormat(byte[] data) {
        if (data.length < 4) return "UNKNOWN (too short)";
        
        // MP3: ID3 tag (49 44 33) 或 frame sync (FF FB / FF F3 / FF F2)
        if (data[0] == 0x49 && data[1] == 0x44 && data[2] == 0x33) return "MP3 (ID3 tag)";
        if ((data[0] & 0xFF) == 0xFF && ((data[1] & 0xE0) == 0xE0)) return "MP3 (frame sync)";
        
        // WAV: RIFF header (52 49 46 46)
        if (data[0] == 0x52 && data[1] == 0x49 && data[2] == 0x46 && data[3] == 0x46) return "WAV (RIFF)";
        
        // FLAC: fLaC (66 4C 61 43)
        if (data[0] == 0x66 && data[1] == 0x4C && data[2] == 0x61 && data[3] == 0x43) return "FLAC";
        
        // OGG: OggS (4F 67 67 53)
        if (data[0] == 0x4F && data[1] == 0x67 && data[2] == 0x67 && data[3] == 0x53) return "OGG/OPUS";
        
        // AAC: ADTS frame (FF F1 / FF F9)
        if ((data[0] & 0xFF) == 0xFF && ((data[1] & 0xF0) == 0xF0)) return "AAC (ADTS)";
        
        return "UNKNOWN/PCM (no recognized header)";
    }

    /**
     * 将配置中的音色字符串（如 {@code "Cherry"}）转为 SDK 枚举
     * <p>
     * 若字符串与 {@link AudioParameters.Voice} 枚举名不匹配，回退为 {@link AudioParameters.Voice#CHERRY}。
     * </p>
     */
    private AudioParameters.Voice resolveVoice(String voiceName) {
        if (voiceName == null) return AudioParameters.Voice.CHERRY;
        try {
            return AudioParameters.Voice.valueOf(voiceName.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("[AudioService] 未知音色: {}，使用默认 Cherry", voiceName);
            return AudioParameters.Voice.CHERRY;
        }
    }

}
