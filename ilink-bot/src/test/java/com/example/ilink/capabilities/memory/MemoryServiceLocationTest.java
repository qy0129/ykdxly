package com.example.ilink.capabilities.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MemoryServiceLocationTest {

    @Test
    void extractsHomeAndCurrentLocationsFromOneSentence() {
        MemoryService service = new MemoryService();
        String request = "记住我常住地在南京，并且我现在在南京";

        assertEquals("南京", service.extractHomeLocation(request));
        assertEquals("南京", service.extractCurrentLocation(request));
    }
}
