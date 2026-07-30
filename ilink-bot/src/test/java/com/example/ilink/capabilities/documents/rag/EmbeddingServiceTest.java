package com.example.ilink.capabilities.documents.rag;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddingServiceTest {

    @Test
    void usesSingleStringInputForSiliconFlowCompatibility() {
        JsonObject body = EmbeddingService.requestBody("BAAI/bge-large-zh-v1.5", "表里面有什么？");

        assertEquals("BAAI/bge-large-zh-v1.5", body.get("model").getAsString());
        assertTrue(body.get("input").isJsonPrimitive());
        assertEquals("表里面有什么？", body.get("input").getAsString());
        assertEquals("float", body.get("encoding_format").getAsString());
    }
}
