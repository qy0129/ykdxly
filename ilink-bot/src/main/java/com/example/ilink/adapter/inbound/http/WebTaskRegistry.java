package com.example.ilink.adapter.inbound.http;

import com.example.ilink.application.messaging.MessagePart;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks resumable Web requests independently for each conversation. */
final class WebTaskRegistry {

    enum State {
        QUEUED, RUNNING, PAUSED, COMPLETED, FAILED
    }

    private final ConcurrentHashMap<String, Task> tasks = new ConcurrentHashMap<>();

    Task create(String userId, String sessionId, List<MessagePart> parts) {
        Task task = new Task(UUID.randomUUID().toString(), userId, sessionId, List.copyOf(parts));
        tasks.put(task.id(), task);
        return task;
    }

    Optional<Task> findOwned(String requestId, String userId) {
        Task task = tasks.get(requestId);
        return task != null && task.userId().equals(userId) ? Optional.of(task) : Optional.empty();
    }

    List<Snapshot> snapshots(String userId) {
        return tasks.values().stream()
                .filter(task -> task.userId().equals(userId))
                .sorted(Comparator.comparingLong(Task::createdAtEpochMs))
                .map(Task::snapshot)
                .toList();
    }

    List<Task> pauseSession(String userId, String sessionId) {
        List<Task> paused = new ArrayList<>();
        tasks.values().stream()
                .filter(task -> task.userId().equals(userId) && task.sessionId().equals(sessionId))
                .filter(task -> task.state() == State.QUEUED || task.state() == State.RUNNING)
                .forEach(task -> {
                    task.pause();
                    paused.add(task);
                });
        return List.copyOf(paused);
    }

    static final class Task {
        private final String id;
        private final String userId;
        private final String sessionId;
        private final List<MessagePart> parts;
        private final long createdAtEpochMs = System.currentTimeMillis();
        private State state = State.QUEUED;
        private long accumulatedElapsedMs;
        private long runningSinceNanos;
        private long executionGeneration = 1L;
        private int attempt;
        private String detail = "等待处理";

        private Task(String id, String userId, String sessionId, List<MessagePart> parts) {
            this.id = id;
            this.userId = userId;
            this.sessionId = sessionId;
            this.parts = parts;
        }

        String id() { return id; }

        String userId() { return userId; }

        String sessionId() { return sessionId; }

        List<MessagePart> parts() { return parts; }

        long createdAtEpochMs() { return createdAtEpochMs; }

        synchronized State state() { return state; }

        synchronized long start() {
            if (state != State.QUEUED) return -1L;
            state = State.RUNNING;
            runningSinceNanos = System.nanoTime();
            attempt++;
            detail = attempt == 1 ? "正在处理" : "正在继续任务";
            return executionGeneration;
        }

        synchronized boolean resume() {
            if (state != State.PAUSED) return false;
            state = State.QUEUED;
            executionGeneration++;
            detail = "等待继续";
            return true;
        }

        synchronized void pause() {
            if (state != State.QUEUED && state != State.RUNNING) return;
            stopClock();
            state = State.PAUSED;
            detail = "任务已暂停，可继续";
        }

        synchronized void complete(long generation) {
            if (generation != executionGeneration || state == State.PAUSED) return;
            stopClock();
            state = State.COMPLETED;
            detail = "处理完成";
        }

        synchronized void fail(long generation, String message) {
            if (generation != executionGeneration || state == State.PAUSED) return;
            stopClock();
            state = State.FAILED;
            detail = message == null || message.isBlank() ? "处理失败" : message;
        }

        synchronized boolean isCurrent(long generation) {
            return generation == executionGeneration;
        }

        synchronized Snapshot snapshot() {
            return new Snapshot(id, sessionId, state.name().toLowerCase(), createdAtEpochMs,
                    elapsedMs(), attempt, detail);
        }

        private long elapsedMs() {
            return accumulatedElapsedMs + (state == State.RUNNING
                    ? (System.nanoTime() - runningSinceNanos) / 1_000_000L : 0L);
        }

        private void stopClock() {
            if (state == State.RUNNING && runningSinceNanos != 0L) {
                accumulatedElapsedMs += (System.nanoTime() - runningSinceNanos) / 1_000_000L;
                runningSinceNanos = 0L;
            }
        }
    }

    record Snapshot(String requestId, String sessionId, String state, long createdAt,
                    long elapsedMs, int attempt, String detail) {
    }
}
