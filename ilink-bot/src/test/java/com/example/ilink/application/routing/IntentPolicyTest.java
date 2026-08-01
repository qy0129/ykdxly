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
        assertEquals("xlsx", IntentPolicy.explicitOutputFileType("生成 Excel 文件"));
        assertEquals("md", IntentPolicy.explicitOutputFileType("保存为 Markdown"));
        assertEquals("csv", IntentPolicy.explicitOutputFileType("导出 CSV 文件"));
        assertEquals("xlsx", IntentPolicy.explicitOutputFileType("生成一份项目表格"));
        assertTrue(IntentPolicy.hasExplicitFileRequest("导出为 Word 文档"));
        assertTrue(IntentPolicy.isFileTypeAnswer("PDF 格式"));
        assertTrue(IntentPolicy.isFileTypeAnswer("Excel"));
    }

    @Test
    void imageAndDocumentEditsAreSeparatedByExplicitObject() {
        assertTrue(IntentPolicy.isExplicitImageEdit("把这张图片改成水彩风格"));
        assertTrue(IntentPolicy.isExplicitImageEdit("将上面生成那只小猫的眼睛上戴一个墨镜"));
        assertTrue(IntentPolicy.isExplicitImageEdit("帮我在刚刚那只小猫的图片上再加一个小狗"));
        assertTrue(IntentPolicy.isExplicitDocumentEdit("把文档第三段删除"));
        assertTrue(IntentPolicy.isExplicitDocumentEdit("在这个文件后面增加一段文字"));
        assertTrue(IntentPolicy.isDocumentImageInsertion("把刚才的图片插入文档第二页"));
        assertTrue(IntentPolicy.isExplicitDocumentEdit("把第三段里的甲方替换成乙方"));
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
    void explicitTodoCreationIsSeparatedFromTodoQuestions() {
        assertTrue(IntentPolicy.isExplicitTodoCreation("新建以下待办事项：明天交周报"));
        assertTrue(IntentPolicy.isExplicitTodoCreation("帮我把明天买菜加入待办"));
        assertFalse(IntentPolicy.isExplicitTodoCreation("如何创建待办？"));
        assertFalse(IntentPolicy.isExplicitTodoCreation("我们讨论一下待办管理功能"));
    }

    @Test
    void completionReportIsRecognizedAsTodoCompletionCandidate() {
        String report = "我已经完成今晚的 Python 学习任务，帮我记录完成情况。";

        assertTrue(IntentPolicy.isTodoCompletionReport(report));
        assertEquals("complete", IntentPolicy.inferTodoAction(report));
        assertFalse(IntentPolicy.isTodoCompletionReport("如何完成今晚的 Python 学习任务？"));
        assertFalse(IntentPolicy.isTodoCompletionReport("我还没完成今晚的 Python 学习任务。"));
        assertFalse(IntentPolicy.isTodoCompletionReport("查询今晚任务的完成情况。"));
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

    @Test
    void explicitBusinessRequestsAreSeparatedFromChat() {
        assertTrue(IntentPolicy.isExplicitFoodOrderRequest("帮我点外卖"));
        assertTrue(IntentPolicy.isExplicitFoodOrderRequest("帮我点外婆家外卖"));
        assertEquals("food_order", IntentPolicy.explicitBusinessIntent("帮我点外卖"));
        assertEquals("nearby_food", IntentPolicy.explicitBusinessIntent("我在西湖附近有什么好吃的"));
        assertEquals("news_search", IntentPolicy.explicitBusinessIntent("帮我查询今天的 AI 新闻"));
        assertEquals("web_search", IntentPolicy.explicitBusinessIntent("帮我搜索 Java 虚拟线程资料"));
        assertEquals("weather", IntentPolicy.explicitBusinessIntent("查一下杭州今天的天气"));
        assertEquals("taxi_trip", IntentPolicy.explicitBusinessIntent("帮我打车去机场"));
        assertEquals("", IntentPolicy.explicitBusinessIntent("我最近经常点外卖"));
    }

    @Test
    void modelChatIntentIsCorrectedForExplicitFoodOrder() throws Exception {
        IntentRecognizer recognizer = new IntentRecognizer(HttpClient.newHttpClient());
        JsonObject action = new JsonObject();
        action.addProperty("intent", "chat");

        Method normalize = IntentRecognizer.class.getDeclaredMethod(
                "normalizeAction", String.class, String.class, JsonObject.class, IntentContext.class);
        normalize.setAccessible(true);
        normalize.invoke(recognizer, "帮我点外卖", "帮我点外卖", action,
                new IntentContext(false, false, false, false, false));

        assertEquals("food_order", action.get("intent").getAsString());
    }

    @Test
    void missingFoodOrderRestaurantIsRecoveredFromOriginalRequest() throws Exception {
        IntentRecognizer recognizer = new IntentRecognizer(HttpClient.newHttpClient());
        JsonObject action = new JsonObject();
        action.addProperty("intent", "food_order");

        Method normalize = IntentRecognizer.class.getDeclaredMethod(
                "normalizeAction", String.class, String.class, JsonObject.class, IntentContext.class);
        normalize.setAccessible(true);
        String request = "帮我点个外卖，我想吃麦当劳";
        normalize.invoke(recognizer, request, request, action,
                new IntentContext(false, false, false, false, false));

        assertEquals("麦当劳", action.get("food_order_restaurants").getAsString());
        assertEquals("麦当劳", IntentRecognizer.inferFoodOrderRestaurant("帮我点麦当劳外卖"));
        assertEquals("喜茶", IntentRecognizer.inferFoodOrderRestaurant("帮我订一杯喜茶外卖"));
        assertEquals("", IntentRecognizer.inferFoodOrderRestaurant("帮我点外卖"));
    }

    @Test
    void modelFoodOrderRestaurantIsNotOverwritten() throws Exception {
        IntentRecognizer recognizer = new IntentRecognizer(HttpClient.newHttpClient());
        JsonObject action = new JsonObject();
        action.addProperty("intent", "food_order");
        action.addProperty("food_order_restaurants", "麦当劳（万和路店）");

        Method normalize = IntentRecognizer.class.getDeclaredMethod(
                "normalizeAction", String.class, String.class, JsonObject.class, IntentContext.class);
        normalize.setAccessible(true);
        normalize.invoke(recognizer, "帮我点麦当劳外卖", "帮我点麦当劳外卖", action,
                new IntentContext(false, false, false, false, false));

        assertEquals("麦当劳（万和路店）", action.get("food_order_restaurants").getAsString());
    }

    @Test
    void explicitFoodOrderSurvivesModelFailure() {
        IntentRecognizer recognizer = new IntentRecognizer(body -> {
            throw new IllegalStateException("model unavailable");
        });

        IntentPlan plan = recognizer.recognize("user", "帮我点外卖",
                new IntentContext(false, false, false, false, false));

        assertEquals("food_order", plan.actions().getFirst().route().intent());
        assertEquals(MessageMode.COMMAND, plan.messageMode());
    }

    @Test
    void foodOrderRestaurantSurvivesModelFailure() {
        IntentRecognizer recognizer = new IntentRecognizer(body -> {
            throw new IllegalStateException("model unavailable");
        });

        IntentPlan plan = recognizer.recognize("user", "帮我点个外卖，我想吃麦当劳",
                new IntentContext(false, false, false, false, false));

        assertEquals("food_order", plan.actions().getFirst().route().intent());
        assertEquals("麦当劳", plan.actions().getFirst().route().foodOrderRestaurants());
    }

    @Test
    void taxiDestinationSurvivesModelFailure() {
        IntentRecognizer recognizer = new IntentRecognizer(body -> {
            throw new IllegalStateException("model unavailable");
        });

        IntentPlan plan = recognizer.recognize("user", "帮我打车去杭州东站",
                new IntentContext(false, false, false, false, false));

        assertEquals("taxi_trip", plan.actions().getFirst().route().intent());
        assertEquals("杭州东站", plan.actions().getFirst().route().travelDestination());
    }

    @Test
    void businessActionWinsOverModelChatMode() {
        IntentRecognizer recognizer = new IntentRecognizer(body -> """
                {"message_mode":"chat","requirements":[{"id":"r1","text":"帮我点外卖","depends_on":[]}],
                 "actions":[{"requirement_id":"r1","action_text":"帮我点外卖","intent":"food_order"}]}
                """);

        IntentPlan plan = recognizer.recognize("user", "帮我点外卖",
                new IntentContext(false, false, false, false, false));

        assertEquals("food_order", plan.actions().getFirst().route().intent());
        assertEquals(MessageMode.COMMAND, plan.messageMode());
    }

    @Test
    void originalMessageRecoversRestaurantWhenModelShortensActionText() {
        IntentRecognizer recognizer = new IntentRecognizer(body -> """
                {"message_mode":"command",
                 "requirements":[{"id":"r1","text":"帮我点个外卖，我想吃麦当劳","depends_on":[]}],
                 "actions":[{"requirement_id":"r1","action_text":"帮我点个外卖","intent":"food_order"}]}
                """);

        IntentPlan plan = recognizer.recognize("user", "帮我点个外卖，我想吃麦当劳",
                new IntentContext(false, false, false, false, false));

        assertEquals("food_order", plan.actions().getFirst().route().intent());
        assertEquals("麦当劳", plan.actions().getFirst().route().foodOrderRestaurants());
    }

    @Test
    void identifiesNaturalTodoQueriesWithoutTreatingThemAsCreation() {
        String request = "查询我刚才创建的待办事项和提醒安排。";

        assertTrue(IntentPolicy.isExplicitTodoQuery(request));
        assertEquals("list", IntentPolicy.inferTodoAction(request));
        assertFalse(IntentPolicy.isExplicitTodoCreation(request));
        assertFalse(IntentPolicy.isExplicitTodoQuery("新建待办事项并设置提醒安排"));
    }
}
