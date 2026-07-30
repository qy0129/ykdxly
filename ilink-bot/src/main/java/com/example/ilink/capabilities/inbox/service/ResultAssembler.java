package com.example.ilink.capabilities.inbox.service;

import com.example.ilink.capabilities.inbox.model.ExtractionResult;
import com.example.ilink.capabilities.inbox.model.MessageResult;
import com.example.ilink.capabilities.inbox.model.MessageSummary;
import com.example.ilink.capabilities.inbox.model.ProcessedMessage;

/** 组装 Inbox 对外结果。 */
public final class ResultAssembler {
    private long total;

    public MessageResult assemble(ProcessedMessage message, MessageSummary summary,
                                  ExtractionResult extraction) {
        total++;
        return new MessageResult(message.messageId(), true, false, "", summary, extraction);
    }

    public MessageResult assembleDuplicate(String messageId, String reason) {
        total++;
        return MessageResult.duplicate(messageId, reason);
    }

    public AssemblerStats getStats() { return new AssemblerStats(total); }
    public record AssemblerStats(long total) { }
}
