package com.example.ilink.feature.visual;

import com.example.ilink.model.PlanTask;
import com.example.ilink.model.TaskPlan;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisualFeaturesTest {

    @Test
    void loginBriefingAlwaysBuildsFourSwipeCards() {
        String briefing = "早上好，欢迎回来。\n\n今天晴，当前温度25度。出行注意防晒。\n\n"
                + "今天有两项日历安排。\n\n邮箱里有一封未读邮件。";
        List<VisualCard> deck = new VisualCardFactory().loginDeck(briefing, "https://example.com/plan");

        assertEquals(4, deck.size());
        assertEquals("https://example.com/plan", deck.getLast().qrUrl());
    }

    @Test
    void exportsAValidExcelPlan() throws Exception {
        TaskPlan plan = new TaskPlan("p1", "学习线性代数", "2026-08-24", "每天一小时", "2026-07-24",
                List.of(new PlanTask("t1", "矩阵基础", "完成第一章", 60,
                        "high", "2026-07-24", "pending")));
        byte[] bytes = new PlanSpreadsheetService().export(plan);

        assertTrue(bytes.length > 1_000);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertEquals("任务", workbook.getSheetAt(0).getRow(0).getCell(1).getStringCellValue());
            assertEquals("矩阵基础", workbook.getSheetAt(0).getRow(1).getCell(1).getStringCellValue());
        }
    }

    @Test
    void quizMaintainsAndClearsConversationState() {
        FunInteractionService service = new FunInteractionService();
        FunInteractionService.Response question = service.handle("u1", "每日答题");

        assertNotNull(question);
        assertTrue(service.hasPending("u1"));
        FunInteractionService.Response answer = service.handle("u1", "A");
        assertNotNull(answer);
        assertFalse(service.hasPending("u1"));
    }

    @Test
    void storyRejectsUnknownChoiceWithoutLosingState() {
        FunInteractionService service = new FunInteractionService();
        service.handle("u1", "分支故事");
        FunInteractionService.Response response = service.handle("u1", "三");

        assertTrue(response.text().contains("1 或 2"));
        assertTrue(service.hasPending("u1"));
    }
}
