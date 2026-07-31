package com.example.ilink.capabilities.documents;

import com.example.ilink.application.conversation.DocumentSessionStore;
import com.google.gson.JsonArray;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentEditToolTest {

    @Test
    void imagePathIsOptionalUnlessTheRequestInsertsAnImage() {
        DocumentEditTool tool = new DocumentEditTool(
                new DocumentAiService(null, null), new DocumentService(), new DocumentSessionStore());
        JsonArray required = tool.definition().parameters().getAsJsonArray("required");

        assertTrue(required.contains(new com.google.gson.JsonPrimitive("request")));
        assertTrue(required.contains(new com.google.gson.JsonPrimitive("output_type")));
        assertFalse(required.contains(new com.google.gson.JsonPrimitive("image_path")));
    }
}
