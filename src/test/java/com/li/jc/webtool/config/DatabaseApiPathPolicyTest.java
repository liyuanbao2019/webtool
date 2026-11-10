package com.li.jc.webtool.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseApiPathPolicyTest {

    @Test
    void rateLimitsBothDatabaseAndLegacyOracleNamespaces() {
        assertTrue(DatabaseApiPathPolicy.isRateLimited("/api/database/execute"));
        assertTrue(DatabaseApiPathPolicy.isRateLimited("/api/oracle/execute"));
        assertTrue(DatabaseApiPathPolicy.isRateLimited("/api/database/explain"));
        assertTrue(DatabaseApiPathPolicy.isRateLimited("/api/oracle/explain"));
        assertTrue(DatabaseApiPathPolicy.isRateLimited("/api/database/result-edits/commit"));
        assertTrue(DatabaseApiPathPolicy.isRateLimited("/api/oracle/result-edits/commit"));
        assertFalse(DatabaseApiPathPolicy.isRateLimited("/api/database/datasources"));
    }

    @Test
    void forcesOriginCheckForBothResultEditAliases() {
        assertTrue(DatabaseApiPathPolicy.requiresOriginCheck("/api/database/result-edits/commit"));
        assertTrue(DatabaseApiPathPolicy.requiresOriginCheck("/api/oracle/result-edits/commit"));
        assertTrue(DatabaseApiPathPolicy.requiresOriginCheck("/api/database/lock/mysql-slow-sql"));
        assertFalse(DatabaseApiPathPolicy.requiresOriginCheck("/api/database/execute"));
    }

    @Test
    void protectsDataImportWritesButNotStatusPolling() {
        assertTrue(DatabaseApiPathPolicy.isRateLimited("/api/database/import/upload"));
        assertTrue(DatabaseApiPathPolicy.isRateLimited("/api/database/import/start"));
        assertTrue(DatabaseApiPathPolicy.isRateLimited("/api/database/import/sheet"));
        assertTrue(DatabaseApiPathPolicy.requiresOriginCheck("/api/database/import/start"));
        assertFalse(DatabaseApiPathPolicy.isRateLimited("/api/database/import/status"));
    }
}
