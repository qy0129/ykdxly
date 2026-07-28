package com.example.ilink.application.routing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentRecognizerBilibiliTest {

    @Test
    void recognizesSeriesRequest() {
        String text = "我想看剧";

        assertTrue(IntentRecognizer.isBilibiliMediaRequest(text));
        assertEquals("series", IntentRecognizer.inferBilibiliCategory(text));
        assertEquals("热门电视剧", IntentRecognizer.inferBilibiliQuery(text, "series"));
    }

    @Test
    void recognizesMusicRequest() {
        String text = "我想听周杰伦的歌";

        assertTrue(IntentRecognizer.isBilibiliMediaRequest(text));
        assertEquals("music", IntentRecognizer.inferBilibiliCategory(text));
        assertEquals("周杰伦 歌曲", IntentRecognizer.inferBilibiliQuery(text, "music"));
    }

    @Test
    void recognizesLearningPlanAndBuildsCourseQuery() {
        String text = "我想学习一下线性代数，预计三十天左右，帮我完成一份计划";

        assertTrue(IntentRecognizer.isLearningPlanRequest(text));
        assertEquals("线性代数 系统课程", IntentRecognizer.inferBilibiliQuery("学习线性代数", "study"));
    }

    @Test
    void recognizesMediaKnowledgeAndEmailQueries() {
        assertTrue(IntentRecognizer.isMediaKnowledgeRequest("查一下海贼王动漫资料"));
        assertEquals("anime", IntentRecognizer.inferMediaCategory("查一下海贼王动漫资料"));
        assertEquals("海贼王", IntentRecognizer.inferMediaQuery("查一下海贼王动漫资料"));

        assertTrue(IntentRecognizer.isEmailRequest("我有什么未读邮件"));
        assertEquals("unread", IntentRecognizer.inferEmailAction("我有什么未读邮件"));
        assertEquals("important", IntentRecognizer.inferEmailAction("有没有重要邮件"));
    }
}
