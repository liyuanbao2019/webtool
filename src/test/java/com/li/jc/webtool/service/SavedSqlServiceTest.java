package com.li.jc.webtool.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SavedSqlServiceTest {
    @TempDir Path directory;
    private SavedSqlService service() { return new SavedSqlService(directory.toString()); }
    private SavedSqlService.Script script(String name, String sql) {
        SavedSqlService.Script s = new SavedSqlService.Script(); s.setName(name); s.setSql(sql); return s;
    }

    @Test void persistsAcrossInstancesAndIsolatesEveryOperationByAccount() {
        SavedSqlService service = service();
        String sql = "-- 常用查询\nselect '中文', '<script>' from dual;\n";
        SavedSqlService.Script saved = service.save("alice", null, script("  常用查询  ", sql));
        assertEquals(sql, service().get("alice", saved.getId()).getSql());
        assertEquals("常用查询", service().list("alice").get(0).getName());
        assertNull(service.list("alice").get(0).getSql());
        assertTrue(service.list("bob").isEmpty());
        assertThrows(ResponseStatusException.class, () -> service.get("bob", saved.getId()));
        assertThrows(ResponseStatusException.class, () -> service.save("bob", saved.getId(), script("改名", "delete from t")));
        assertThrows(ResponseStatusException.class, () -> service.delete("bob", saved.getId()));
        assertThrows(ResponseStatusException.class, () -> service.save("alice", null, script("常用查询", "select 2")));
        service.save("alice", saved.getId(), script("改名", "select 2"));
        assertEquals("select 2", service().get("alice", saved.getId()).getSql());
        service.delete("alice", saved.getId());
        assertTrue(service().list("alice").isEmpty());
    }

    @Test void validatesContentAndDoesNotUseAccountAsPath() throws Exception {
        SavedSqlService service = service();
        assertThrows(IllegalArgumentException.class, () -> service.save("a", null, script(" ", "select 1")));
        assertThrows(IllegalArgumentException.class, () -> service.save("a", null, script("name", " \n")));
        assertThrows(IllegalArgumentException.class, () -> service.save("a", null,
                script("big", new String(new char[1024 * 1024]).replace('\0', '中'))));
        assertThrows(ResponseStatusException.class, () -> service.list(null));
        service.save("../../alice", null, script("safe", "select 1"));
        try (java.util.stream.Stream<Path> paths = Files.list(directory)) {
            assertTrue(paths.allMatch(p -> p.getFileName().toString().matches("[A-Za-z0-9_-]{43}\\.json")));
        }
    }

    @Test void corruptStorageIsPreservedAndNeverOverwritten() throws Exception {
        SavedSqlService service = service();
        service.save("alice", null, script("old", "select 1"));
        Path file;
        try (java.util.stream.Stream<Path> paths = Files.list(directory)) { file = paths.findFirst().get(); }
        byte[] corrupt = "{broken".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(file, corrupt);
        assertThrows(IllegalStateException.class, () -> service.list("alice"));
        assertThrows(IllegalStateException.class, () -> service.save("alice", null, script("new", "select 2")));
        assertArrayEquals(corrupt, Files.readAllBytes(file));
    }

    @Test void concurrentRequestsDoNotLoseScripts() throws Exception {
        SavedSqlService service = service();
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                final int n = i;
                futures.add(executor.submit(() -> service.save("alice", null, script("query" + n, "select " + n))));
            }
            for (Future<?> f : futures) f.get();
            assertEquals(20, service().list("alice").size());
        } finally { executor.shutdownNow(); }
    }
}
