package com.example.ilink.capabilities.planning;

import java.time.LocalDateTime;

/** 从一条自然语言消息中拆出的单个待办草稿。 */
public record TodoDraft(
        String clientId,
        String sourceText,
        String title,
        LocalDateTime dueAt
) {
    public TodoDraft {
        clientId = clientId == null ? "" : clientId.trim();
        sourceText = sourceText == null ? "" : sourceText.trim();
        title = title == null ? "" : title.trim();
    }
}
