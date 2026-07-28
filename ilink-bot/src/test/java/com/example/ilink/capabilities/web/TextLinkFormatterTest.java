package com.example.ilink.capabilities.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextLinkFormatterTest {

    @Test
    void convertsRawUrlToUnifiedClickableLabel() {
        assertEquals("手机查看：[点击此链接跳转](https://uri.amap.com/marker?id=1)",
                TextLinkFormatter.format("手机查看：https://uri.amap.com/marker?id=1"));
    }

    @Test
    void replacesExistingMarkdownLabelWithoutChangingTarget() {
        assertEquals("[点击此链接跳转](https://xgw.qq.com/)",
                TextLinkFormatter.format("[QQ邮箱APP官网](https://xgw.qq.com/)"));
    }

    @Test
    void convertsEveryLinkAndPreservesPunctuation() {
        assertEquals("打开[点击此链接跳转](https://a.example.com)，或者[点击此链接跳转](https://b.example.com)。",
                TextLinkFormatter.format("打开https://a.example.com，或者https://b.example.com。"));
    }
}
