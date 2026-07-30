package com.example.ilink.adapter.inbound.wechat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginQrPageTest {

    @Test
    void waitingStateReusesExistingQrTemplateUntilSdkProvidesCode() throws Exception {
        LoginQrPage page = new LoginQrPage();
        String waiting = page.currentPageHtml();

        assertTrue(waiting.contains("WeChat Bot Auth Console"));
        assertTrue(waiting.contains("QR CODE LOADING"));
        assertTrue(waiting.contains("登录二维码正在生成"));

        page.render("data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==");
        String rendered = page.currentPageHtml();
        assertTrue(rendered.contains("WECHAT ROBOT AUTHENTICATION"));
        assertFalse(rendered.contains("QR CODE LOADING"));
        page.cleanup();
    }
}
