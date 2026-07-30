package com.example.ilink.application.conversation;

import com.example.ilink.capabilities.memory.MemoryService;

public final class MemoryContextProvider {
    private final MemoryService memoryService;

    public MemoryContextProvider(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    public MemoryContext build(String userId) {
        return new MemoryContext(memoryService.prompt(userId));
    }
}
