package com.gxcj.xjtool.service;

import com.gxcj.xjtool.config.OracleConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseToolsServiceTest {

    @Test
    void oracleArchiveCleanupExecutesRmanThroughSsh() {
        DatabaseToolsService service = serviceFor("ORACLE");

        Map<String, Object> result = service.cleanupArchiveLogs(0, 7, true);

        assertEquals(Boolean.TRUE, result.get("executed"));
        assertEquals("DELETE NOPROMPT ARCHIVELOG ALL COMPLETED BEFORE 'SYSDATE-7';", result.get("command"));
        assertTrue(String.valueOf(result.get("output")).contains("Recovery Manager complete"));
    }

    @Test
    void archiveCleanupRequiresExplicitConfirmation() {
        DatabaseToolsService service = serviceFor("ORACLE");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.cleanupArchiveLogs(0, 7, false));

        assertTrue(error.getMessage().contains("确认"));
    }

    @Test
    void mysqlRecycleBinIsExplicitlyUnsupportedAndDoesNotConnect() {
        DatabaseToolsService service = serviceFor("MYSQL");

        Map<String, Object> query = service.queryRecycleBin(0);
        Map<String, Object> cleanup = service.cleanupRecycleBin(0, true);

        assertEquals(Boolean.FALSE, query.get("supported"));
        assertEquals(Boolean.FALSE, cleanup.get("supported"));
        assertEquals(Boolean.FALSE, cleanup.get("executed"));
    }

    @Test
    void unsupportedDatabaseTypeIsRejected() {
        DatabaseToolsService service = serviceFor("POSTGRESQL");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.queryRecycleBin(0));

        assertTrue(error.getMessage().contains("Oracle、达梦和 MySQL"));
    }

    @Test
    void invalidDatasourceIndexIsRejected() {
        DatabaseToolsService service = serviceFor("DM");

        assertThrows(IllegalArgumentException.class, () -> service.queryRecycleBin(1));
    }

    private DatabaseToolsService serviceFor(String type) {
        OracleConfig config = new OracleConfig();
        OracleConfig.OracleDataSource datasource = new OracleConfig.OracleDataSource();
        datasource.setName("test-" + type.toLowerCase());
        datasource.setType(type);
        config.getDatasources().add(datasource);
        OracleSshCommandService ssh = mock(OracleSshCommandService.class);
        when(ssh.cleanupArchiveLogs(anyInt(), anyInt())).thenReturn(
                new OracleSshCommandService.CommandResult(0, "Recovery Manager complete.", ""));
        return new DatabaseToolsService(config, ssh, mock(OracleHealthCheckService.class),
                mock(OracleRecycleBinService.class));
    }
}
