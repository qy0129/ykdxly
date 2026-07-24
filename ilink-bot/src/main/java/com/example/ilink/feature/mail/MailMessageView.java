package com.example.ilink.feature.mail;

import java.time.LocalDateTime;

/** QQ 邮箱只读查询结果。 */
public record MailMessageView(
        String from,
        String subject,
        LocalDateTime sentAt,
        boolean unread,
        boolean important,
        String bodySnippet) {
}
