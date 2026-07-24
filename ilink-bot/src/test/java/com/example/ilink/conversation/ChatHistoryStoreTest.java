package com.example.ilink.conversation;

import com.google.gson.JsonArray;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatHistoryStoreTest {

    @Test
    void unifiedRecordingDoesNotDuplicateExistingTurnWrites() {
        ChatHistoryStore history = new ChatHistoryStore(HttpClient.newHttpClient(), null);

        history.addUserMessage("user", "附近有什么好吃的");
        history.add("user", "附近有什么好吃的", "附近有这些餐厅");
        history.addAssistantMessage("user", "附近有这些餐厅");

        JsonArray messages = new JsonArray();
        history.addHistoryMessages(messages, "user");

        assertEquals(2, messages.size());
        assertEquals("user", messages.get(0).getAsJsonObject().get("role").getAsString());
        assertEquals("assistant", messages.get(1).getAsJsonObject().get("role").getAsString());
    }

    @Test
    void currentInputIsExcludedBeforeSendingItAgainToModel() {
        ChatHistoryStore history = new ChatHistoryStore(HttpClient.newHttpClient(), null);
        history.addUserMessage("user", "我现在在杭州市阿里高桥园区");

        JsonArray messages = new JsonArray();
        history.addHistoryMessages(messages, "user", "我现在在杭州市阿里高桥园区");

        assertEquals(0, messages.size());
    }
}
