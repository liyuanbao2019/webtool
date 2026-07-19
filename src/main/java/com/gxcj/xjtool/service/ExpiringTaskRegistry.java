package com.gxcj.xjtool.service;

import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;

/** Retains completed asynchronous tasks for a bounded reconnect window. */
public class ExpiringTaskRegistry<T> {

    private final ConcurrentMap<String, Entry<T>> entries = new ConcurrentHashMap<>();
    private final long completedTaskTtlMillis;
    private final int maximumSize;
    private final LongSupplier clock;

    public ExpiringTaskRegistry(long completedTaskTtlMillis, int maximumSize) {
        this(completedTaskTtlMillis, maximumSize, System::currentTimeMillis);
    }

    ExpiringTaskRegistry(long completedTaskTtlMillis, int maximumSize, LongSupplier clock) {
        if (completedTaskTtlMillis < 0) {
            throw new IllegalArgumentException("completedTaskTtlMillis must not be negative");
        }
        if (maximumSize < 1) {
            throw new IllegalArgumentException("maximumSize must be positive");
        }
        this.completedTaskTtlMillis = completedTaskTtlMillis;
        this.maximumSize = maximumSize;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized void register(String taskId, T task) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(task, "task");
        long now = clock.getAsLong();
        removeExpired(now);
        if (!entries.containsKey(taskId)) {
            makeRoomForNewTask();
        }
        entries.put(taskId, new Entry<>(task));
    }

    public synchronized T get(String taskId) {
        removeExpired(clock.getAsLong());
        Entry<T> entry = entries.get(taskId);
        return entry == null ? null : entry.task;
    }

    public synchronized void markCompleted(String taskId) {
        Entry<T> entry = entries.get(taskId);
        if (entry != null && entry.completedAt < 0) {
            entry.completedAt = clock.getAsLong();
        }
        removeExpired(clock.getAsLong());
    }

    public synchronized T remove(String taskId) {
        Entry<T> entry = entries.remove(taskId);
        return entry == null ? null : entry.task;
    }

    int size() {
        return entries.size();
    }

    private void makeRoomForNewTask() {
        while (entries.size() >= maximumSize) {
            Map.Entry<String, Entry<T>> oldestCompleted = entries.entrySet().stream()
                    .filter(entry -> entry.getValue().completedAt >= 0)
                    .min(Comparator.comparingLong(entry -> entry.getValue().completedAt))
                    .orElse(null);
            if (oldestCompleted == null) {
                throw new IllegalStateException("too many active tasks");
            }
            entries.remove(oldestCompleted.getKey(), oldestCompleted.getValue());
        }
    }

    private void removeExpired(long now) {
        entries.entrySet().removeIf(entry -> {
            long completedAt = entry.getValue().completedAt;
            return completedAt >= 0 && now - completedAt >= completedTaskTtlMillis;
        });
    }

    private static final class Entry<T> {
        private final T task;
        private volatile long completedAt = -1L;

        private Entry(T task) {
            this.task = task;
        }
    }
}
