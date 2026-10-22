package com.li.jc.webtool.service;

import com.li.jc.webtool.config.ServerConfig;
import com.li.jc.webtool.model.CommonWebsite;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class CommonWebsiteServiceTest {
    @TempDir Path directory;

    private CommonWebsite site(String name, String url) {
        CommonWebsite site = new CommonWebsite(); site.setName(name); site.setUrl(url); return site;
    }

    private CommonWebsiteService service(Path file) {
        ServerConfig defaults = new ServerConfig();
        defaults.setCommonWebsites(Collections.singletonList(site("Original", "https://example.com")));
        CommonWebsiteService service = new CommonWebsiteService(defaults, file.toString()); service.load(); return service;
    }

    @Test void preservesOrderDisabledSitesAndEmptyListAcrossRestarts() {
        Path file = directory.resolve("data/websites.json");
        CommonWebsiteService service = service(file);
        assertFalse(Files.exists(file), "Reading YAML defaults should not write files");
        CommonWebsiteService.Settings request = new CommonWebsiteService.Settings();
        request.setEnabled(false);
        request.setWebsites(Arrays.asList(site(" B ", "intranet:8080/path?q=1"), site("A", "https://example.com/a")));
        CommonWebsiteService.Settings saved = service.save(request);
        assertEquals("B", saved.getWebsites().get(0).getName());
        assertEquals("http://intranet:8080/path?q=1", saved.getWebsites().get(0).getUrl());
        assertTrue(service.visibleWebsites().isEmpty());
        saved.getWebsites().clear(); request.getWebsites().get(0).setName("tampered");
        CommonWebsiteService restarted = service(file);
        assertFalse(restarted.settings().isEnabled());
        assertEquals("B", restarted.settings().getWebsites().get(0).getName());
        restarted.save(new CommonWebsiteService.Settings());
        assertTrue(service(file).settings().getWebsites().isEmpty(), "An intentionally empty list must not fall back to YAML");
    }

    @Test void rejectsUnsafeUrlsAndDoesNotReplaceTheSavedSnapshot() {
        CommonWebsiteService service = service(directory.resolve("websites.json"));
        for (String url : Arrays.asList("javascript:alert(1)", "data:text/html,test", "file:///etc/passwd",
                "ftp://example.com", "https://user:password@example.com", "https://example.com:99999", "https://")) {
            CommonWebsiteService.Settings request = new CommonWebsiteService.Settings();
            request.setWebsites(Collections.singletonList(site("Bad", url)));
            assertThrows(IllegalArgumentException.class, () -> service.save(request), url);
            assertEquals("Original", service.visibleWebsites().get(0).getName());
        }
    }

    @Test void failedPersistenceKeepsTheLiveCatalogUnchanged() throws Exception {
        Path blocker = directory.resolve("not-a-directory");
        Files.write(blocker, new byte[]{1});
        CommonWebsiteService service = service(blocker.resolve("websites.json"));
        CommonWebsiteService.Settings request = new CommonWebsiteService.Settings();
        request.setWebsites(Collections.singletonList(site("Replacement", "https://example.org")));
        assertThrows(IllegalStateException.class, () -> service.save(request));
        assertEquals("Original", service.visibleWebsites().get(0).getName());
    }

    @Test void invalidLegacyYamlEntryDoesNotPreventStartupAndRemainsEditable() {
        ServerConfig defaults = new ServerConfig();
        defaults.setCommonWebsites(Arrays.asList(site("Broken", "https://"),
                site("Intranet", "http://office_portal:8080/home"), site("Valid", "https://example.com")));
        CommonWebsiteService service = new CommonWebsiteService(defaults, directory.resolve("missing.json").toString());
        assertDoesNotThrow(service::load);
        assertEquals(3, service.settings().getWebsites().size());
        assertEquals("https://", service.settings().getWebsites().get(0).getUrl());
        assertEquals(1, service.settings().getWarnings().size());
        assertEquals(2, service.visibleWebsites().size());
        assertEquals("http://office_portal:8080/home", service.visibleWebsites().get(0).getUrl());
        assertThrows(IllegalArgumentException.class, () -> service.save(service.settings()),
                "Saving must still require correction of invalid entries");
    }

    @Test void invalidSavedEntryIsRetainedWithoutRewritingTheOriginalFile() throws Exception {
        Path file = directory.resolve("websites.json");
        String original = "{\"enabled\":true,\"websites\":[{\"name\":\"Bad\",\"url\":\"javascript:alert(1)\"}]}";
        Files.write(file, original.getBytes(StandardCharsets.UTF_8));
        CommonWebsiteService service = service(file);
        assertTrue(service.visibleWebsites().isEmpty());
        assertEquals("javascript:alert(1)", service.settings().getWebsites().get(0).getUrl());
        assertFalse(service.settings().getWarnings().isEmpty());
        assertEquals(original, new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }

    @Test void malformedSavedFileFallsBackWithWarningWithoutOverwritingIt() throws Exception {
        Path file = directory.resolve("websites.json");
        Files.write(file, "not-json".getBytes(StandardCharsets.UTF_8));
        CommonWebsiteService service = service(file);
        assertEquals("Original", service.visibleWebsites().get(0).getName());
        assertFalse(service.settings().getWarnings().isEmpty());
        assertEquals("not-json", new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }
}
