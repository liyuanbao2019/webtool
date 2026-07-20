package com.li.jc.webtool.service;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OracleRecycleBinServiceTest {

    private final OracleRecycleBinService service = new OracleRecycleBinService();

    @Test
    void usesDatabaseScopeWhenDbaRecycleBinIsReadable() {
        StubJdbcTemplate jdbc = new StubJdbcTemplate(true, false);
        OracleRecycleBinService.Snapshot snapshot = service.query(jdbc, 30);
        assertEquals(OracleRecycleBinService.Scope.DATABASE, snapshot.getScope());
        assertEquals(2, snapshot.getCount());
        assertEquals(193D, snapshot.getSizeMb());
    }

    @Test
    void fallsBackToCurrentUserScopeWithoutDbaViewPermission() {
        StubJdbcTemplate jdbc = new StubJdbcTemplate(false, false);
        OracleRecycleBinService.Snapshot snapshot = service.query(jdbc, 30);
        assertEquals(OracleRecycleBinService.Scope.CURRENT_USER, snapshot.getScope());
        assertEquals("NETMAINTAIN", snapshot.getRows().get(0).get("OWNER"));
    }

    @Test
    void purgesTheSameDatabaseScopeThatWasQueried() {
        StubJdbcTemplate jdbc = new StubJdbcTemplate(true, false);
        int count = service.purgeDetectedScope(jdbc);
        assertEquals(2, count);
        assertEquals("PURGE DBA_RECYCLEBIN", jdbc.executedSql);
    }

    @Test
    void reportsSysdbaRequirementInsteadOfClaimingSuccess() {
        StubJdbcTemplate jdbc = new StubJdbcTemplate(true, true);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.purgeDetectedScope(jdbc));
        assertTrue(error.getMessage().contains("SYSDBA"));
    }

    private static final class StubJdbcTemplate extends JdbcTemplate {
        private final boolean dbaReadable;
        private final boolean purgeDenied;
        private String executedSql;

        private StubJdbcTemplate(boolean dbaReadable, boolean purgeDenied) {
            this.dbaReadable = dbaReadable;
            this.purgeDenied = purgeDenied;
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            if (sql.contains("dba_recyclebin") && !dbaReadable) {
                throw new DataAccessResourceFailureException("ORA-00942");
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("OWNER", sql.contains("dba_recyclebin") ? "FSP_GZWFM" : "NETMAINTAIN");
            row.put("ORIGINAL_NAME", "TEST_TABLE");
            row.put("SIZE_MB", 3D);
            return Collections.singletonList(row);
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql) {
            if (sql.contains("dba_recyclebin") && !dbaReadable) {
                throw new DataAccessResourceFailureException("ORA-00942");
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("OBJECT_COUNT", dbaReadable ? 2 : 1);
            row.put("SIZE_MB", dbaReadable ? 193D : 3D);
            return Collections.singletonList(row);
        }

        @Override
        public void execute(String sql) {
            if (purgeDenied) throw new DataAccessResourceFailureException("ORA-01031: insufficient privileges");
            executedSql = sql;
        }
    }
}
