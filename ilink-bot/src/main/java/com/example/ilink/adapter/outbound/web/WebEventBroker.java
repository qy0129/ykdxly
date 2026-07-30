package com.example.ilink.adapter.outbound.web;

import com.example.ilink.application.messaging.AgentEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** In-memory event fan-out with a small replay window for SSE reconnects. */
public final class WebEventBroker {

    private static final int HISTORY_LIMIT = 200;
    private static final int SUBSCRIBER_QUEUE_LIMIT = 256;

    private final AtomicLong sequence = new AtomicLong();
    private final ConcurrentHashMap<String, UserStream> streams = new ConcurrentHashMap<>();

    public Envelope publish(String recipientId, AgentEvent event) {
        UserStream stream = streams.computeIfAbsent(recipientId, ignored -> new UserStream());
        Envelope envelope = new Envelope(sequence.incrementAndGet(), event);
        synchronized (stream.history) {
            stream.history.addLast(envelope);
            while (stream.history.size() > HISTORY_LIMIT) stream.history.removeFirst();
        }
        stream.subscribers.forEach(subscription -> subscription.offer(envelope));
        return envelope;
    }

    public Subscription subscribe(String recipientId, long afterEventId) {
        UserStream stream = streams.computeIfAbsent(recipientId, ignored -> new UserStream());
        Subscription subscription = new Subscription(stream);
        synchronized (stream.history) {
            stream.history.stream()
                    .filter(event -> event.id() > afterEventId)
                    .forEach(subscription::offer);
            stream.subscribers.add(subscription);
        }
        return subscription;
    }

    public List<Envelope> history(String recipientId, long afterEventId) {
        UserStream stream = streams.get(recipientId);
        if (stream == null) return List.of();
        synchronized (stream.history) {
            List<Envelope> events = new ArrayList<>();
            stream.history.stream()
                    .filter(event -> event.id() > afterEventId)
                    .forEach(events::add);
            return List.copyOf(events);
        }
    }

    public void clear(String recipientId) {
        UserStream stream = streams.remove(recipientId);
        if (stream != null) stream.subscribers.forEach(Subscription::close);
    }

    public record Envelope(long id, AgentEvent event) {
    }

    private static final class UserStream {
        private final Deque<Envelope> history = new ArrayDeque<>();
        private final CopyOnWriteArrayList<Subscription> subscribers = new CopyOnWriteArrayList<>();
    }

    public static final class Subscription implements AutoCloseable {
        private static final Envelope CLOSED = new Envelope(-1L, null);
        private final UserStream owner;
        private final BlockingQueue<Envelope> events = new LinkedBlockingQueue<>(SUBSCRIBER_QUEUE_LIMIT);
        private volatile boolean closed;

        private Subscription(UserStream owner) {
            this.owner = owner;
        }

        public Envelope poll(long timeout, TimeUnit unit) throws InterruptedException {
            Envelope envelope = events.poll(timeout, unit);
            return envelope == CLOSED ? null : envelope;
        }

        public boolean isClosed() {
            return closed;
        }

        private void offer(Envelope event) {
            if (closed) return;
            if (!events.offer(event)) {
                events.poll();
                events.offer(event);
            }
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            owner.subscribers.remove(this);
            events.clear();
            events.offer(CLOSED);
        }
    }
}
