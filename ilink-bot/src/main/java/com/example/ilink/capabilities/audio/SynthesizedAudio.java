package com.example.ilink.capabilities.audio;

/**
 * 文本转语音接口实际返回的音频数据和格式。
 */
public record SynthesizedAudio(byte[] bytes, String format) {

    /** 判断当前音频是否为 MP3。 */
    public boolean isMp3() {
        return "mp3".equals(format);
    }
}
