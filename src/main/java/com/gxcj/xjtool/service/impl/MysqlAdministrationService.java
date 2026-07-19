package com.gxcj.xjtool.service.impl;

import com.gxcj.xjtool.config.DatabaseConfig;
import com.gxcj.xjtool.config.SecurityConfig;
import com.gxcj.xjtool.service.SqlAuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** MySQL and PXC-specific diagnostics and guarded administration operations. */
@Slf4j
@Service
@RequiredArgsConstructor
public class MysqlAdministrationService {

    private static final String MYSQL_TABLE_REF_REGEX =
            "`[^`]+`|\"[^\"]+\"|\\[[^\\]]+\\]|[A-Za-z0-9_$]+";
    private static final String MYSQL_QUALIFIED_TABLE_REF_REGEX =
            "(?:" + MYSQL_TABLE_REF_REGEX + ")(?:\\s*\\.\\s*(?:" + MYSQL_TABLE_REF_REGEX + "))?";
    private static final Pattern MYSQL_ALTER_TABLE_CAPTURE_PATTERN = Pattern.compile(
            "(?is)^\\s*ALTER\\s+TABLE\\s+(" + MYSQL_QUALIFIED_TABLE_REF_REGEX + ")\\s+(.*)$");
    private static final Pattern MYSQL_CREATE_INDEX_CAPTURE_PATTERN = Pattern.compile(
            "(?is)^\\s*CREATE\\s+(UNIQUE\\s+|FULLTEXT\\s+|SPATIAL\\s+)?INDEX\\s+(" + MYSQL_TABLE_REF_REGEX + ")\\s+ON\\s+(" + MYSQL_QUALIFIED_TABLE_REF_REGEX + ")\\s*(\\(.*)$");
    private static final Pattern MYSQL_DROP_INDEX_CAPTURE_PATTERN = Pattern.compile(
            "(?is)^\\s*DROP\\s+INDEX\\s+(" + MYSQL_TABLE_REF_REGEX + ")\\s+ON\\s+(" + MYSQL_QUALIFIED_TABLE_REF_REGEX + ")\\b.*$");
    private static final Pattern MYSQL_JDBC_URL_PATTERN = Pattern.compile(
            "^jdbc:mysql://([^:/?]+)(?::(\\d+))?/([^?]+).*", Pattern.CASE_INSENSITIVE);

    private final DatabaseConnectionManager connectionManager;
    private final SqlAuditService sqlAuditService;
    private final SecurityConfig securityConfig;

    private static final Set<String> MYSQL_PROCESS_DATABASE_WHITELIST =
            new HashSet<>(Arrays.asList("tm_xj", "tm_xj_jike", "ingp_auth", "ingp_auth_jike"));

