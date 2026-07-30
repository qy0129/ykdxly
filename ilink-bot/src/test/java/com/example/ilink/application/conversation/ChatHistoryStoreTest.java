package com.example.ilink.application.conversation;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void summaryIsMergedIntoTheLeadingSystemMessage() throws Exception {
        ChatHistoryStore history = new ChatHistoryStore(HttpClient.newHttpClient(), null);
        setSummary(history, "用户偏好清淡食物");
        history.addUserMessage("user", "附近有什么好吃的");

        JsonArray messages = new JsonArray();
        JsonObject systemMessage = new JsonObject();
        systemMessage.addProperty("role", "system");
        systemMessage.addProperty("content", "识别用户意图");
        messages.add(systemMessage);

        history.addHistoryMessages(messages, "user");

        assertEquals("system", messages.get(0).getAsJsonObject().get("role").getAsString());
        assertTrue(messages.get(0).getAsJsonObject().get("content").getAsString()
                .contains("用户偏好清淡食物"));
        assertEquals(1, countRole(messages, "system"));
        assertEquals("user", messages.get(1).getAsJsonObject().get("role").getAsString());
    }

    @Test
    void concurrentWebSessionScopesKeepHistoriesIsolated() throws Exception {
        try (ChatHistoryStore history = new ChatHistoryStore(HttpClient.newHttpClient(), null);
             ExecutorService workers = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);

            Future<JsonArray> first = workers.submit(() -> scopedTurn(
                    history, ready, start, "session-a", "question-a", "answer-a"));
            Future<JsonArray> second = workers.submit(() -> scopedTurn(
                    history, ready, start, "session-b", "question-b", "answer-b"));

            ready.await();
            start.countDown();
            assertTurn(first.get(), "question-a", "answer-a");
            assertTurn(second.get(), "question-b", "answer-b");
        }
    }

    private static JsonArray scopedTurn(ChatHistoryStore history, CountDownLatch ready,
                                        CountDownLatch start, String sessionId,
                                        String question, String answer) throws Exception {
        try (ChatHistoryStore.SessionScope ignored = history.bindSession("web-user", sessionId)) {
            ready.countDown();
            start.await();
            history.add("web-user", question, answer);
            JsonArray messages = new JsonArray();
            history.addHistoryMessages(messages, "web-user");
            return messages;
        }
    }

    private static void assertTurn(JsonArray messages, String question, String answer) {
        assertEquals(2, messages.size());
        assertEquals(question, messages.get(0).getAsJsonObject().get("content").getAsString());
        assertEquals(answer, messages.get(1).getAsJsonObject().get("content").getAsString());
    }

    @SuppressWarnings("unchecked")
    private static void setSummary(ChatHistoryStore history, String summary) throws Exception {
        Field field = ChatHistoryStore.class.getDeclaredField("conversationSummary");
        field.setAccessible(true);
        ((Map<String, String>) field.get(history)).put("user", summary);
    }

    private static int countRole(JsonArray messages, String role) {
        int count = 0;
        for (var message : messages) {
            if (role.equals(message.getAsJsonObject().get("role").getAsString())) count++;
        }
        return count;
    }
}
