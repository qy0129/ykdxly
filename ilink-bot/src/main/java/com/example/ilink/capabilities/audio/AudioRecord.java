package com.example.ilink.capabilities.audio;

/**
 * 一条语音记录。
 *
 * <p>记录语音来源、落盘路径和可选的转写结果。转写结果允许后续异步补充，
 * 因此使用可变字段保存。</p>
 */
public final class AudioRecord {

    private final AudioSource source;
    private final String path;
    private volatile String transcript;

    /** 创建语音记录。transcript 可以为空，表示尚未完成转写。 */
    public AudioRecord(AudioSource source, String path, String transcript) {
        this.source = source;
        this.path = path;
        this.transcript = transcript;
    }

    /** 返回语音来源。 */
    public AudioSource source() {
        return source;
    }

    /** 返回语音文件路径。 */
    public String path() {
        return path;
    }

    /** 返回当前转写文本，尚未转写时可能为空。 */
    public String transcript() {
        return transcript;
    }

    /** 保存语音转写结果。 */
    public void setTranscript(String transcript) {
        this.transcript = transcript;
    }
}
