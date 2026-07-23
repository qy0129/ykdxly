package com.example.ilink.routing;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.http.HttpClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentPolicyTest {

    @Test
    void imageRequestDoesNotAuthorizeDocumentOutput() {
        assertTrue(IntentPolicy.isExplicitImageCreation("帮我生成一张猫咪图片"));
        assertFalse(IntentPolicy.hasExplicitFileRequest("帮我生成一张猫咪图片"));
        assertFalse(IntentPolicy.hasExplicitFileRequest("帮我生成一个图片文件"));
    }

    @Test
    void explicitDocumentFormatsAreRecognized() {
        assertEquals("pdf", IntentPolicy.explicitOutputFileType("整理成 PDF 给我"));
        assertEquals("docx", IntentPolicy.explicitOutputFileType("导出为 Word 文档"));
        assertTrue(IntentPolicy.hasExplicitFileRequest("导出为 Word 文档"));
        assertTrue(IntentPolicy.isFileTypeAnswer("PDF 格式"));
    }

    @Test
    void imageAndDocumentEditsAreSeparatedByExplicitObject() {
        assertTrue(IntentPolicy.isExplicitImageEdit("把这张图片改成水彩风格"));
        assertTrue(IntentPolicy.isExplicitDocumentEdit("把文档第三段删除"));
        assertFalse(IntentPolicy.isExplicitDocumentEdit("把这张图片改成水彩风格"));
    }

    @Test
    void wrongFileIntentIsCorrectedToDraw() throws Exception {
        IntentRecognizer recognizer = new IntentRecognizer(HttpClient.newHttpClient());
        JsonObject action = new JsonObject();
        action.addProperty("intent", "generate_file");
        action.addProperty("action_text", "生成一张城市夜景图片");
        action.addProperty("output_file_type", "docx");

        Method normalize = IntentRecognizer.class.getDeclaredMethod(
                "normalizeAction", String.class, String.class, JsonObject.class, IntentContext.class);
        normalize.setAccessible(true);
        normalize.invoke(recognizer, "生成一张城市夜景图片", "生成一张城市夜景图片",
                action, new IntentContext(false, false, false, true));

        assertEquals("draw", action.get("intent").getAsString());
        assertEquals("none", action.get("output_file_type").getAsString());
        assertEquals("生成一张城市夜景图片", action.get("en_prompt").getAsString());
    }

    @Test
    void hallucinatedFileIntentFallsBackToChat() throws Exception {
        IntentRecognizer recognizer = new IntentRecognizer(HttpClient.newHttpClient());
        JsonObject action = new JsonObject();
        action.addProperty("intent", "generate_file");
        action.addProperty("output_file_type", "pdf");

        Method normalize = IntentRecognizer.class.getDeclaredMethod(
                "normalizeAction", String.class, String.class, JsonObject.class, IntentContext.class);
        normalize.setAccessible(true);
        normalize.invoke(recognizer, "给我讲个笑话", "给我讲个笑话",
                action, new IntentContext(false, false, false, true));

        assertEquals("chat", action.get("intent").getAsString());
        assertEquals("none", action.get("output_file_type").getAsString());
    }
}
