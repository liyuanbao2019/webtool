package com.li.jc.webtool.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.li.jc.webtool.controller.ServerController;
import com.li.jc.webtool.model.ServerInfo;
import com.li.jc.webtool.util.CryptoUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.OutputStream;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ServerConfigurationRefreshTest {
    @TempDir Path directory;
    private ServerConfig config;
    private ExcelServerConfigLoader loader;
    private ServerController controller;
    private String previousJarLocation;
    private ClassLoader previousClassLoader;
    private URLClassLoader classLoader;
    private Path fixture;

    @BeforeEach
    void setUp() throws Exception {
        previousJarLocation = System.getProperty("webtool.jar.location");
        System.setProperty("webtool.jar.location", directory.resolve("webtool.jar").toString());
        Path resources = Files.createDirectory(directory.resolve("classpath"));
        fixture = resources.resolve("refresh-test.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(fixture)) {
            Sheet sheet = workbook.createSheet("Servers");
            String[] header = {"Group", "Name", "Host", "Port", "Username", "Password"};
            String[] values = {"original", "Original server", "192.0.2.1", "22", "root", "test-password"};
            Row first = sheet.createRow(0);
            Row row = sheet.createRow(1);
            for (int i = 0; i < header.length; i++) {
                first.createCell(i).setCellValue(header[i]);
                row.createCell(i).setCellValue(values[i]);
            }
            workbook.write(out);
        }
        previousClassLoader = Thread.currentThread().getContextClassLoader();
        classLoader = new URLClassLoader(new java.net.URL[]{resources.toUri().toURL()}, previousClassLoader);
        Thread.currentThread().setContextClassLoader(classLoader);
        config = new ServerConfig();
        config.setServersConfigFile("refresh-test.xlsx");
        loader = newLoader();
        loader.onApplicationEvent(null);
        controller = new ServerController();
        ReflectionTestUtils.setField(controller, "serverConfig", config);
        ReflectionTestUtils.setField(controller, "excelServerConfigLoader", loader);
    }

    @AfterEach
    void tearDown() throws Exception {
        Thread.currentThread().setContextClassLoader(previousClassLoader);
        if (classLoader != null) classLoader.close();
        if (previousJarLocation == null) System.clearProperty("webtool.jar.location");
        else System.setProperty("webtool.jar.location", previousJarLocation);
    }

    @Test
    void savesAndReloadsTheSameExternalFileWithoutRebuildingClasspath() throws Exception {
        Path output = ReflectionTestUtils.invokeMethod(loader, "getOutputFile", "refresh-test.xlsx");
        assertEquals(directory.resolve("config/refresh-test.xlsx"), output);
        byte[] originalClasspath = Files.readAllBytes(fixture);
        ServerInfo added = server("192.0.2.2", "Added", "new-group");
        ServerInfo saved = decode(controller.add(added).getEncryptedData());
        assertEquals("Added", controller.getById(saved.getId()).getName());
        assertEquals(2, controller.list().stream().mapToInt(g -> g.getServers().size()).sum());
        assertTrue(controller.listGroups().contains("new-group"));

        for (int i = 0; i < 3; i++) {
            ServerInfo updated = server("192.0.2." + (10 + i), "Edited " + i, "moved-" + i);
            updated.setPort(2200 + i);
            updated.setUsername("user" + i);
            updated.setPassword("changed-" + i);
            controller.update(saved.getId(), updated);
            controller.reload();
            ServerInfo actual = controller.getById(saved.getId());
            assertNotNull(actual, "refresh must preserve the ID used by open pages");
            assertEquals(updated.getName(), actual.getName());
            assertEquals(updated.getHost(), actual.getHost());
            assertEquals(updated.getPort(), actual.getPort());
            assertEquals(updated.getUsername(), actual.getUsername());
            assertEquals(updated.getPassword(), actual.getPassword());
            assertEquals(updated.getGroupName(), actual.getGroupName());
        }
        assertArrayEquals(originalClasspath, Files.readAllBytes(fixture));
        newLoader().onApplicationEvent(null);
        assertEquals("Edited 2", controller.getById("192.0.2.12").getName());
        String id = controller.getById("192.0.2.12").getId();
        controller.delete(id);
        controller.reload();
        assertNull(controller.getById(id));
        assertNull(controller.getById("192.0.2.12"));
    }

    @Test
    void failedReloadKeepsTheLastGoodList() throws Exception {
        String id = controller.getById("192.0.2.1").getId();
        Files.createDirectories(directory.resolve("config"));
        Files.write(directory.resolve("config/refresh-test.xlsx"), new byte[]{1, 2, 3});
        assertThrows(RuntimeException.class, controller::reload);
        assertEquals("Original server", controller.getById(id).getName());
        assertEquals(1, controller.list().size());
    }

    @Test
    void failedSaveDoesNotPublishChangesOrReportSuccess() throws Exception {
        Files.write(directory.resolve("config"), new byte[]{1});
        String id = controller.getById("192.0.2.1").getId();
        assertThrows(RuntimeException.class, () -> controller.add(server("192.0.2.2", "Added", "new")));
        assertThrows(RuntimeException.class, () -> controller.update(id, server("192.0.2.3", "Changed", "new")));
        assertThrows(RuntimeException.class, () -> controller.delete(id));
        assertEquals("Original server", controller.getById(id).getName());
        assertNull(controller.getById("192.0.2.2"));
        assertNull(controller.getById("192.0.2.3"));
    }

    @Test
    void absoluteConfigPathIsUsedForBothSaveAndReload() throws Exception {
        Path external = directory.resolve("custom location/servers.xlsx");
        Files.createDirectories(external.getParent());
        Files.copy(fixture, external);
        config.setServersConfigFile(external.toString());
        controller.reload();
        ServerInfo saved = decode(controller.add(server("192.0.2.2", "Absolute path", "custom")).getEncryptedData());
        controller.reload();
        assertEquals("Absolute path", controller.getById(saved.getId()).getName());
        assertEquals(external, ReflectionTestUtils.invokeMethod(loader, "getOutputFile", external.toString()));
        assertFalse(Files.exists(directory.resolve("config")));
    }

    @Test
    void duplicateHostsAreEditedByIdAndUnknownIdsCannotCreateDuplicates() throws Exception {
        Files.createDirectories(directory.resolve("config"));
        ServerInfo other = server("192.0.2.1", "Other account", "original");
        other.setUsername("oracle");
        ServerInfo saved = decode(controller.add(other).getEncryptedData());
        controller.reload();
        assertEquals("oracle", controller.getById(saved.getId()).getUsername());
        ServerInfo update = server("192.0.2.1", "Edited account", "original");
        update.setUsername("oracle");
        controller.update(saved.getId(), update);
        controller.reload();
        assertEquals("Edited account", controller.getById(saved.getId()).getName());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller.update("expired-id", update));
        assertEquals(2, controller.list().get(0).getServers().size());
        assertEquals("Original server", decode(controller.list().get(0).getServers().get(0).getEncryptedData()).getName());
    }

    @Test
    void httpApiReturnsFreshRowsAndReportsPersistenceFailures() throws Exception {
        org.springframework.test.web.servlet.MockMvc mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(controller).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/server/list"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"));
        Files.write(directory.resolve("config"), new byte[]{1});
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/server/add")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsBytes(server("192.0.2.2", "Added", "new"))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isInternalServerError())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message").isNotEmpty());
        assertNull(controller.getById("192.0.2.2"));
    }

    private ExcelServerConfigLoader newLoader() {
        ExcelServerConfigLoader instance = new ExcelServerConfigLoader();
        ReflectionTestUtils.setField(instance, "serverConfig", config);
        ReflectionTestUtils.setField(instance, "serversConfigFile", "refresh-test.xlsx");
        return instance;
    }

    private ServerInfo server(String host, String name, String group) {
        ServerInfo server = new ServerInfo();
        server.setHost(host);
        server.setName(name);
        server.setGroupName(group);
        server.setUsername("root");
        server.setPassword("test-password");
        return server;
    }

    private ServerInfo decode(String encrypted) throws Exception {
        return new ObjectMapper().readValue(CryptoUtil.decrypt(encrypted), ServerInfo.class);
    }
}
