package com.example.ilink.feature.mail;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QqMailServiceTest {

    @Test
    void briefingShowsOnlyThreeHeadersAndImportantCount() {
        List<MailMessageView> unread = List.of(
                mail("第一封", true),
                mail("第二封", false),
                mail("第三封", true),
                mail("第四封", false));

        String result = QqMailService.formatBriefing(unread);

        assertTrue(result.contains("邮箱里有4封近期未读邮件，其中2封可能比较重要"));
        assertTrue(result.contains("第一封（发件人）"));
        assertTrue(result.contains("第三封（发件人）"));
        assertFalse(result.contains("第四封"));
    }

    private MailMessageView mail(String subject, boolean important) {
        return new MailMessageView("发件人", subject, LocalDateTime.now(), true, important, "");
    }
}
