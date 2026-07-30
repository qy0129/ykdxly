package com.example.ilink.capabilities.travel;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DidiMcpClientTest {

    @Test
    void readsStructuredContentFromSandboxTextJson() {
        JsonObject result = JsonParser.parseString("""
                {"content":[{"type":"text","text":"{\\"traceId\\":\\"estimate-1\\",\\"items\\":[]}"}]}
                """).getAsJsonObject();

        assertEquals("estimate-1", DidiMcpClient.structuredContent(result).get("traceId").getAsString());
    }

    @Test
    void readsSnakeCaseStructuredContent() {
        JsonObject result = JsonParser.parseString("""
                {"structured_content":{"data":{"traceId":"estimate-2","items":[]}}}
                """).getAsJsonObject();

        assertEquals("estimate-2", DidiMcpClient.structuredContent(result).get("traceId").getAsString());
    }
}
