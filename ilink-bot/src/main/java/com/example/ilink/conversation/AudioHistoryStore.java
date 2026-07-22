package com.example.ilink.conversation;

import com.example.ilink.model.AudioRecord;
import com.example.ilink.model.AudioSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户语音历史存储。
 *
 * <p>按用户保存收到的语音和机器人合成的语音，支持按来源和倒序编号查找，
 * 供“把第几条语音转成文字”等后续请求使用。</p>
 */
public final class AudioHistoryStore {

    private final Map<String, List<AudioRecord>> histories = new ConcurrentHashMap<>();

    /** 追加一条语音记录。 */
    public void add(String userId, AudioSource source, String path, String transcript) {
        // 使用线程安全列表，消息处理线程和定时任务可以同时访问历史记录。
        histories.computeIfAbsent(userId, key -> Collections.synchronizedList(new ArrayList<>()))
                .add(new AudioRecord(source, path, transcript));
    }

    /** 按来源和倒序编号查找语音，找不到时返回 null。 */
    public AudioRecord find(String userId, AudioSource source, int index) {
        // 用户看到的“第 1 条”按最近一条开始计数，而不是按文件创建顺序计数。
        if (index < 1) return null;
        List<AudioRecord> records = histories.get(userId);
        if (records == null) return null;

        synchronized (records) {
            int matched = 0;
            for (int i = records.size() - 1; i >= 0; i--) {
                AudioRecord record = records.get(i);
                if (source != AudioSource.ANY && record.source() != source) continue;
                if (++matched == index) return record;
            }
        }
        return null;
    }
}
