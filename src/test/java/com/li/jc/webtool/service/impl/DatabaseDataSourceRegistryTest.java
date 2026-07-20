package com.li.jc.webtool.service.impl;

import com.li.jc.webtool.config.DatabaseConfig;
import com.li.jc.webtool.dto.SqlResultResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DatabaseDataSourceRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void managesAllSupportedDatabaseTypesAndResetsPools() {
        DatabaseConfig config = new DatabaseConfig();
        DatabaseConnectionManager connectionManager = mock(DatabaseConnectionManager.class);
        DatabaseDataSourceRegistry registry = new DatabaseDataSourceRegistry(config, connectionManager);
        ReflectionTestUtils.setField(registry, "configFile", tempDir.resolve("datasources.json").toString());

        DatabaseConfig.DataSourceConfig request = new DatabaseConfig.DataSourceConfig();
        request.setName("postgres-main");
        request.setType("postgresql");
        request.setUrl("jdbc:postgresql://localhost:5432/app");
        request.setUsername("app");
        request.setPassword("secret");

        SqlResultResponse response = registry.add(request);

        assertTrue(response.isSuccess());
        assertEquals(1, config.getDatasources().size());
        assertEquals("POSTGRESQL", config.getDatasources().get(0).getType());
        verify(connectionManager).reset();
    }

    @Test
    void rejectsUnsupportedDatabaseTypeWithoutChangingConfiguration() {
        DatabaseConfig config = new DatabaseConfig();
        DatabaseDataSourceRegistry registry = new DatabaseDataSourceRegistry(
                config, mock(DatabaseConnectionManager.class));
        ReflectionTestUtils.setField(registry, "configFile", tempDir.resolve("datasources.json").toString());

        DatabaseConfig.DataSourceConfig request = new DatabaseConfig.DataSourceConfig();
        request.setName("unsupported");
        request.setType("SQLSERVER");
        request.setUrl("jdbc:sqlserver://localhost");
        request.setUsername("app");

        SqlResultResponse response = registry.add(request);

        assertFalse(response.isSuccess());
        assertTrue(config.getDatasources().isEmpty());
    }
}
