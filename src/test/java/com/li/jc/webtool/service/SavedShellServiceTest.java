package com.li.jc.webtool.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SavedShellServiceTest {
    @TempDir Path directory;
    private SavedShellService service() { return new SavedShellService(directory.toString()); }
    private SavedShellService.Script script(String name, String content) {
        SavedShellService.Script s = new SavedShellService.Script(); s.setName(name); s.setContent(content); return s;
    }

    @Test void persistsAcrossInstancesAndIsolatesEveryOperationByAccount() {
        SavedShellService service = service();
        String content = "#!/bin/bash\necho '中文'\nprintf '<script>\\n'\n";
        SavedShellService.Script saved = service.save("alice", null, script("  常用检查  ", content));
        assertEquals(content, service().get("alice", saved.getId()).getContent());
        assertEquals("常用检查", service().list("alice").get(0).getName());
        assertNull(service.list("alice").get(0).getContent());
        assertTrue(service.list("bob").isEmpty());
        assertThrows(ResponseStatusException.class, () -> service.get("bob", saved.getId()));
        assertThrows(ResponseStatusException.class, () -> service.save("bob", saved.getId(), script("改名", "rm -rf /tmp/example")));
        assertThrows(ResponseStatusException.class, () -> service.delete("bob", saved.getId()));
        assertThrows(ResponseStatusException.class, () -> service.save("alice", null, script("常用检查", "echo updated")));
        service.save("alice", saved.getId(), script("改名", "echo updated"));
        assertEquals("echo updated", service().get("alice", saved.getId()).getContent());
        service.delete("alice", saved.getId());
        assertTrue(service().list("alice").isEmpty());
    }

    @Test void validatesContentAndDoesNotUseAccountAsPath() throws Exception {
        SavedShellService service = service();
        assertThrows(IllegalArgumentException.class, () -> service.save("a", null, script(" ", "echo hello")));
        assertThrows(IllegalArgumentException.class, () -> service.save("a", null, script("name", " \n")));
        assertThrows(IllegalArgumentException.class, () -> service.save("a", null,
                script("big", new String(new char[256 * 1024]).replace('\0', '中'))));
        assertThrows(ResponseStatusException.class, () -> service.list(null));
        service.save("../../alice", null, script("safe", "echo hello"));
        try (java.util.stream.Stream<Path> paths = Files.list(directory)) {
            assertTrue(paths.allMatch(p -> p.getFileName().toString().matches("[A-Za-z0-9_-]{43}\\.json")));
        }
    }

    @Test void corruptStorageIsPreservedAndNeverOverwritten() throws Exception {
        SavedShellService service = service();
        service.save("alice", null, script("old", "echo hello"));
        Path file;
        try (java.util.stream.Stream<Path> paths = Files.list(directory)) { file = paths.findFirst().get(); }
        byte[] corrupt = "{broken".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(file, corrupt);
        assertThrows(IllegalStateException.class, () -> service.list("alice"));
        assertThrows(IllegalStateException.class, () -> service.save("alice", null, script("new", "echo updated")));
        assertArrayEquals(corrupt, Files.readAllBytes(file));
    }

    @Test void concurrentRequestsDoNotLoseScripts() throws Exception {
        SavedShellService service = service();
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                final int n = i;
                futures.add(executor.submit(() -> service.save("alice", null, script("query" + n, "echo " + n))));
            }
            for (Future<?> f : futures) f.get();
            assertEquals(20, service().list("alice").size());
        } finally { executor.shutdownNow(); }
    }
}
