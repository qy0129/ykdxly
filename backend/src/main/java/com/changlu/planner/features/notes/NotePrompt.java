package com.changlu.planner.features.notes;

import com.changlu.planner.agent.core.ModelClient;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** 笔记对话提示词构造；纯函数，便于单元测试。 */
public final class NotePrompt {
  private static final Gson GSON = new Gson();

  private NotePrompt() {}

  public static final String SYSTEM_PROMPT = """
      你是“学习笔记整理助手”，内嵌在笔记编辑器里。用户会给你当前这篇学习笔记和本次日程的学习资料，
      你负责根据用户的最新指令整篇改写这份 Markdown 笔记。你只能输出一个 JSON 对象，不要输出任何其它文字。

      ## 输入字段
      - scheduleTitle：本次日程主题
      - studyNote：AI 整理的学习资料摘要
      - keyPoints：关键点列表
      - sections：资料分节，形如 [{title, content}]
      - draftNote：当前笔记的完整内容，可能包含用户手写内容、图片、表格，必须保留
      - message：用户本条最新指令
      - history：之前几轮对话（用户指令 + 你的回复摘要），仅用于理解上下文，不要复述完整笔记

      ## 输出要求
      1. 只输出如下格式的 JSON（字符串内的换行必须写成 \\n，不要输出真实换行；不要用 ``` 代码块包裹 JSON）：
         {"markdown":"整篇修订后的完整笔记","reply":"本次改动的简短中文说明"}
      2. markdown 必须是整篇笔记的全文，绝不是差异片段；不得省略 draftNote 里已有的任何内容。
      3. 保留用户自己添加的内容（手写段落、图片、表格等），除非 message 明确要求修改。
      4. 以 scheduleTitle、studyNote、keyPoints、sections 为事实依据，不要编造资料中没有的事实。
      5. reply 用一句话（50 字以内）概括本次改动，例如“已精简为要点式结构”“已补充一个具体示例”。""";

  public static JsonArray messages(JsonObject body) {
    JsonArray messages = new JsonArray();
    messages.add(ModelClient.message("system", SYSTEM_PROMPT));

    // 转发历史对话（用户指令 + AI 回复摘要），保持 token 有界；完整笔记只由 draftNote 携带。
    JsonElement historyElement = body.get("history");
    if (historyElement != null && historyElement.isJsonArray()) {
      for (JsonElement item : historyElement.getAsJsonArray()) {
        if (!item.isJsonObject()) continue;
        JsonObject entry = item.getAsJsonObject();
        String role = text(entry, "role");
        String content = text(entry, "content");
        if (!role.isBlank() && !content.isBlank()) messages.add(ModelClient.message(role, content));
      }
    }

    JsonObject user = new JsonObject();
    user.addProperty("scheduleTitle", text(body, "scheduleTitle"));
    user.addProperty("studyNote", text(body, "studyNote"));
    user.addProperty("draftNote", text(body, "draftNote"));
    user.add("keyPoints", body.has("keyPoints") ? body.get("keyPoints") : new JsonArray());
    user.add("sections", body.has("sections") ? body.get("sections") : new JsonArray());
    user.addProperty("message", text(body, "message"));
    messages.add(ModelClient.message("user", GSON.toJson(user)));
    return messages;
  }

  private static String text(JsonObject object, String key) {
    JsonElement value = object.get(key);
    return value == null || value.isJsonNull() ? "" : value.getAsString();
  }
}
