package com.example.ilink.adapter.inbound.http;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebUiResourceTest {

    @Test
    void webResourcesContainReadableChineseLabels() throws Exception {
        String html = resource("/templates/web-chat.html");
        String script = resource("/static/js/web-chat.js");
        String shell = resource("/static/js/web-shell.js");

        assertFalse(html.contains("???"));
        assertFalse(script.contains("???"));
        assertTrue(html.contains("新建会话"));
        assertTrue(html.contains("上传图片和 PDF 文件"));
        assertTrue(html.contains("停止生成"));
        assertTrue(html.contains("继续任务"));
        assertTrue(html.contains("修改并重新运行"));
        assertTrue(html.contains("重命名"));
        assertTrue(html.contains("删除"));
        assertTrue(script.contains("seenEventIds"));
        assertTrue(script.contains("settleActivity"));
        assertTrue(script.contains("task.state !== \"completed\""));
        assertTrue(html.contains("activity-backdrop"));
        assertTrue(script.contains("/api/web/cancel"));
        assertTrue(script.contains("/api/web/tasks/"));
        assertTrue(script.contains("/rerun"));
        assertTrue(script.contains("connectEvents();"));
        assertTrue(script.contains("attachment-preview"));
        assertTrue(script.contains("message-attachment-image"));
        assertTrue(script.contains("link-card-description"));
        assertTrue(script.contains("noopener noreferrer"));
        assertTrue(script.contains("validWebUrl"));
        assertTrue(script.contains("tasksBySession"));
        assertTrue(script.contains("activitiesBySession"));
        assertTrue(script.contains("renderActivities()"));
        assertTrue(script.contains("renderLinkedText"));
        assertTrue(script.contains("message-inline-link"));
        assertTrue(script.contains("message.kind === \"image\""));
        assertTrue(script.contains("addArtifactMessage(message.content, message)"));
        assertTrue(html.contains("工作空间文件"));
        assertTrue(html.contains("微信 Bot"));
        assertTrue(shell.contains("/api/web/wechat"));
        assertTrue(shell.contains("status.ready"));
        assertTrue(shell.contains("请先在手机微信中向 Bot 发送一条消息"));
        assertTrue(shell.contains("/api/web/workspace"));
        assertTrue(shell.contains("navigation.planUrl"));
        assertTrue(shell.contains("navigation.sessionsUrl"));
        assertFalse(html.contains("data-view=\"plan\""));
        assertFalse(html.contains("data-view=\"sessions\""));
        assertFalse(html.contains("data-view=\"login\""));
    }

    private String resource(String path) throws Exception {
        try (InputStream input = WebUiResourceTest.class.getResourceAsStream(path)) {
            assertNotNull(input, path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
