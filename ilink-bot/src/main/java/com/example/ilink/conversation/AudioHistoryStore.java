package com.example.ilink.conversation;

import com.example.ilink.model.AudioRecord;
import com.example.ilink.model.AudioSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class AudioHistoryStore {

    private final Map<String, List<AudioRecord>> histories = new ConcurrentHashMap<>();

    public void add(String userId, AudioSource source, String path, String transcript) {
        histories.computeIfAbsent(userId, key -> Collections.synchronizedList(new ArrayList<>()))
                .add(new AudioRecord(source, path, transcript));
    }

    public AudioRecord find(String userId, AudioSource source, int index) {
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
