package com.example.ilink.application.tooling;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolSchemaValidatorTest {
    @Test
    void validatesRequiredEnumRangeAndUnknownProperties() {
        JsonObject properties = new JsonObject();
        properties.add("mode", ToolDefinition.enumStringProperty("回复模式", "text", "voice"));
        properties.add("count", ToolDefinition.integerProperty("数量", 1, 10));
        JsonObject schema = ToolDefinition.objectParameters(properties, "mode", "count");
        ToolSchemaValidator validator = new ToolSchemaValidator();

        JsonObject valid = new JsonObject();
        valid.addProperty("mode", "voice");
        valid.addProperty("count", 2);
        assertTrue(validator.validate(schema, valid).valid());

        JsonObject invalid = valid.deepCopy();
        invalid.addProperty("count", 20);
        assertFalse(validator.validate(schema, invalid).valid());

        JsonObject unknown = valid.deepCopy();
        unknown.addProperty("unexpected", true);
        assertFalse(validator.validate(schema, unknown).valid());
    }
}
