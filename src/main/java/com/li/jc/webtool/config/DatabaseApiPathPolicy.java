package com.li.jc.webtool.config;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Centralizes security-sensitive database API paths so the current database
 * namespace and the historical Oracle namespace always receive equal policy.
 */
final class DatabaseApiPathPolicy {

    private static final Set<String> RATE_LIMITED_PATHS = immutableSet(
            "/api/database/execute",
            "/api/oracle/execute",
            "/api/database/explain",
            "/api/oracle/explain",
            "/api/database/result-edits/commit",
            "/api/oracle/result-edits/commit",
            "/api/database/lock/mysql-slow-sql",
            "/api/database/lock/mysql-transaction-diagnostics");

    private static final Set<String> FORCED_ORIGIN_CHECK_PATHS = immutableSet(
            "/api/database/result-edits/commit",
            "/api/oracle/result-edits/commit",
            "/api/database/lock/mysql-slow-sql",
            "/api/database/lock/mysql-transaction-diagnostics");

    private DatabaseApiPathPolicy() {
    }

    static boolean isRateLimited(String uri) {
        return RATE_LIMITED_PATHS.contains(uri);
    }

    static boolean requiresOriginCheck(String uri) {
        return FORCED_ORIGIN_CHECK_PATHS.contains(uri);
    }

    private static Set<String> immutableSet(String... paths) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(paths)));
    }
}
