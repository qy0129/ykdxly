package com.changlu.planner.features.notes;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

/**
 * NotePrompt 提示词构造单元测试。
 * 覆盖消息顺序、历史转发、上下文字段与异常输入。
 */
class NotePromptTest {
  private static final Gson GSON = new Gson();

  @Test
  void messagesContainsSystemThenHistoryThenUser() {
    JsonObject body = new JsonObject();
    body.addProperty("scheduleTitle", "高等数学 · 导数");
    body.addProperty("draftNote", "# 我的笔记");
    body.addProperty("message", "精简一点");
    JsonArray keyPoints = new JsonArray();
    keyPoints.add("概念 A");
    body.add("keyPoints", keyPoints);

    JsonArray history = new JsonArray();
    JsonObject turn1 = new JsonObject();
    turn1.addProperty("role", "user");
    turn1.addProperty("content", "请写笔记");
    history.add(turn1);
    JsonObject turn2 = new JsonObject();
    turn2.addProperty("role", "assistant");
    turn2.addProperty("content", "已生成结构化笔记。");
    history.add(turn2);
    body.add("history", history);

    JsonArray messages = NotePrompt.messages(body);

    assertEquals(1 + 2 + 1, messages.size());
    assertEquals("system", messages.get(0).getAsJsonObject().get("role").getAsString());
    assertEquals(NotePrompt.SYSTEM_PROMPT, messages.get(0).getAsJsonObject().get("content").getAsString());
    assertEquals("user", messages.get(1).getAsJsonObject().get("role").getAsString());
    assertEquals("请写笔记", messages.get(1).getAsJsonObject().get("content").getAsString());
    assertEquals("assistant", messages.get(2).getAsJsonObject().get("role").getAsString());
    assertEquals("已生成结构化笔记。", messages.get(2).getAsJsonObject().get("content").getAsString());

    JsonObject user = messages.get(3).getAsJsonObject();
    assertEquals("user", user.get("role").getAsString());
    JsonObject payload = GSON.fromJson(user.get("content").getAsString(), JsonObject.class);
    assertEquals("高等数学 · 导数", payload.get("scheduleTitle").getAsString());
    assertEquals("# 我的笔记", payload.get("draftNote").getAsString());
    assertEquals("精简一点", payload.get("message").getAsString());
    assertEquals(1, payload.getAsJsonArray("keyPoints").size());
    assertTrue(payload.has("sections"));
  }

  @Test
  void missingHistoryAndFieldsAreTolerated() {
    JsonObject body = new JsonObject();
    JsonArray messages = NotePrompt.messages(body);

    assertEquals(2, messages.size());
    assertEquals("system", messages.get(0).getAsJsonObject().get("role").getAsString());
    JsonObject payload = GSON.fromJson(messages.get(1).getAsJsonObject().get("content").getAsString(), JsonObject.class);
    assertTrue(payload.has("keyPoints"));
    assertTrue(payload.getAsJsonArray("keyPoints").isEmpty());
    assertEquals("", payload.get("draftNote").getAsString());
  }

  @Test
  void invalidHistoryEntriesAreSkipped() {
    JsonObject body = new JsonObject();
    JsonArray history = new JsonArray();
    history.add(new JsonObject()); // 空对象
    JsonObject blankContent = new JsonObject();
    blankContent.addProperty("role", "assistant");
    blankContent.addProperty("content", ""); // 空内容
    history.add(blankContent);
    history.add("not-an-object"); // 非对象
    body.add("history", history);

    JsonArray messages = NotePrompt.messages(body);

    assertEquals(2, messages.size());
  }
}
