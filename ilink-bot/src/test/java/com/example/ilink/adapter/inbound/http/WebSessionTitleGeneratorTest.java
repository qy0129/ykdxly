package com.example.ilink.adapter.inbound.http;

import com.example.ilink.application.messaging.MessagePart;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSessionTitleGeneratorTest {

    @Test
    void createsCompactTopicTitleInsteadOfCopyingTheWholePrompt() {
        String prompt = "请帮我修改 Web Bot 前端界面，增加圆角、阴影和透明感，并修复完成后仍旋转的问题。";

        String title = WebSessionTitleGenerator.generate(
                List.of(new MessagePart.Text(prompt)), "已经完成界面和任务状态修改。 ");

        assertTrue(title.codePointCount(0, title.length()) <= 20);
        assertTrue(title.contains("Web Bot"));
        assertFalse(title.equals(prompt));
        assertTrue(title.endsWith("修复") || title.endsWith("优化"));
    }

    @Test
    void usesReplyAsFallbackForVagueContinuation() {
        String title = WebSessionTitleGenerator.generate(
                List.of(new MessagePart.Text("继续")), "已完成 PDF 报告导出故障的排查和修复。 ");

        assertTrue(title.contains("PDF"));
        assertTrue(title.codePointCount(0, title.length()) <= 20);
    }

    @Test
    void removesInstructionalPrefixAndUsesTheUserTopicForAction() {
        String title = WebSessionTitleGenerator.generate(
                List.of(new MessagePart.Text("请用一句话说明 Java 21 虚拟线程的主要用途。")),
                "Java 21 通过虚拟线程实现高并发阻塞式任务。 ");

        assertFalse(title.startsWith("请"));
        assertTrue(title.contains("虚拟线程"));
        assertTrue(title.endsWith("分析"));
    }
}