    public List<Map<String, Object>> getMysqlWsrepProcesses(int datasourceIndex, String databaseName, String command, String eventType) {
        validateMysqlProcessDatasource(datasourceIndex, databaseName);

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ID, USER, HOST, DB, COMMAND, TIME, STATE, INFO ");
        sql.append("FROM information_schema.PROCESSLIST ");
        sql.append("WHERE DB = ? ");

        List<Object> params = new ArrayList<>();
        params.add(databaseName);

        // COMMAND 筛选条件
        if (command != null && !command.trim().isEmpty()) {
            sql.append("AND COMMAND = ? ");
            params.add(command.trim());
        }

        // 事件类型筛选
        if ("cluster_wait".equals(eventType)) {
            sql.append("AND STATE LIKE ? ");
            params.add("%wsrep:%");
        }

        sql.append("ORDER BY TIME DESC, ID DESC");

        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = connectionManager.getConnection(datasourceIndex);
                PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            stmt.setQueryTimeout(15);
            for (int i = 0; i < params.size(); i++) {
                Object param = params.get(i);
                if (param instanceof String) {
                    stmt.setString(i + 1, (String) param);
                } else if (param instanceof Integer) {
                    stmt.setInt(i + 1, (Integer) param);
                } else if (param instanceof Long) {
                    stmt.setLong(i + 1, (Long) param);
                }
            }
            try (ResultSet rs = stmt.executeQuery()) {
                ResultSetMetaData metaData = rs.getMetaData();
                int columnCount = metaData.getColumnCount();
                List<String> columns = new ArrayList<>();
                for (int i = 1; i <= columnCount; i++) {
                    columns.add(metaData.getColumnLabel(i));
                }
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= columnCount; i++) {
                        row.put(columns.get(i - 1), DatabaseServiceImpl.getColumnValue(rs, i, metaData.getColumnType(i)));
                    }
                    rows.add(row);
                }
            }
            log.info("MySQL wsrep process query success datasource={}, database={}, rows={}",
                    datasourceIndex, databaseName, rows.size());
        } catch (SQLException e) {
            log.error("MySQL wsrep process query failed datasource={}, database={}", datasourceIndex, databaseName, e);
            throw new IllegalStateException(e.getMessage(), e);
        }
        return rows;
    }

    public List<Map<String, Object>> getMysqlCurrentSlowSql(int datasourceIndex, String databaseName, int minSeconds) {
        validateMysqlProcessDatasource(datasourceIndex, databaseName);
        int threshold = Math.max(1, Math.min(minSeconds, 3600));

        String sql = "SELECT ID, USER, HOST, DB, COMMAND, TIME, STATE, INFO " +
                "FROM information_schema.PROCESSLIST " +
                "WHERE DB = ? " +
                "AND COMMAND = 'Query' " +
                "AND ID <> CONNECTION_ID() " +
                "AND TIME >= ? " +
                "AND INFO IS NOT NULL " +
                "ORDER BY TIME DESC, ID DESC " +
                "LIMIT 200";

        try (Connection conn = connectionManager.getConnection(datasourceIndex);
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setQueryTimeout(15);
            stmt.setString(1, databaseName);
            stmt.setInt(2, threshold);
            try (ResultSet rs = stmt.executeQuery()) {
                List<Map<String, Object>> rows = readRows(rs);
                log.info("MySQL current slow SQL query success datasource={}, database={}, threshold={}s, rows={}",
                        datasourceIndex, databaseName, threshold, rows.size());
                return rows;
            }
        } catch (SQLException e) {
            log.error("MySQL current slow SQL query failed datasource={}, database={}", datasourceIndex, databaseName, e);
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    public Map<String, Object> getMysqlTransactionDiagnostics(int datasourceIndex, String databaseName, int minSeconds) {
        validateMysqlProcessDatasource(datasourceIndex, databaseName);
        int threshold = Math.max(1, Math.min(minSeconds, 86400));

        Map<String, Object> result = new LinkedHashMap<>();
        try (Connection conn = connectionManager.getConnection(datasourceIndex)) {
            List<Map<String, Object>> longTransactions = queryMysqlLongTransactions(conn, databaseName, threshold);
            result.put("longTransactions", longTransactions);
            result.put("longTransactionCount", longTransactions.size());

            try {
                List<Map<String, Object>> lockWaits = queryMysqlLockWaits(conn, databaseName);
                result.put("lockWaits", lockWaits);
                result.put("lockWaitCount", lockWaits.size());
                result.put("lockWaitMessage", null);
            } catch (SQLException lockEx) {
                log.warn("MySQL information_schema lock wait query failed datasource={}, database={}: {}",
                        datasourceIndex, databaseName, lockEx.getMessage());
                try {
                    List<Map<String, Object>> lockWaits = queryMysqlLockWaitsFromPerformanceSchema(conn, databaseName);
                    result.put("lockWaits", lockWaits);
                    result.put("lockWaitCount", lockWaits.size());
                    result.put("lockWaitMessage", null);
                } catch (SQLException performanceLockEx) {
                    log.warn("MySQL performance_schema lock wait query failed datasource={}, database={}: {}",
                            datasourceIndex, databaseName, performanceLockEx.getMessage());
                    result.put("lockWaits", Collections.emptyList());
                    result.put("lockWaitCount", 0);
                    result.put("lockWaitMessage", "Lock wait detail unavailable: " + performanceLockEx.getMessage());
                }
            }

            log.info("MySQL transaction diagnostics success datasource={}, database={}, threshold={}s, trxCount={}",
                    datasourceIndex, databaseName, threshold, longTransactions.size());
            return result;
        } catch (SQLException e) {
            log.error("MySQL transaction diagnostics failed datasource={}, database={}", datasourceIndex, databaseName, e);
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    public Map<String, Object> buildMysqlOnlineDdlPlan(int datasourceIndex, String databaseName, String ddl, String username) {
        validateMysqlProcessDatasource(datasourceIndex, databaseName);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);

        if (ddl == null || ddl.trim().isEmpty()) {
            result.put("message", "DDL cannot be empty");
            return result;
        }

        DatabaseConfig.DataSourceConfig ds = connectionManager.getDataSourceConfig(datasourceIndex);
        if (ds == null || !"MYSQL".equalsIgnoreCase(ds.getType())) {
            result.put("message", "Only MySQL datasource supports online DDL plan");
            return result;
        }

        String normalizedDdl = DatabaseSqlParser.formatForJdbcExecution(ddl.trim());
        OnlineDdlAlter alter = buildOnlineAlter(normalizedDdl);
        DatabaseServiceImpl.MysqlPxcDdlTarget target = DatabaseServiceImpl.parseMysqlPxcDdlTarget(normalizedDdl);
        if (target == null) {
            result.put("message", "Only ALTER TABLE, CREATE INDEX, and DROP INDEX can be converted to online DDL");
            result.put("supported", false);
            return result;
        }
        if (target.blockAlways || alter == null) {
            result.put("message", target.operation + " is not supported by the online DDL assistant");
            result.put("supported", false);
            result.put("operation", target.operation);
            return result;
        }

        String targetSchema = target.schemaName != null ? target.schemaName : databaseName;
        String targetTable = target.tableName;
        if (!databaseName.equalsIgnoreCase(targetSchema)) {
            result.put("message", "DDL target database must match selected database: " + databaseName);
            result.put("supported", false);
            return result;
        }

        List<String> warnings = new ArrayList<>();
        List<String> blockers = new ArrayList<>();
        Map<String, Object> tableStats = new LinkedHashMap<>();
        int wsrepWaitCount = 0;
        int longTransactionCount = 0;
        int lockWaitCount = 0;

        try (Connection conn = connectionManager.getConnection(datasourceIndex)) {
            tableStats = queryMysqlTableStats(conn, targetSchema, targetTable);
            long tableRows = toLong(tableStats.get("tableRows"), -1L);
            long totalBytes = toLong(tableStats.get("totalBytes"), -1L);

            if (tableRows < 0) {
                blockers.add("Cannot determine table size from information_schema.TABLES");
            } else if (tableRows >= securityConfig.getSqlCheck().getMysqlPxcDdl().getLargeTableRows()) {
                warnings.add("Large table detected: estimated rows=" + tableRows);
            }
            if (totalBytes >= 0) {
                tableStats.put("totalSizeMb", totalBytes / 1024 / 1024);
            }

            wsrepWaitCount = countMysqlWsrepWaits(conn, targetSchema);
            if (wsrepWaitCount > 0) {
                blockers.add("Current wsrep cluster wait processes: " + wsrepWaitCount);
            }

            List<Map<String, Object>> longTransactions = queryMysqlLongTransactions(conn, targetSchema, 30);
            longTransactionCount = longTransactions.size();
            if (longTransactionCount > 0) {
                blockers.add("Long transactions running >=30s: " + longTransactionCount);
            }

            try {
                List<Map<String, Object>> lockWaits = queryMysqlLockWaits(conn, targetSchema);
                lockWaitCount = lockWaits.size();
            } catch (SQLException e) {
                try {
                    List<Map<String, Object>> lockWaits = queryMysqlLockWaitsFromPerformanceSchema(conn, targetSchema);
                    lockWaitCount = lockWaits.size();
                } catch (SQLException ignored) {
                    warnings.add("Lock wait detail unavailable: " + e.getMessage());
                }
            }
            if (lockWaitCount > 0) {
                blockers.add("Current lock waits: " + lockWaitCount);
            }
        } catch (SQLException e) {
            result.put("message", "Precheck failed: " + e.getMessage());
            result.put("supported", true);
            return result;
        }

        String command = buildPtOnlineSchemaChangeCommand(ds, targetSchema, targetTable, alter.alterClause, true);
        String dryRunCommand = buildPtOnlineSchemaChangeCommand(ds, targetSchema, targetTable, alter.alterClause, false);
        boolean canExecute = blockers.isEmpty();

        result.put("success", true);
        result.put("supported", true);
        result.put("canExecute", canExecute);
        result.put("riskLevel", canExecute ? (warnings.isEmpty() ? "LOW" : "MEDIUM") : "HIGH");
        result.put("operation", alter.operation);
        result.put("database", targetSchema);
        result.put("table", targetTable);
        result.put("alterClause", alter.alterClause);
        result.put("command", command);
        result.put("dryRunCommand", dryRunCommand);
        result.put("tableStats", tableStats);
        result.put("wsrepWaitCount", wsrepWaitCount);
        result.put("longTransactionCount", longTransactionCount);
        result.put("lockWaitCount", lockWaitCount);
        result.put("warnings", warnings);
        result.put("blockers", blockers);
        result.put("message", canExecute
                ? "Online DDL plan generated. Execute only in an approved maintenance window."
                : "Online DDL plan generated but blockers must be cleared before execution.");

        try {
            sqlAuditService.logSqlExecution(
                    username,
                    "MYSQL ONLINE DDL PLAN: " + normalizedDdl,
                    true,
                    canExecute ? null : blockers.toString(),
                    0,
                    0,
                    ds.getName());
        } catch (Exception e) {
            log.warn("Record MySQL online DDL plan audit failed", e);
        }

        return result;
    }

    private OnlineDdlAlter buildOnlineAlter(String ddl) {
        Matcher alterMatcher = MYSQL_ALTER_TABLE_CAPTURE_PATTERN.matcher(ddl);
        if (alterMatcher.find()) {
            return new OnlineDdlAlter("ALTER TABLE", alterMatcher.group(2).trim());
        }

        Matcher createIndexMatcher = MYSQL_CREATE_INDEX_CAPTURE_PATTERN.matcher(ddl);
        if (createIndexMatcher.find()) {
            String indexKind = createIndexMatcher.group(1) == null ? "" : createIndexMatcher.group(1).trim() + " ";
            String indexName = DatabaseServiceImpl.cleanMysqlIdentifier(createIndexMatcher.group(2));
            String definition = createIndexMatcher.group(4).trim();
            return new OnlineDdlAlter("CREATE INDEX", "ADD " + indexKind + "INDEX `" + indexName + "` " + definition);
        }

        Matcher dropIndexMatcher = MYSQL_DROP_INDEX_CAPTURE_PATTERN.matcher(ddl);
        if (dropIndexMatcher.find()) {
            String indexName = DatabaseServiceImpl.cleanMysqlIdentifier(dropIndexMatcher.group(1));
            return new OnlineDdlAlter("DROP INDEX", "DROP INDEX `" + indexName + "`");
        }

        return null;
    }

    private Map<String, Object> queryMysqlTableStats(Connection conn, String schemaName, String tableName) throws SQLException {
        String sql = "SELECT TABLE_ROWS, DATA_LENGTH, INDEX_LENGTH, ENGINE, TABLE_COLLATION " +
                "FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setQueryTimeout(10);
            stmt.setString(1, schemaName);
            stmt.setString(2, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                Map<String, Object> row = new LinkedHashMap<>();
                if (!rs.next()) {
                    row.put("tableRows", -1L);
                    row.put("dataLength", -1L);
                    row.put("indexLength", -1L);
                    row.put("totalBytes", -1L);
                    return row;
                }
                long dataLength = rs.getLong("DATA_LENGTH");
                long indexLength = rs.getLong("INDEX_LENGTH");
                row.put("tableRows", rs.getLong("TABLE_ROWS"));
                row.put("dataLength", dataLength);
                row.put("indexLength", indexLength);
                row.put("totalBytes", dataLength + indexLength);
                row.put("engine", rs.getString("ENGINE"));
                row.put("tableCollation", rs.getString("TABLE_COLLATION"));
                return row;
            }
        }
    }

    private int countMysqlWsrepWaits(Connection conn, String databaseName) throws SQLException {
        String sql = "SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE DB = ? AND STATE LIKE ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setQueryTimeout(10);
            stmt.setString(1, databaseName);
            stmt.setString(2, "%wsrep:%");
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private String buildPtOnlineSchemaChangeCommand(DatabaseConfig.DataSourceConfig ds, String databaseName,
            String tableName, String alterClause, boolean execute) {
        MysqlJdbcEndpoint endpoint = parseMysqlJdbcEndpoint(ds.getUrl());
        List<String> parts = new ArrayList<>();
        parts.add("pt-online-schema-change");
        parts.add("--alter " + shellQuote(alterClause));
        parts.add("--host=" + shellQuote(endpoint.host));
        parts.add("--port=" + endpoint.port);
        parts.add("--user=" + shellQuote(ds.getUsername()));
        parts.add("--ask-pass");
        parts.add("--max-load=Threads_running=50");
        parts.add("--critical-load=Threads_running=100");
        parts.add("--chunk-time=0.5");
        parts.add("--set-vars=lock_wait_timeout=3");
        parts.add("--check-interval=2");
        parts.add("--recursion-method=none");
        parts.add(execute ? "--execute" : "--dry-run");
        parts.add("D=" + databaseName + ",t=" + tableName);
        return String.join(" \\\n  ", parts);
    }

    private MysqlJdbcEndpoint parseMysqlJdbcEndpoint(String url) {
        Matcher matcher = MYSQL_JDBC_URL_PATTERN.matcher(url == null ? "" : url);
        if (matcher.find()) {
            String port = matcher.group(2) == null ? "3306" : matcher.group(2);
            return new MysqlJdbcEndpoint(matcher.group(1), port);
        }
        return new MysqlJdbcEndpoint("127.0.0.1", "3306");
    }

    private String shellQuote(String value) {
        String safe = value == null ? "" : value.replace("'", "'\"'\"'");
        return "'" + safe + "'";
    }

    private long toLong(Object value, long defaultValue) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return value == null ? defaultValue : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static class OnlineDdlAlter {
        private final String operation;
        private final String alterClause;

        private OnlineDdlAlter(String operation, String alterClause) {
            this.operation = operation;
            this.alterClause = alterClause;
        }
    }

    private static class MysqlJdbcEndpoint {
        private final String host;
        private final String port;

        private MysqlJdbcEndpoint(String host, String port) {
            this.host = host;
            this.port = port;
        }
    }

    private List<Map<String, Object>> queryMysqlLongTransactions(Connection conn, String databaseName, int minSeconds)
            throws SQLException {
        String sql = "SELECT " +
                "t.trx_id AS TRX_ID, " +
                "t.trx_state AS TRX_STATE, " +
                "DATE_FORMAT(t.trx_started, '%Y-%m-%d %H:%i:%s') AS TRX_STARTED, " +
                "TIMESTAMPDIFF(SECOND, t.trx_started, NOW()) AS TRX_SECONDS, " +
                "t.trx_mysql_thread_id AS PROCESS_ID, " +
                "p.USER AS USER, p.HOST AS HOST, p.DB AS DB, p.COMMAND AS COMMAND, p.TIME AS PROCESS_TIME, " +
                "p.STATE AS STATE, COALESCE(t.trx_query, p.INFO) AS SQL_TEXT, " +
                "t.trx_rows_locked AS ROWS_LOCKED, t.trx_rows_modified AS ROWS_MODIFIED, " +
                "t.trx_tables_locked AS TABLES_LOCKED, t.trx_lock_structs AS LOCK_STRUCTS " +
                "FROM information_schema.innodb_trx t " +
                "LEFT JOIN information_schema.PROCESSLIST p ON p.ID = t.trx_mysql_thread_id " +
                "WHERE (p.DB = ? OR p.DB IS NULL) " +
                "AND TIMESTAMPDIFF(SECOND, t.trx_started, NOW()) >= ? " +
                "ORDER BY TRX_SECONDS DESC, PROCESS_ID DESC " +
                "LIMIT 200";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setQueryTimeout(15);
            stmt.setString(1, databaseName);
            stmt.setInt(2, minSeconds);
            try (ResultSet rs = stmt.executeQuery()) {
                return readRows(rs);
            }
        }
    }

    private List<Map<String, Object>> queryMysqlLockWaits(Connection conn, String databaseName) throws SQLException {
        String sql = "SELECT " +
                "r.trx_id AS WAITING_TRX_ID, " +
                "r.trx_mysql_thread_id AS WAITING_PROCESS_ID, " +
                "TIMESTAMPDIFF(SECOND, r.trx_started, NOW()) AS WAITING_SECONDS, " +
                "rp.USER AS WAITING_USER, rp.HOST AS WAITING_HOST, rp.DB AS WAITING_DB, " +
                "COALESCE(r.trx_query, rp.INFO) AS WAITING_SQL, " +
                "b.trx_id AS BLOCKING_TRX_ID, " +
                "b.trx_mysql_thread_id AS BLOCKING_PROCESS_ID, " +
                "TIMESTAMPDIFF(SECOND, b.trx_started, NOW()) AS BLOCKING_SECONDS, " +
                "bp.USER AS BLOCKING_USER, bp.HOST AS BLOCKING_HOST, bp.DB AS BLOCKING_DB, " +
                "COALESCE(b.trx_query, bp.INFO) AS BLOCKING_SQL " +
                "FROM information_schema.innodb_lock_waits w " +
                "JOIN information_schema.innodb_trx r ON w.requesting_trx_id = r.trx_id " +
                "JOIN information_schema.innodb_trx b ON w.blocking_trx_id = b.trx_id " +
                "LEFT JOIN information_schema.PROCESSLIST rp ON rp.ID = r.trx_mysql_thread_id " +
                "LEFT JOIN information_schema.PROCESSLIST bp ON bp.ID = b.trx_mysql_thread_id " +
                "WHERE (rp.DB = ? OR bp.DB = ?) " +
                "ORDER BY WAITING_SECONDS DESC, WAITING_PROCESS_ID DESC " +
                "LIMIT 200";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setQueryTimeout(15);
            stmt.setString(1, databaseName);
            stmt.setString(2, databaseName);
            try (ResultSet rs = stmt.executeQuery()) {
                return readRows(rs);
            }
        }
    }

    private List<Map<String, Object>> queryMysqlLockWaitsFromPerformanceSchema(Connection conn, String databaseName)
            throws SQLException {
        String sql = "SELECT " +
                "r.trx_id AS WAITING_TRX_ID, " +
                "r.trx_mysql_thread_id AS WAITING_PROCESS_ID, " +
                "TIMESTAMPDIFF(SECOND, r.trx_started, NOW()) AS WAITING_SECONDS, " +
                "rp.USER AS WAITING_USER, rp.HOST AS WAITING_HOST, rp.DB AS WAITING_DB, " +
                "COALESCE(r.trx_query, rp.INFO) AS WAITING_SQL, " +
                "b.trx_id AS BLOCKING_TRX_ID, " +
                "b.trx_mysql_thread_id AS BLOCKING_PROCESS_ID, " +
                "TIMESTAMPDIFF(SECOND, b.trx_started, NOW()) AS BLOCKING_SECONDS, " +
                "bp.USER AS BLOCKING_USER, bp.HOST AS BLOCKING_HOST, bp.DB AS BLOCKING_DB, " +
                "COALESCE(b.trx_query, bp.INFO) AS BLOCKING_SQL " +
                "FROM performance_schema.data_lock_waits w " +
                "JOIN information_schema.innodb_trx r ON w.REQUESTING_ENGINE_TRANSACTION_ID = r.trx_id " +
                "JOIN information_schema.innodb_trx b ON w.BLOCKING_ENGINE_TRANSACTION_ID = b.trx_id " +
                "LEFT JOIN information_schema.PROCESSLIST rp ON rp.ID = r.trx_mysql_thread_id " +
                "LEFT JOIN information_schema.PROCESSLIST bp ON bp.ID = b.trx_mysql_thread_id " +
                "WHERE (rp.DB = ? OR bp.DB = ?) " +
                "ORDER BY WAITING_SECONDS DESC, WAITING_PROCESS_ID DESC " +
                "LIMIT 200";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setQueryTimeout(15);
            stmt.setString(1, databaseName);
            stmt.setString(2, databaseName);
            try (ResultSet rs = stmt.executeQuery()) {
                return readRows(rs);
            }
        }
    }

    public Map<String, Object> killMysqlProcesses(int datasourceIndex, String databaseName, String command, String eventType, List<Long> processIds, String username) {
        validateMysqlProcessDatasource(datasourceIndex, databaseName);

        Map<String, Object> result = new LinkedHashMap<>();
        List<Long> requestedIds = processIds == null
                ? Collections.emptyList()
                : processIds.stream()
                        .filter(Objects::nonNull)
                        .filter(id -> id > 0)
                        .distinct()
                        .collect(Collectors.toList());

        if (requestedIds.isEmpty()) {
            result.put("success", false);
            result.put("message", "No process selected");
            result.put("killedIds", Collections.emptyList());
            result.put("failed", Collections.emptyList());
            return result;
        }

        // 构建验证 SQL，保持与查询条件一致
        StringBuilder validateSql = new StringBuilder();
        validateSql.append("SELECT COUNT(*) FROM information_schema.PROCESSLIST ");
        validateSql.append("WHERE ID = ? AND DB = ? ");

        List<Object> validateParams = new ArrayList<>();
        validateParams.add(null); // ID placeholder
        validateParams.add(databaseName);

        // COMMAND 筛选条件
        if (command != null && !command.trim().isEmpty()) {
            validateSql.append("AND COMMAND = ? ");
            validateParams.add(command.trim());
        }

        // 事件类型筛选（集群等待时添加 STATE LIKE 条件）
        if ("cluster_wait".equals(eventType)) {
            validateSql.append("AND STATE LIKE ? ");
            validateParams.add("%wsrep:%");
        }

        List<Long> killedIds = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();

        try (Connection conn = connectionManager.getConnection(datasourceIndex);
                PreparedStatement validateStmt = conn.prepareStatement(validateSql.toString());
                Statement killStmt = conn.createStatement()) {
            validateStmt.setQueryTimeout(10);
            killStmt.setQueryTimeout(10);

            for (Long processId : requestedIds) {
                try {
                    // 设置验证 SQL 参数
                    validateStmt.setLong(1, processId);
                    for (int i = 2; i <= validateParams.size(); i++) {
                        Object param = validateParams.get(i - 1);
                        if (param instanceof String) {
                            validateStmt.setString(i, (String) param);
                        } else if (param instanceof Integer) {
                            validateStmt.setInt(i, (Integer) param);
                        }
                    }
                    boolean allowed = false;
                    try (ResultSet rs = validateStmt.executeQuery()) {
                        allowed = rs.next() && rs.getInt(1) > 0;
                    }

                    if (!allowed) {
                        failed.add(buildMysqlKillFailure(processId, "Process no longer matches the kill filter"));
                        continue;
                    }

                    killStmt.execute("KILL " + processId);
                    killedIds.add(processId);
                    log.info("MySQL process killed datasource={}, database={}, processId={}, user={}",
                            datasourceIndex, databaseName, processId, username);
                } catch (SQLException e) {
                    failed.add(buildMysqlKillFailure(processId, e.getMessage()));
                    log.warn("MySQL process kill failed datasource={}, database={}, processId={}: {}",
                            datasourceIndex, databaseName, processId, e.getMessage());
                }
            }
        } catch (SQLException e) {
            log.error("MySQL process batch kill failed datasource={}, database={}, ids={}",
                    datasourceIndex, databaseName, requestedIds, e);
            throw new IllegalStateException(e.getMessage(), e);
        }

        try {
            DatabaseConfig.DataSourceConfig dsConfig = connectionManager.getDataSourceConfig(datasourceIndex);
            sqlAuditService.logSqlExecution(
                    username,
                    "KILL MYSQL PROCESS IDS " + requestedIds + " ON " + databaseName,
                    failed.isEmpty(),
                    failed.isEmpty() ? null : failed.toString(),
                    0,
                    killedIds.size(),
                    dsConfig != null ? dsConfig.getName() : String.valueOf(datasourceIndex));
        } catch (Exception e) {
            log.error("Record MySQL process kill audit failed", e);
        }

        result.put("success", failed.isEmpty());
        result.put("message", "Killed " + killedIds.size() + " process(es), failed " + failed.size());
        result.put("killedIds", killedIds);
        result.put("failed", failed);
        return result;
    }

    private void validateMysqlProcessDatasource(int datasourceIndex, String databaseName) {
        DatabaseConfig.DataSourceConfig ds = connectionManager.getDataSourceConfig(datasourceIndex);
        if (ds == null) {
            throw new IllegalArgumentException("Datasource does not exist");
        }
        if (!"MYSQL".equalsIgnoreCase(ds.getType())) {
            throw new IllegalArgumentException("Only MySQL datasource supports process killing");
        }
        if (databaseName == null || !MYSQL_PROCESS_DATABASE_WHITELIST.contains(databaseName)) {
            throw new IllegalArgumentException("Unsupported database: " + databaseName);
        }
    }

    private Map<String, Object> buildMysqlKillFailure(Long processId, String message) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", processId);
        row.put("message", message);
        return row;
    }

    private List<Map<String, Object>> readRows(ResultSet rs) throws SQLException {
        ResultSetMetaData metaData = rs.getMetaData();
        int columnCount = metaData.getColumnCount();
        List<String> columns = new ArrayList<>();
        for (int i = 1; i <= columnCount; i++) {
            columns.add(metaData.getColumnLabel(i));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= columnCount; i++) {
                row.put(columns.get(i - 1), DatabaseServiceImpl.getColumnValue(rs, i, metaData.getColumnType(i)));
            }
            rows.add(row);
        }
        return rows;
    }
}
