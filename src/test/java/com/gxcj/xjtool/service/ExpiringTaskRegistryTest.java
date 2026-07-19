package com.gxcj.xjtool.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExpiringTaskRegistryTest {

    @Test
    void expiresOnlyCompletedTasks() {
        AtomicLong now = new AtomicLong(1_000L);
        ExpiringTaskRegistry<String> registry = new ExpiringTaskRegistry<>(100L, 10, now::get);
        registry.register("active", "active-task");
        registry.register("done", "completed-task");
        registry.markCompleted("done");

        now.addAndGet(100L);

        assertNotNull(registry.get("active"));
        assertNull(registry.get("done"));
    }

    @Test
    void evictsOldestCompletedTaskAtCapacityWithoutRemovingActiveTask() {
        AtomicLong now = new AtomicLong(1_000L);
        ExpiringTaskRegistry<String> registry = new ExpiringTaskRegistry<>(10_000L, 2, now::get);
        registry.register("active", "active-task");
        registry.register("done", "completed-task");
        registry.markCompleted("done");

        registry.register("new", "new-task");

        assertEquals("active-task", registry.get("active"));
        assertNull(registry.get("done"));
        assertEquals("new-task", registry.get("new"));
    }

    @Test
    void rejectsNewTaskWhenCapacityContainsOnlyActiveTasks() {
        AtomicLong now = new AtomicLong(1_000L);
        ExpiringTaskRegistry<String> registry = new ExpiringTaskRegistry<>(100L, 1, now::get);
        registry.register("active", "active-task");

        assertThrows(IllegalStateException.class, () -> registry.register("another", "another-task"));
        assertEquals(1, registry.size());
    }
}
