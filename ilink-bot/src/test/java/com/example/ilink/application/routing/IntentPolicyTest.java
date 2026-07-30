package com.example.ilink.application.routing;

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
        assertTrue(IntentPolicy.isExplicitImageCreation("帮我生成一张小猫照片"));
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
        assertTrue(IntentPolicy.isExplicitImageEdit("将上面生成那只小猫的眼睛上戴一个墨镜"));
        assertTrue(IntentPolicy.isExplicitImageEdit("帮我在刚刚那只小猫的图片上再加一个小狗"));
        assertTrue(IntentPolicy.isExplicitDocumentEdit("把文档第三段删除"));
        assertFalse(IntentPolicy.isExplicitDocumentEdit("把这张图片改成水彩风格"));
    }

    @Test
    void repeatReplyCommandsAreHandledLocally() {
        assertTrue(IntentPolicy.isRepeatRequest("你重新发一遍"));
        assertTrue(IntentPolicy.isRepeatRequest("上一条再发一次"));
        assertTrue(IntentPolicy.isRepeatRequest("帮我重发一下"));
        assertFalse(IntentPolicy.isRepeatRequest("帮我重新发送一封邮件"));
    }

    @Test
    void casualGreetingsAreHandledLocally() {
        assertTrue(IntentPolicy.isCasualGreeting("你好"));
        assertTrue(IntentPolicy.isCasualGreeting("在吗？"));
        assertFalse(IntentPolicy.isCasualGreeting("你好，帮我查天气"));
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
                action, new IntentContext(false, false, false, true, false));

        assertEquals("draw", action.get("intent").getAsString());
        assertEquals("none", action.get("output_file_type").getAsString());
        assertEquals("生成一张城市夜景图片", action.get("en_prompt").getAsString());
    }

    @Test
    void explicitImageRequestCorrectsModelChatIntentWithinRouting() throws Exception {
        IntentRecognizer recognizer = new IntentRecognizer(HttpClient.newHttpClient());
        JsonObject action = new JsonObject();
        action.addProperty("intent", "chat");
        action.addProperty("action_text", "帮我生成一张小猫照片");

        Method normalize = IntentRecognizer.class.getDeclaredMethod(
                "normalizeAction", String.class, String.class, JsonObject.class, IntentContext.class);
        normalize.setAccessible(true);
        normalize.invoke(recognizer, "帮我生成一张小猫照片", "帮我生成一张小猫照片",
                action, new IntentContext(false, false, false, false, false));

        assertEquals("draw", action.get("intent").getAsString());
        assertEquals("none", action.get("image_size").getAsString());
    }

    @Test
    void imageActionUsesOriginalRequestWhenModelOmitsImagePrompt() throws Exception {
        IntentRecognizer recognizer = new IntentRecognizer(HttpClient.newHttpClient());
        JsonObject action = new JsonObject();
        action.addProperty("intent", "image_action");
        action.addProperty("image_action", "analyze");

        Method normalize = IntentRecognizer.class.getDeclaredMethod(
                "normalizeAction", String.class, String.class, JsonObject.class, IntentContext.class);
        normalize.setAccessible(true);
        normalize.invoke(recognizer, "分析这张图片里的表格", "分析这张图片里的表格",
                action, new IntentContext(true, true, false, false, false));

        assertEquals("分析这张图片里的表格", action.get("image_prompt").getAsString());
    }

    @Test
    void explicitImageEditCorrectsChatIntentAndMissingSubtype() throws Exception {
        IntentRecognizer recognizer = new IntentRecognizer(HttpClient.newHttpClient());
        JsonObject action = new JsonObject();
        action.addProperty("intent", "chat");

        Method normalize = IntentRecognizer.class.getDeclaredMethod(
                "normalizeAction", String.class, String.class, JsonObject.class, IntentContext.class);
        normalize.setAccessible(true);
        normalize.invoke(recognizer, "帮我在刚刚那只小猫的图片上再加一个小狗",
                "帮我在刚刚那只小猫的图片上再加一个小狗", action,
                new IntentContext(false, true, false, false, false));

        assertEquals("image_action", action.get("intent").getAsString());
        assertEquals("edit", action.get("image_action").getAsString());
        assertEquals("帮我在刚刚那只小猫的图片上再加一个小狗",
                action.get("image_prompt").getAsString());
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
                action, new IntentContext(false, false, false, true, false));

        assertEquals("chat", action.get("intent").getAsString());
        assertEquals("none", action.get("output_file_type").getAsString());
    }

    @Test
    void locationAndFoodPreferenceBecomeKeywordNearbySearch() throws Exception {
        IntentRecognizer recognizer = new IntentRecognizer(HttpClient.newHttpClient());
        JsonObject action = new JsonObject();
        action.addProperty("intent", "food_order");
        action.addProperty("action_text", "我现在在阿里高桥云港园区，我想吃麦当劳了");
        action.addProperty("food_order_restaurants", "麦当劳");

        Method normalize = IntentRecognizer.class.getDeclaredMethod(
                "normalizeAction", String.class, String.class, JsonObject.class, IntentContext.class);
        normalize.setAccessible(true);
        normalize.invoke(recognizer, "我现在在阿里高桥云港园区，我想吃麦当劳了",
                "我现在在阿里高桥云港园区，我想吃麦当劳了", action,
                new IntentContext(false, false, false, false, false));

        assertEquals("nearby_food", action.get("intent").getAsString());
        assertEquals("麦当劳", action.get("meal_keyword").getAsString());
        assertEquals("search", action.get("nearby_action").getAsString());
    }

    @Test
    void nearbyKeywordCanBeRecoveredFromNaturalLanguage() {
        assertTrue(IntentRecognizer.isNearbyDiningRequest("我现在在阿里高桥云港园区，我想吃麦当劳了"));
        assertEquals("麦当劳", IntentRecognizer.inferNearbyFoodKeyword(
                "我现在在阿里高桥云港园区，我想吃麦当劳了"));
        assertEquals("麦当劳", IntentRecognizer.inferNearbyFoodKeyword("这附近有麦当劳吗"));
        assertEquals("", IntentRecognizer.inferNearbyFoodKeyword("附近有什么好吃的"));
        assertTrue(IntentRecognizer.isNearbyDiningRequest("我附近有什么好吃的"));
        assertEquals("", IntentRecognizer.inferNearbyFoodKeyword("我现在在阿里高桥云港园区"));
        assertFalse(IntentRecognizer.isNearbyDiningRequest("帮我点麦当劳外卖"));
    }

    @Test
    void genericNearbyQuestionDoesNotBecomePoiKeyword() throws Exception {
        IntentRecognizer recognizer = new IntentRecognizer(HttpClient.newHttpClient());
        JsonObject action = new JsonObject();
        action.addProperty("intent", "nearby_food");
        action.addProperty("meal_keyword", "什么好吃的");
        action.addProperty("nearby_action", "search");

        Method normalize = IntentRecognizer.class.getDeclaredMethod(
                "normalizeAction", String.class, String.class, JsonObject.class, IntentContext.class);
        normalize.setAccessible(true);
        normalize.invoke(recognizer, "附近有什么好吃的", "附近有什么好吃的",
                action, new IntentContext(false, false, false, false, false));

        assertEquals("", action.get("meal_keyword").getAsString());
        assertEquals("search", action.get("nearby_action").getAsString());
    }

    @Test
    void hallucinatedNearbyFoodIntentFallsBackToChat() throws Exception {
        IntentRecognizer recognizer = new IntentRecognizer(HttpClient.newHttpClient());
        JsonObject action = new JsonObject();
        action.addProperty("intent", "nearby_food");
        action.addProperty("nearby_location", "你好");

        Method normalize = IntentRecognizer.class.getDeclaredMethod(
                "normalizeAction", String.class, String.class, JsonObject.class, IntentContext.class);
        normalize.setAccessible(true);
        normalize.invoke(recognizer, "你好", "你好", action,
                new IntentContext(false, false, false, false, false));

        assertEquals("chat", action.get("intent").getAsString());
        assertEquals("", action.get("nearby_location").getAsString());
    }
}
