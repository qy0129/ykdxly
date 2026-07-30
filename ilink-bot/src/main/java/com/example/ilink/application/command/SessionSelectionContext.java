package com.example.ilink.application.command;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Short-lived context that makes bare numbers unambiguous session choices. */
final class SessionSelectionContext {

    private static final long DEFAULT_TTL_MILLIS = 5 * 60 * 1000L;

    private final Map<String, PendingSelection> pendingSelections = new ConcurrentHashMap<>();
    private final LongSupplier clock;
    private final long ttlMillis;

    SessionSelectionContext() {
        this(System::currentTimeMillis, DEFAULT_TTL_MILLIS);
    }

    SessionSelectionContext(LongSupplier clock, long ttlMillis) {
        this.clock = clock;
        this.ttlMillis = ttlMillis;
    }

    void open(String userId, String sourceSessionId) {
        pendingSelections.put(userId,
                new PendingSelection(sourceSessionId, clock.getAsLong() + ttlMillis));
    }

    boolean permitsBareNumber(String userId, String currentSessionId,
                              boolean hasPendingBusinessInteraction) {
        if (hasPendingBusinessInteraction) {
            clear(userId);
            return false;
        }
        PendingSelection pending = pendingSelections.get(userId);
        if (pending == null) return false;
        if (pending.expiresAtMillis() <= clock.getAsLong()
                || !pending.sourceSessionId().equals(currentSessionId)) {
            clear(userId);
            return false;
        }
        return true;
    }

    void clear(String userId) {
        pendingSelections.remove(userId);
    }

    private record PendingSelection(String sourceSessionId, long expiresAtMillis) {
    }
}
