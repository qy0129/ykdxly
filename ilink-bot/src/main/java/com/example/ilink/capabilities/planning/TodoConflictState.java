package com.example.ilink.capabilities.planning;

import java.util.List;
import java.util.Map;

/** 待办时间冲突的可持久化多轮状态；时间使用 ISO 字符串避免 Gson 反射 Java 时间类型。 */
public record TodoConflictState(
        List<DraftState> drafts,
        int reminderMinutes,
        boolean supervisionEnabled,
        String supervisionCadence,
        String stage,
        List<ItemRef> choices,
        List<ItemRef> remaining,
        ItemRef current,
        Map<String, String> existingDueAtUpdates,
        long expiresAtMillis
) {
    public static final String AWAITING_KEEP = "awaiting_keep";
    public static final String AWAITING_NEW_TIME = "awaiting_new_time";

    public TodoConflictState {
        drafts = drafts == null ? List.of() : List.copyOf(drafts);
        supervisionCadence = supervisionCadence == null ? "" : supervisionCadence.trim();
        stage = stage == null ? "" : stage.trim();
        choices = choices == null ? List.of() : List.copyOf(choices);
        remaining = remaining == null ? List.of() : List.copyOf(remaining);
        existingDueAtUpdates = existingDueAtUpdates == null ? Map.of() : Map.copyOf(existingDueAtUpdates);
    }

    public TodoConflictState awaitingKeep(List<ItemRef> conflictChoices) {
        return new TodoConflictState(drafts, reminderMinutes, supervisionEnabled, supervisionCadence,
                AWAITING_KEEP, conflictChoices, List.of(), null, existingDueAtUpdates, expiresAtMillis);
    }

    public TodoConflictState awaitingNewTime(List<ItemRef> queue) {
        ItemRef next = queue.isEmpty() ? null : queue.getFirst();
        List<ItemRef> rest = queue.size() <= 1 ? List.of() : queue.subList(1, queue.size());
        return new TodoConflictState(drafts, reminderMinutes, supervisionEnabled, supervisionCadence,
                AWAITING_NEW_TIME, List.of(), rest, next, existingDueAtUpdates, expiresAtMillis);
    }

    public TodoConflictState withSchedule(List<DraftState> newDrafts, Map<String, String> updates) {
        return new TodoConflictState(newDrafts, reminderMinutes, supervisionEnabled, supervisionCadence,
                stage, choices, remaining, current, updates, expiresAtMillis);
    }

    public TodoConflictState withExpiresAt(long expiresAt) {
        return new TodoConflictState(drafts, reminderMinutes, supervisionEnabled, supervisionCadence,
                stage, choices, remaining, current, existingDueAtUpdates, expiresAt);
    }

    public record DraftState(String clientId, String sourceText, String title, String dueAt) {
        public DraftState {
            clientId = clientId == null ? "" : clientId.trim();
            sourceText = sourceText == null ? "" : sourceText.trim();
            title = title == null ? "" : title.trim();
            dueAt = dueAt == null ? "" : dueAt.trim();
        }
    }

    public record ItemRef(String kind, String reference, String title) {
        public ItemRef {
            kind = kind == null ? "" : kind.trim();
            reference = reference == null ? "" : reference.trim();
            title = title == null ? "" : title.trim();
        }
    }
}
