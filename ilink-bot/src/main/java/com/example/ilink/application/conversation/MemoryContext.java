package com.example.ilink.application.conversation;

public record MemoryContext(String memories) {
    public MemoryContext {
        memories = memories == null ? "" : memories;
    }

    public boolean isEmpty() {
        return memories.isBlank();
    }

    public String prompt() {
        return memories;
    }
}
