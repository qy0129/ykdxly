package com.example.ilink.conversation;

import com.example.ilink.model.DocumentRecord;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class DocumentSessionStore {

    private final Map<String, DocumentRecord> documents = new ConcurrentHashMap<>();

    public void set(String userId, DocumentRecord document) {
        documents.put(userId, document);
    }

    public DocumentRecord get(String userId) {
        return documents.get(userId);
    }
}
