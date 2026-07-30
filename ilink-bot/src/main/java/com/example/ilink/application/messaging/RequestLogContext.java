package com.example.ilink.application.messaging;

import java.util.function.Consumer;

/** Correlates request logs across the synchronous message-processing call chain. */
public final class RequestLogContext {

    private static final int PREVIEW_LIMIT = 120;
    private static final int CORRELATION_ID_LIMIT = 8;
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    private RequestLogContext() {
    }

    public static Scope open(ChannelType channel, String userId, String sessionId, String requestId) {
        return open(channel, userId, sessionId, requestId, null);
    }

    public static Scope open(ChannelType channel, String userId, String sessionId, String requestId,
                             Consumer<AgentEvent> eventSink) {
        Context previous = CURRENT.get();
        CURRENT.set(new Context(channel, clean(userId), clean(sessionId), clean(requestId), eventSink));
        return () -> restore(previous);
    }

    /** Keeps an inbound adapter's richer context, such as the Web request ID, when already present. */
    public static Scope ensure(AgentContext context, String sessionId) {
        if (CURRENT.get() != null) return () -> { };
        return open(context.channel(), context.principalId(), sessionId, "");
    }

    public static String prefix(String event) {
        Context context = CURRENT.get();
        if (context == null) return "[SYS][" + cleanEvent(event) + "]";
        return prefix(context.channel(), event, context.userId(), context.sessionId(), context.requestId());
    }

    public static String prefix(ChannelType channel, String event, String userId,
                                String sessionId, String requestId) {
        StringBuilder value = new StringBuilder()
                .append('[').append(channel == ChannelType.WEB ? "W" : "WX").append(']');
        String request = shortId(requestId);
        if (!request.isBlank()) value.append("[r=").append(request).append(']');
        value.append('[').append(cleanEvent(event)).append(']');
        return value.toString();
    }

    /** Uses the active request context when it belongs to this channel and recipient. */
    public static String prefixFor(ChannelType channel, String event, String userId) {
        Context context = CURRENT.get();
        if (context != null && context.channel() == channel && context.userId().equals(clean(userId))) {
            return prefix(event);
        }
        return prefix(channel, event, userId, "", "");
    }

    public static String preview(String value) {
        if (value == null || value.isBlank()) return "<empty>";
        String normalized = value.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").trim();
        if (normalized.length() > PREVIEW_LIMIT) normalized = normalized.substring(0, PREVIEW_LIMIT) + "...";
        return '"' + normalized.replace("\"", "\\\"") + '"';
    }

    public static String error(Throwable error) {
        if (error == null) return "unknown";
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null || message.isBlank()
                ? "" : ": " + preview(message));
    }

    /** Publishes a public progress summary when the inbound adapter supplied an event sink. */
    public static void publish(AgentEvent event) {
        Context context = CURRENT.get();
        if (context == null || context.eventSink() == null || event == null) return;
        try {
            context.eventSink().accept(event);
        } catch (RuntimeException ignored) {
            // Progress reporting must never break the business operation.
        }
    }

    private static String shortId(String value) {
        String cleaned = clean(value);
        return cleaned.length() <= CORRELATION_ID_LIMIT
                ? cleaned : cleaned.substring(0, CORRELATION_ID_LIMIT);
    }

    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n\\t ]+", " ").trim();
    }

    private static String cleanEvent(String event) {
        String cleaned = clean(event);
        return cleaned.isBlank() ? "事件" : cleaned;
    }

    private static void restore(Context previous) {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }

    private record Context(ChannelType channel, String userId, String sessionId, String requestId,
                           Consumer<AgentEvent> eventSink) {
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
