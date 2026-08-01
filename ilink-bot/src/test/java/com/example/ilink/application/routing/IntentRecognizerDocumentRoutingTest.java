package com.example.ilink.application.routing;

import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentRecognizerDocumentRoutingTest {

    @Test
    void productionUnifiedRouteCorrectsChatToExcelGeneration() {
        IntentRecognizer recognizer = unified("""
                {"message_mode":"command","actions":[{"requirement_id":"r1",
                "action_text":"生成一份Excel项目清单","intent":"chat"}]}
                """);

        IntentResult route = recognize(recognizer, "生成一份Excel项目清单", false);

        assertEquals("generate_file", route.intent());
        assertEquals("xlsx", route.outputFileType());
    }

    @Test
    void unknownDocumentGenerateAliasIsCorrectedToRegisteredCapability() {
        IntentRecognizer recognizer = unified("""
                {"message_mode":"command","actions":[{"requirement_id":"r1",
                "action_text":"导出为Markdown文件","intent":"document_generate"}]}
                """);

        IntentResult route = recognize(recognizer, "导出为Markdown文件", false);

        assertEquals("generate_file", route.intent());
        assertEquals("md", route.outputFileType());
    }

    @Test
    void documentImageInsertionWinsOverImageEdit() {
        IntentRecognizer recognizer = unified("""
                {"message_mode":"command","actions":[{"requirement_id":"r1",
                "action_text":"把刚才的图片插入文档第二页","intent":"image_action","image_action":"edit"}]}
                """);

        IntentResult route = recognize(recognizer, "把刚才的图片插入文档第二页", true);

        assertEquals("document_edit", route.intent());
    }

    @Test
    void routingFailureKeepsExplicitDocumentEdit() {
        IntentRecognizer recognizer = new IntentRecognizer(body -> {
            throw new IllegalStateException("offline");
        });

        IntentResult route = recognize(recognizer, "把文档第三段中的甲方替换成乙方", true);

        assertEquals("document_edit", route.intent());
    }

    @Test
    void presentationGenerationStaysVisibleForContractRejection() {
        IntentRecognizer recognizer = unified("""
                {"message_mode":"command","actions":[{"requirement_id":"r1",
                "action_text":"生成一份PPT","intent":"generate_file","output_file_type":"pptx"}]}
                """);
        IntentResult route = recognize(recognizer, "生成一份PPT", false);

        CapabilityContractValidator.Validation validation = new CapabilityContractValidator().validate(
                "生成一份PPT", route, new CapabilityContractValidator.Context(false, false, false));

        assertEquals("generate_file", route.intent());
        assertEquals(CapabilityContractValidator.Decision.REQUEST_INPUT, validation.decision());
        assertTrue(validation.message().contains("暂不支持从零生成"));
    }

    @Test
    void promptOnlyUsesRegisteredDocumentCapabilityNames() {
        String prompt = new RoutePromptBuilder(CapabilityRegistry.defaults()).buildUnifiedPrompt(
                RoutingContext.minimal(new IntentContext(false, false, false, false, false)));

        assertTrue(prompt.contains("generate_file"));
        assertFalse(prompt.contains("document_generate"));
        assertTrue(prompt.contains("未使用字段必须省略"));
        assertFalse(prompt.contains("\"food_order_restaurants\":\"\""));
        assertTrue(prompt.contains("requirements和actions都只返回一个todo批量动作"));
    }

    @Test
    void routingPromptBoundsLongContext() {
        RoutingContext context = new RoutingContext("p".repeat(300), "m".repeat(2000),
                "s".repeat(2000), List.of(), "k".repeat(3000), "杭州", "杭州",
                ZonedDateTime.now(), new IntentContext(false, false, false, false, false), Map.of());

        String prompt = new RoutePromptBuilder(CapabilityRegistry.defaults()).buildUnifiedPrompt(context);

        assertTrue(prompt.contains("p".repeat(100) + "..."));
        assertTrue(prompt.contains("m".repeat(400) + "..."));
        assertTrue(prompt.contains("s".repeat(400) + "..."));
        assertTrue(prompt.contains("k".repeat(700) + "..."));
    }

    @Test
    void malformedArrayEntryDoesNotHideLaterValidActions() {
        IntentRecognizer recognizer = unified("""
                {"message_mode":"command","requirements":[
                {"id":"r1","text":"查询杭州天气","depends_on":[]},
                {"id":"r2","text":"查询最新新闻","depends_on":[]}],
                "actions":[42,
                {"requirement_id":"r1","action_text":"查询杭州天气","intent":"weather","weather_location":"杭州"},
                {"requirement_id":"r2","action_text":"查询最新新闻","intent":"news_search"}]}
                """);

        IntentPlan plan = recognizer.recognize("user", "查询杭州天气，再查询最新新闻",
                new IntentContext(false, false, false, false, false));

        assertEquals(List.of("weather", "news_search"),
                plan.actions().stream().map(action -> action.route().intent()).toList());
    }

    private static IntentRecognizer unified(String response) {
        return new IntentRecognizer(body -> response);
    }

    private static IntentResult recognize(IntentRecognizer recognizer, String text, boolean hasDocument) {
        IntentPlan plan = recognizer.recognize("user", text,
                new IntentContext(true, true, false, hasDocument, false));
        assertEquals(1, plan.actions().size());
        return plan.actions().getFirst().route();
    }
}
