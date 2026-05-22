package com.gxcj.xjtool.service.impl;

import com.gxcj.xjtool.config.OracleConfig;
import com.gxcj.xjtool.dto.ExecuteSqlRequest;
import com.gxcj.xjtool.dto.OracleDataSourceDto;
import com.gxcj.xjtool.dto.SqlResultResponse;
import com.gxcj.xjtool.service.DangerousCommandTokenService;
import com.gxcj.xjtool.service.OracleService;
import com.gxcj.xjtool.service.SqlAuditService;
import com.gxcj.xjtool.service.SqlSecurityService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Oracle 数据库服务实现
 * 集成 HikariCP 连接池与查询保护机制
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OracleServiceImpl implements OracleService {

    private final OracleConfig oracleConfig;
    private final SqlAuditService sqlAuditService;
    private final SqlSecurityService sqlSecurityService;
    private final DangerousCommandTokenService tokenService;

    // 缓存连接池，key为数据源索引
    private final Map<Integer, HikariDataSource> dataSourcePools = new ConcurrentHashMap<>();

    // 缓存数据库类型，key为数据源索引
    private final Map<Integer, String> dataSourceTypes = new ConcurrentHashMap<>();

    // 缓存数据库名称（MySQL用），key为数据源索引
    private final Map<Integer, String> dataSourceDatabases = new ConcurrentHashMap<>();

    // Slave 连接池缓存：key = "datasourceIndex@slaveIp"，value = HikariDataSource
    private final Map<String, HikariDataSource> slavePools = new ConcurrentHashMap<>();

    // Slave 连接池创建时间缓存：key = "datasourceIndex@slaveIp"，value = currentTimeMillis
    private final Map<String, Long> slavePoolStartTimes = new ConcurrentHashMap<>();

    // 用于并行查询的线程池
    private final ExecutorService lockQueryExecutor = Executors.newCachedThreadPool();

    // slave 连接池最大生命周期（毫秒），防止长时间闲置导致 ORA-3113
    private static final long SLAVE_POOL_MAX_LIFE_MS = 5 * 60 * 1000; // 5 分钟

    // 解锁库表专用 SQL
    private static final String LOCK_QUERY_SQL =
        "SELECT " +
        "    c.object_name, " +
        "    b.machine, " +
        "    b.sid, " +
        "    b.serial# as serial_num, " +
        "    b.username, " +
        "    b.program, " +
        "    b.status, " +
        "    b.event, " +
        "    b.blocking_session, " +
        "    a.locked_mode, " +
        "    (SELECT NVL(MAX(ctime), 0) FROM v$lock WHERE sid = b.sid AND type IN ('TM', 'TX')) as lock_duration_seconds, " +
        "    TO_CHAR(b.logon_time, 'YYYY-MM-DD HH24:MI:SS') as logon_time " +
        "FROM v$locked_object a, " +
        "     v$session b, " +
        "     dba_objects c " +
        "WHERE b.sid = a.session_id " +
        "  AND a.object_id = c.object_id " +
        "  AND c.owner NOT IN ('SYS', 'SYSTEM', 'SYSMAN', 'DBSNMP', 'OUTLN', 'XDB', 'WMSYS', 'EXFSYS', 'CTXSYS', 'ANONYMOUS', 'ORDSYS', 'ORDPLUGINS', 'MDSYS', 'APPQOSSYS', 'AUDSYS', 'DBSFWUSER', 'OJVMSYS', 'SYSBACKUP', 'SYSDG', 'SYSKM') " +
        "  AND c.object_name NOT LIKE '%$%' " +
        "  AND c.object_name NOT LIKE 'SYS_%' " +
        "  AND (UPPER(NVL(b.event, ' ')) LIKE 'ENQ: TM%' OR UPPER(NVL(b.event, ' ')) LIKE 'ENQ: TX%') " +
        "  AND (SELECT NVL(MAX(ctime), 0) FROM v$lock WHERE sid = b.sid AND type IN ('TM', 'TX')) > 60 " +
        "ORDER BY lock_duration_seconds DESC";

    @Override
    public List<OracleDataSourceDto> getDataSources() {
        List<OracleDataSourceDto> result = new ArrayList<>();
        List<OracleConfig.OracleDataSource> datasources = oracleConfig.getDatasources();

        if (datasources != null) {
            for (int i = 0; i < datasources.size(); i++) {
                OracleConfig.OracleDataSource ds = datasources.get(i);

                // 构建明文DTO
                OracleDataSourceDto plainDto = OracleDataSourceDto.builder()
                        .index(i)
                        .name(ds.getName())
                        .url(ds.getUrl())
                        .username(ds.getUsername())
                        .build();

                // 将整个DTO序列化为JSON并加密
                try {
                    com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    String json = objectMapper.writeValueAsString(plainDto);
                    String encrypted = com.gxcj.xjtool.util.CryptoUtil.encrypt(json);

                    // 创建只包含加密数据的DTO
                    OracleDataSourceDto encryptedDto = new OracleDataSourceDto();
                    encryptedDto.setEncryptedData(encrypted);
                    result.add(encryptedDto);
                } catch (Exception e) {
                    log.error("数据源信息加密失败", e);
                }
            }
        }
        return result;
    }

    @Override
    public SqlResultResponse testConnection(int datasourceIndex) {
        OracleConfig.OracleDataSource dsConfig = getDataSourceConfig(datasourceIndex);
        if (dsConfig == null) {
            return SqlResultResponse.error("数据源不存在");
        }

        long startTime = System.currentTimeMillis();
        // 测试连接时不一定非要初始化连接池，但为了统一管理，这里也通过连接池获取连接
        // 或者为了纯粹测试配置正确性，可以使用独立连接，避免连接池参数配置错误导致连接失败
        // 这里选择使用连接池，以确保实际运行时也是可用的
        try (Connection conn = getConnectionByIndex(datasourceIndex)) {
            long executionTime = System.currentTimeMillis() - startTime;

            // 获取数据库版本信息
            DatabaseMetaData metaData = conn.getMetaData();
            String dbVersion = metaData.getDatabaseProductName() + " " + metaData.getDatabaseProductVersion();

            // 获取连接池状态（如果是连接池）
            HikariDataSource ds = dataSourcePools.get(datasourceIndex);
            String poolState = "N/A";
            if (ds != null) {
                poolState = String.format("Active: %d, Idle: %d, Total: %d",
                        ds.getHikariPoolMXBean().getActiveConnections(),
                        ds.getHikariPoolMXBean().getIdleConnections(),
                        ds.getHikariPoolMXBean().getTotalConnections());
            }

            log.info("数据库连接测试成功: {}, 耗时: {}ms, 连接池: {}", dsConfig.getName(), executionTime, poolState);

            List<String> columns = Arrays.asList("状态", "数据库版本", "连接耗时", "连接池状态");
            List<Map<String, Object>> rows = new ArrayList<>();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("状态", "连接成功");
            row.put("数据库版本", dbVersion);
            row.put("连接耗时", executionTime + "ms");
            row.put("连接池状态", poolState);
            rows.add(row);

            return SqlResultResponse.success(columns, rows, executionTime);
        } catch (Exception e) {
            log.error("数据库连接测试失败: {}", dsConfig.getName(), e);
            return SqlResultResponse.error("连接失败: " + e.getMessage());
        }
    }

    @Override
    public String getDatasourceType(int datasourceIndex) {
        // 确保连接池已创建以填充dataSourceTypes
        try (Connection conn = getConnectionByIndex(datasourceIndex)) {
            // Connection will be auto-closed
        } catch (Exception e) {
            log.warn("获取数据源类型失败，使用默认值ORACLE", e);
        }
        return dataSourceTypes.getOrDefault(datasourceIndex, "ORACLE");
    }

    @Override
    public SqlResultResponse executeSql(ExecuteSqlRequest request) {
        if (request.getSql() == null || request.getSql().trim().isEmpty()) {
            return SqlResultResponse.error("SQL 语句不能为空");
        }

        // 验证数据源
        OracleConfig.OracleDataSource dsConfig = getDataSourceConfig(request.getDatasourceIndex());
        if (dsConfig == null) {
            return SqlResultResponse.error("数据源不存在");
        }

        String sqlInput = request.getSql().trim();

        // SQL安全检查
        SqlSecurityService.SqlCheckResult securityCheck = sqlSecurityService.checkSql(sqlInput, request.getUsername());

        // 如果是 unsafe，直接拒绝
        if (!securityCheck.isSafe() && !securityCheck.isNeedConfirm()) {
            log.warn("SQL安全检查失败: {} [User: {}]", securityCheck.getReason(), request.getUsername());
            return SqlResultResponse.error("SQL安全检查失败: " + securityCheck.getReason());
        }

        // 如果是 needConfirm，检查Token
        if (securityCheck.isNeedConfirm()) {
            String token = request.getDangerousCommandToken();
            String sessionId = request.getSessionId();

            // 如果没 Token，返回需要确认
            if (token == null || token.isEmpty()) {
                return SqlResultResponse.confirmationNeeded(
                        securityCheck.getReason(),
                        securityCheck.getReasonKey(),
                        securityCheck.getSuggestionKey(),
                        null);
            }

            // 验证 Token
            if (!tokenService.validateAndConsumeToken(sessionId, sqlInput, token)) {
                log.warn("危险命令Token验证失败或已过期: {} [User: {}]", securityCheck.getReason(), request.getUsername());
                return SqlResultResponse.error("危险命令Token验证失败或已过期，请重新确认执行");
            }
        }

        // 智能分割SQL语句（支持PL/SQL块，使用Druid）
        String dbType = getDatasourceType(request.getDatasourceIndex());
        List<String> validSqls = splitSqlStatements(sqlInput, dbType);

        SqlResultResponse response;

        // 获取分页参数（pageSize <= 0 表示不分页）
        int page = request.getPage() != null ? request.getPage() : 1;
        int pageSize = request.getPageSize() != null ? request.getPageSize() : 0;

        // 如果只有一条SQL，按原流程执行
        if (validSqls.size() == 1) {
            response = executeSingleSql(validSqls.get(0), request.getDatasourceIndex(), request.getMaxRows(), page, pageSize);
        }
        // 如果有多条SQL，批量执行（每条 SELECT 也应用分页，避免全量拉取大表）
        else if (validSqls.size() > 1) {
            response = executeBatchSql(validSqls, request.getDatasourceIndex(), request.getMaxRows(), page, pageSize);
        } else {
            response = SqlResultResponse.error("SQL 语句不能为空");
        }

        // 记录审计日志
        try {
            sqlAuditService.logSqlExecution(
                    request.getUsername(),
                    sqlInput,
                    response.isSuccess(),
                    response.getErrorMessage(),
                    response.getExecutionTime(),
                    response.getAffectedRows(),
                    dsConfig.getName());
        } catch (Exception e) {
            log.error("记录审计日志失败", e);
        }

        return response;
    }

    @Override
    public SqlResultResponse explainSql(ExecuteSqlRequest request) {
        if (request.getSql() == null || request.getSql().trim().isEmpty()) {
            return SqlResultResponse.error("SQL 语句不能为空");
        }

        // 验证数据源
        if (getDataSourceConfig(request.getDatasourceIndex()) == null) {
            return SqlResultResponse.error("数据源不存在");
        }

        String sql = request.getSql().trim();
        // 移除末尾的分号
        if (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }

        // SQL安全检查
        SqlSecurityService.SqlCheckResult securityCheck = sqlSecurityService.checkSql(sql, request.getUsername());
        if (!securityCheck.isSafe()) {
            log.warn("SQL安全检查失败(Explain): {} [User: {}]", securityCheck.getReason(), request.getUsername());
            return SqlResultResponse.error("SQL安全检查失败: " + securityCheck.getReason());
        }

        long startTime = System.currentTimeMillis();
        String dbType = getDatasourceType(request.getDatasourceIndex());

        try (Connection conn = getConnectionByIndex(request.getDatasourceIndex())) {
            if ("ORACLE".equals(dbType)) {
                return explainOracleSql(conn, sql, startTime);
            } else if ("DM".equals(dbType)) {
                return explainDmSql(conn, sql, startTime);
            } else if ("MYSQL".equals(dbType)) {
                return explainMysqlSql(conn, sql, startTime);
            } else if ("POSTGRESQL".equals(dbType) || "POSTGRES".equals(dbType) || "PG".equals(dbType)) {
                return explainPostgreSql(conn, sql, startTime);
            } else {
                return SqlResultResponse.error("当前数据库类型不支持执行计划查询");
            }
        } catch (SQLException e) {
            log.error("获取执行计划失败", e);
            return SqlResultResponse.error("获取执行计划失败: " + e.getMessage());
        }
    }

    /**
     * Oracle/DM 执行计划
     */
    private SqlResultResponse explainOracleSql(Connection conn, String sql, long startTime) throws SQLException {
        Statement stmt = null;
        ResultSet rs = null;

        try {
            stmt = conn.createStatement();

            // 1. 清空之前的执行计划
            try {
                stmt.execute("DELETE FROM PLAN_TABLE");
            } catch (SQLException e) {
                // 忽略，表可能不存在或无权限
            }

            // 2. 执行 EXPLAIN PLAN
            String explainSql = "EXPLAIN PLAN FOR " + sql;
            stmt.execute(explainSql);

            // 3. 查询执行计划（从 PLAN_TABLE 获取结构化数据）
            String planQuery = "SELECT " +
                    "ID, " +
                    "LPAD(' ', 2 * (LEVEL - 1)) || OPERATION || ' ' || OPTIONS AS OPERATION, " +
                    "OBJECT_NAME, " +
                    "COST, " +
                    "CARDINALITY, " +
                    "BYTES, " +
                    "CPU_COST, " +
                    "IO_COST " +
                    "FROM PLAN_TABLE " +
                    "START WITH ID = 0 " +
                    "CONNECT BY PRIOR ID = PARENT_ID " +
                    "ORDER SIBLINGS BY ID";

            rs = stmt.executeQuery(planQuery);
            ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();

            // 获取列名
            List<String> columns = new ArrayList<>();
            for (int i = 1; i <= columnCount; i++) {
                columns.add(metaData.getColumnName(i));
            }

            // 获取数据
            List<Map<String, Object>> rows = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= columnCount; i++) {
                    Object value = rs.getObject(i);
                    row.put(columns.get(i - 1), value);
                }
                rows.add(row);
            }

            long executionTime = System.currentTimeMillis() - startTime;

            SqlResultResponse response = new SqlResultResponse();
            response.setSuccess(true);
            response.setColumns(columns);
            response.setRows(rows);
            response.setAffectedRows(0);
            response.setExecutionTime(executionTime);

            log.info("执行计划查询成功，耗时: {}ms [数据库类型: ORACLE]", executionTime);
            return response;

        } finally {
            if (rs != null)
                try {
                    rs.close();
                } catch (SQLException e) {
                }
            if (stmt != null)
                try {
                    stmt.close();
                } catch (SQLException e) {
                }
        }
    }

    /**
     * 达梦（DM）执行计划
     */
    private SqlResultResponse explainDmSql(Connection conn, String sql, long startTime) throws SQLException {
        // 达梦使用 EXPLAIN FOR 语法
        // 检查是否已经包含 EXPLAIN，避免重复添加
        String explainSql;
        if (sql.trim().toUpperCase().startsWith("EXPLAIN")) {
            explainSql = sql; // 已经包含 EXPLAIN，直接使用
        } else {
            explainSql = "EXPLAIN FOR " + sql;
        }

        log.info("达梦执行计划SQL: {}", explainSql);

        Statement stmt = null;
        ResultSet rs = null;

        try {
            stmt = conn.createStatement();

            // EXPLAIN FOR 使用 execute() 而不是 executeQuery()
            boolean hasResultSet = stmt.execute(explainSql);

            if (hasResultSet) {
                // 如果有结果集，获取它
                rs = stmt.getResultSet();
                return buildPlanResponse(rs, startTime, "DM");
            } else {
                // 没有结果集，返回提示
                SqlResultResponse response = new SqlResultResponse();
                response.setSuccess(true);
                response.setColumns(Arrays.asList("说明"));

                List<Map<String, Object>> rows = new ArrayList<>();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("说明", "执行计划已生成，但未返回结果集。请使用达梦管理工具查看详细信息。");
                rows.add(row);

                response.setRows(rows);
                response.setExecutionTime(System.currentTimeMillis() - startTime);
                return response;
            }
        } finally {
            if (rs != null)
                try {
                    rs.close();
                } catch (SQLException e) {
                }
            if (stmt != null)
                try {
                    stmt.close();
                } catch (SQLException e) {
                }
        }
    }

    /**
     * 构建执行计划响应
     */

    private SqlResultResponse buildPlanResponse(ResultSet rs, long startTime, String dbType) throws SQLException {
        ResultSetMetaData metaData = rs.getMetaData();
        int columnCount = metaData.getColumnCount();

        // 获取列名
        List<String> columns = new ArrayList<>();
        for (int i = 1; i <= columnCount; i++) {
            columns.add(metaData.getColumnName(i));
        }

        // 获取数据
        List<Map<String, Object>> rows = new ArrayList<>();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= columnCount; i++) {
                Object value = rs.getObject(i);
                row.put(columns.get(i - 1), value);
            }
            rows.add(row);
        }

        long executionTime = System.currentTimeMillis() - startTime;

        SqlResultResponse response = new SqlResultResponse();
        response.setSuccess(true);
        response.setColumns(columns);
        response.setRows(rows);
        response.setAffectedRows(0);
        response.setExecutionTime(executionTime);

        log.info("执行计划查询成功，返回 {} 行，耗时: {}ms [数据库类型: {}]", rows.size(), executionTime, dbType);
        return response;
    }

    /**
     * MySQL 执行计划
     */
    private SqlResultResponse explainMysqlSql(Connection conn, String sql, long startTime) throws SQLException {
        String explainSql = "EXPLAIN " + sql;

        try (Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(explainSql)) {

            ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();

            // 获取列名
            List<String> columns = new ArrayList<>();
            for (int i = 1; i <= columnCount; i++) {
                columns.add(metaData.getColumnName(i));
            }

            // 获取数据
            List<Map<String, Object>> rows = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= columnCount; i++) {
                    row.put(columns.get(i - 1), rs.getObject(i));
                }
                rows.add(row);
            }

            long executionTime = System.currentTimeMillis() - startTime;

            SqlResultResponse response = new SqlResultResponse();
            response.setSuccess(true);
            response.setColumns(columns);
            response.setRows(rows);
            response.setAffectedRows(0);
            response.setExecutionTime(executionTime);

            log.info("执行计划查询成功，耗时: {}ms [数据库类型: POSTGRESQL]", executionTime);
            return response;
        }
    }

    /**
     * PostgreSQL 执行计划
     */
    private SqlResultResponse explainPostgreSql(Connection conn, String sql, long startTime) throws SQLException {
        String explainSql = "EXPLAIN " + sql;

        try (Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(explainSql)) {

            ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();

            // 获取列名
            List<String> columns = new ArrayList<>();
            for (int i = 1; i <= columnCount; i++) {
                columns.add(metaData.getColumnName(i));
            }

            // 获取数据
            List<Map<String, Object>> rows = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= columnCount; i++) {
                    row.put(columns.get(i - 1), rs.getObject(i));
                }
                rows.add(row);
            }

            long executionTime = System.currentTimeMillis() - startTime;

            SqlResultResponse response = new SqlResultResponse();
            response.setSuccess(true);
            response.setColumns(columns);
            response.setRows(rows);
            response.setAffectedRows(0);
            response.setExecutionTime(executionTime);

            log.info("执行计划查询成功，耗时: {}ms [数据库类型: POSTGRESQL]", executionTime);
            return response;
        }
    }

    /**
     * 智能分割SQL语句 (使用 Druid Parser)
     * 解决复杂多行注释、字符串内含分号、复杂PL/SQL块等问题
     *
     * 注意：处理 --- 行注释时需特殊逻辑——
     * 连续 3 个以上 `-` 的行属于注释，但 Druid 的 MySQL parser
     * 会把 "-- " 视为注释起点，导致 ---xxx 被错误切分为空语句。
     * 解决方案：预处理阶段将此类注释行替换为空行，保留原行位置，
     * 让 Druid 在干净的文本上做 AST 解析。
     */
    private List<String> splitSqlStatements(String sqlInput, String dbTypeStr) {
        List<String> validSqls = new ArrayList<>();
        // 必须先预处理（Druid 失败时降级路径也要用同一份文本，否则会仍带 --- 交给 MySQL）
        final String preprocessed = stripTripleDashComments(sqlInput);
        try {
            com.alibaba.druid.DbType dbType;
            if ("MYSQL".equalsIgnoreCase(dbTypeStr)) {
                dbType = com.alibaba.druid.DbType.mysql;
            } else if ("POSTGRESQL".equalsIgnoreCase(dbTypeStr) || "POSTGRES".equalsIgnoreCase(dbTypeStr) || "PG".equalsIgnoreCase(dbTypeStr)) {
                dbType = com.alibaba.druid.DbType.postgresql;
            } else if ("DM".equalsIgnoreCase(dbTypeStr)) {
                dbType = com.alibaba.druid.DbType.dm;
            } else {
                dbType = com.alibaba.druid.DbType.oracle;
            }

            // 使用 Druid 将大段 SQL 或脚本完美拆分为多个 Statement AST 节点
            List<com.alibaba.druid.sql.ast.SQLStatement> stmtList =
                    com.alibaba.druid.sql.SQLUtils.parseStatements(preprocessed, dbType);
            for (com.alibaba.druid.sql.ast.SQLStatement stmt : stmtList) {
                String sql = stmt.toString().trim();
                sql = formatSqlForJdbcExecution(sql);
                if (!sql.isEmpty() && !sql.equals("/")) {
                    validSqls.add(sql);
                }
            }
        } catch (com.alibaba.druid.sql.parser.ParserException e) {
            log.warn("Druid SQL 解析失败，降级返回原 SQL: {}", e.getMessage());
            String fallbackSql = formatSqlForJdbcExecution(preprocessed);
            if (!fallbackSql.isEmpty() && !fallbackSql.equals("/")) {
                validSqls.add(fallbackSql);
            }
        } catch (Exception e) {
            log.error("SQL 分割时发生未知异常", e);
            String fallbackSql = formatSqlForJdbcExecution(preprocessed);
            if (!fallbackSql.isEmpty() && !fallbackSql.equals("/")) {
                validSqls.add(fallbackSql);
            }
        }
        return validSqls;
    }

    /**
     * 预处理：去掉行首连续 3 个及以上 `-` 的整行（如 ---、---- 及 --- 标题）。
     * 替换为空行以尽量保持行号，避免 Druid / MySQL 把 --- 当语法。
     * 标准单行注释 `-- `（仅两个减号后接空格等）行首连续 `-` 数为 2，不处理。
     */
    private String stripTripleDashComments(String sql) {
        StringBuilder out = new StringBuilder();
        String[] lines = sql.split("\\r?\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            int dashRun = 0;
            while (dashRun < trimmed.length() && trimmed.charAt(dashRun) == '-') {
                dashRun++;
            }
            if (dashRun >= 3) {
                out.append("\n");
                continue;
            }
            out.append(line).append(i < lines.length - 1 ? "\n" : "");
        }
        return out.toString();
    }

    /**
     * 去除末尾多余的分号供 JDBC 执行（单条普通SQL不能带分号，否则报错ORA-00933等）
     * 只有像 BEGIN..END 这样的 PL/SQL 块必须保留末尾分号
     */
    private String formatSqlForJdbcExecution(String sql) {
        sql = sql.trim();
        if (sql.endsWith(";")) {
            boolean keepSemicolon = false;
            String upperSql = sql.toUpperCase();
            if (upperSql.startsWith("DECLARE") || upperSql.startsWith("BEGIN") ||
                upperSql.startsWith("CREATE OR REPLACE PROCEDURE") || upperSql.startsWith("CREATE PROCEDURE") ||
                upperSql.startsWith("CREATE OR REPLACE FUNCTION") || upperSql.startsWith("CREATE FUNCTION") ||
                upperSql.startsWith("CREATE OR REPLACE TRIGGER") || upperSql.startsWith("CREATE TRIGGER") ||
                upperSql.startsWith("CREATE OR REPLACE PACKAGE") || upperSql.startsWith("CREATE PACKAGE") ||
                upperSql.startsWith("CREATE OR REPLACE TYPE") || upperSql.startsWith("CREATE TYPE")) {
                keepSemicolon = true;
            }
            if (!keepSemicolon) {
                sql = sql.substring(0, sql.length() - 1).trim();
            }
        }
        return sql;
    }

    /**
     * 执行单条SQL语句（支持服务端分页）
     *
     * @param page     当前页码（从1开始，<=0表示不分页）
     * @param pageSize 每页行数（<=0表示不分页）
     */
    private SqlResultResponse executeSingleSql(String sql, int datasourceIndex, int maxRows, int page, int pageSize) {
        String sqlType = getSqlType(sql);
        log.info("执行 SQL [{}]: {}", sqlType, sql.length() > 100 ? sql.substring(0, 100) + "..." : sql);

        long startTime = System.currentTimeMillis();

        try (Connection conn = getConnectionByIndex(datasourceIndex)) {
            if (isResultSetSqlType(sqlType)) {
                return executeQuery(conn, sql, maxRows, page, pageSize, startTime);
            } else {
                return executeUpdate(conn, sql, sqlType, startTime);
            }
        } catch (SQLException e) {
            log.error("SQL 执行失败", e);
            return SqlResultResponse.error("执行失败: " + e.getMessage());
        }
    }

    /**
     * 批量执行多条SQL语句（支持对每条 SELECT 进行服务端分页）
     */
    private SqlResultResponse executeBatchSql(List<String> sqlList, int datasourceIndex, int maxRows, int page, int pageSize) {
        long startTime = System.currentTimeMillis();
        int totalAffectedRows = 0;
        List<SqlResultResponse> multiResults = new ArrayList<>();

        log.info("批量执行 {} 条SQL语句", sqlList.size());

        boolean hasError = false;
        String firstErrorMessage = null;

        try (Connection conn = getConnectionByIndex(datasourceIndex)) {
            conn.setAutoCommit(false); // 开启事务

            for (int i = 0; i < sqlList.size(); i++) {
                String sql = sqlList.get(i);
                String sqlType = getSqlType(sql);
                long stmtStartTime = System.currentTimeMillis();

                try {
                    SqlResultResponse res;
                    if (isResultSetSqlType(sqlType)) {
                        res = executeQuery(conn, sql, maxRows, page, pageSize, stmtStartTime);
                    } else {
                        res = executeUpdate(conn, sql, sqlType, stmtStartTime);
                        totalAffectedRows += res.getAffectedRows();
                    }
                    res.setSql(sql);
                    multiResults.add(res);
                } catch (SQLException e) {
                    conn.rollback(); // 回滚事务
                    log.error("批量执行第 {} 条SQL失败: {}", i + 1, sql, e);
                    hasError = true;
                    firstErrorMessage = "执行第 " + (i + 1) + " 条SQL失败: " + e.getMessage();
                    
                    SqlResultResponse errRes = SqlResultResponse.error(firstErrorMessage);
                    errRes.setSql(sql);
                    multiResults.add(errRes);
                    break; // 失败后中止后续执行
                }
            }

            if (!hasError) {
                conn.commit(); // 全部成功才提交事务
            }

            long executionTime = System.currentTimeMillis() - startTime;
            log.info("批量执行{}, 共 {} 条SQL，总影响 {} 行，耗时 {}ms", 
                     hasError ? "失败" : "完成", sqlList.size(), totalAffectedRows, executionTime);

            SqlResultResponse finalResponse = new SqlResultResponse();
            finalResponse.setBatch(true);
            finalResponse.setMultiResults(multiResults);
            finalResponse.setExecutionTime(executionTime);
            
            if (hasError) {
                finalResponse.setSuccess(false);
                finalResponse.setErrorMessage("批量执行异常: " + firstErrorMessage);
            } else {
                finalResponse.setSuccess(true);
                finalResponse.setAffectedRows(totalAffectedRows);
                finalResponse.setRowCount(multiResults.size()); 
            }
            return finalResponse;

        } catch (SQLException e) {
            log.error("批量SQL执行失败", e);
            return SqlResultResponse.error("批量执行失败: " + e.getMessage());
        }
    }

    /**
     * 执行查询语句（支持服务端分页）
     *
     * @param page     当前页码（从1开始，<=0表示不分页）
     * @param pageSize 每页行数（<=0表示不分页）
     */
    private SqlResultResponse executeQuery(Connection conn, String sql, int maxRows, int page, int pageSize, long startTime)
            throws SQLException {
        OracleConfig.QueryConfig queryConfig = oracleConfig.getQuery();
        String dbType = getDatasourceTypeByConn(conn);

        // 安全清理：去除末尾分号，防止 ORA-00911
        String safeSql = sql.trim();
        while (safeSql.endsWith(";")) {
            safeSql = safeSql.substring(0, safeSql.length() - 1).trim();
        }

        // 判断是否启用分页：pageSize > 0 且是纯 SELECT语句（非 WITH/CTE）时才分页
        boolean doPaging = pageSize > 0 && page >= 1 && "SELECT".equals(getSqlType(safeSql));

        // WITH 子句（CTE）包裹 COUNT 在 Oracle 里不合法，降级
        if (doPaging) {
            String upper = safeSql.toUpperCase();
            if (upper.startsWith("WITH ") || upper.startsWith("WITH\t") || upper.startsWith("WITH\n")) {
                log.warn("WITH 子句（CTE）暂不支持服务端分页，降级为全量查询");
                doPaging = false;
            }
        }

        if (doPaging) {
            // ========== 服务端分页逻辑 ==========

            // 1. 查询总数（失败则降级为全量）
            long totalCount = -1;
            try (Statement cntStmt = conn.createStatement()) {
                cntStmt.setQueryTimeout(queryConfig.getMaxQueryTimeout());
                String countSql = "SELECT COUNT(*) FROM (" + safeSql + ") CNT_WRAP";
                try (ResultSet cntRs = cntStmt.executeQuery(countSql)) {
                    if (cntRs.next()) {
                        totalCount = cntRs.getLong(1);
                    }
                }
            } catch (SQLException e) {
                log.warn("COUNT 查询失败，降级为全量查询: {}", e.getMessage());
                doPaging = false;
            }

            if (doPaging && totalCount >= 0) {
                int totalPages = totalCount == 0 ? 1 : (int) Math.ceil((double) totalCount / pageSize);
                if (page > totalPages) page = totalPages;

                // 2. 构建分页 SQL
                int offset = (page - 1) * pageSize;
                String pagedSql;
                if ("MYSQL".equalsIgnoreCase(dbType)
                        || "POSTGRESQL".equalsIgnoreCase(dbType)
                        || "POSTGRES".equalsIgnoreCase(dbType)
                        || "PG".equalsIgnoreCase(dbType)) {
                    pagedSql = "SELECT * FROM (" + safeSql + ") PAGE_WRAP LIMIT " + pageSize + " OFFSET " + offset;
                } else {
                    // Oracle / 达梦(DM) 用 ROWNUM 双层嵌套
                    pagedSql = "SELECT * FROM ("
                            + "  SELECT T_INNER.*, ROWNUM AS RN_COL FROM (" + safeSql + ") T_INNER"
                            + "  WHERE ROWNUM <= " + (offset + pageSize)
                            + ") WHERE RN_COL > " + offset;
                }

                // 3. 执行分页查询
                try (Statement stmt = conn.createStatement()) {
                    stmt.setQueryTimeout(queryConfig.getMaxQueryTimeout());
                    stmt.setFetchSize(queryConfig.getFetchSize());
                    try (ResultSet rs = stmt.executeQuery(pagedSql)) {
                        ResultSetMetaData metaData = rs.getMetaData();
                        int columnCount = metaData.getColumnCount();

                        // 过滤掉 ROWNUM 辅助列 __rn__
                        List<String> columns = new ArrayList<>();
                        for (int i = 1; i <= columnCount; i++) {
                            String colLabel = metaData.getColumnLabel(i);
                            if (!"RN_COL".equalsIgnoreCase(colLabel)) {
                                columns.add(colLabel);
                            }
                        }

                        List<Map<String, Object>> rows = new ArrayList<>();
                        while (rs.next()) {
                            Map<String, Object> row = new LinkedHashMap<>();
                            for (String col : columns) {
                                int colIdx = rs.findColumn(col);
                                row.put(col, getColumnValue(rs, colIdx, metaData.getColumnType(colIdx)));
                            }
                            rows.add(row);
                        }

                        long executionTime = System.currentTimeMillis() - startTime;
                        log.info("分页查询完成：page={}/{}, pageSize={}, totalCount={}, 本页{}行, 耗时{}ms",
                                page, totalPages, pageSize, totalCount, rows.size(), executionTime);

                        SqlResultResponse response = SqlResultResponse.success(columns, rows, executionTime);
                        response.setTotalCount(totalCount);
                        response.setTotalPages(totalPages);
                        response.setCurrentPage(page);
                        response.setPageSize(pageSize);
                        return response;
                    }
                }
            }
        }

        // ========== 非分页（全量/降级）逻辑 ==========
        try (Statement stmt = conn.createStatement()) {
            int effectiveMaxRows = maxRows > 0 ? maxRows : queryConfig.getDefaultMaxRows();
            stmt.setMaxRows(effectiveMaxRows);
            stmt.setQueryTimeout(queryConfig.getMaxQueryTimeout());
            stmt.setFetchSize(queryConfig.getFetchSize());

            try (ResultSet rs = stmt.executeQuery(safeSql)) {
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
                        row.put(columns.get(i - 1), getColumnValue(rs, i, metaData.getColumnType(i)));
                    }
                    rows.add(row);
                }

                long executionTime = System.currentTimeMillis() - startTime;
                String limitMsg = (effectiveMaxRows > 0 && rows.size() >= effectiveMaxRows) ? " (达到行数限制)" : "";
                log.info("查询完成，返回 {} 行{}, 耗时 {}ms", rows.size(), limitMsg, executionTime);
                return SqlResultResponse.success(columns, rows, executionTime);
            }
        }
    }

    /**
     * 根据连接获取数据库类型（通过已缓存的连接池Map反查）
     */
    private String getDatasourceTypeByConn(Connection conn) {
        // 尝试从DatabaseMetaData获取产品名
        try {
            String productName = conn.getMetaData().getDatabaseProductName().toUpperCase();
            if (productName.contains("ORACLE")) return "ORACLE";
            if (productName.contains("MySQL") || productName.contains("MYSQL")) return "MYSQL";
            if (productName.contains("DM") || productName.contains("达梦")) return "DM";
            if (productName.contains("POSTGRESQL") || productName.contains("POSTGRES")) return "POSTGRESQL";
        } catch (Exception ignored) {}
        return "ORACLE"; // 默认
    }


    /**
     * 执行更新语句（INSERT/UPDATE/DELETE）
     */
    private SqlResultResponse executeUpdate(Connection conn, String sql, String sqlType, long startTime)
            throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            // 设置超时
            stmt.setQueryTimeout(oracleConfig.getQuery().getMaxQueryTimeout());

            int affectedRows = stmt.executeUpdate(sql);
            long executionTime = System.currentTimeMillis() - startTime;

            log.info("{} 执行完成，影响 {} 行，耗时 {}ms", sqlType, affectedRows, executionTime);

            return SqlResultResponse.successUpdate(affectedRows, sqlType, executionTime);
        }
    }

    /**
     * 获取列值（处理特殊类型）
     */
    private Object getColumnValue(ResultSet rs, int columnIndex, int columnType) throws SQLException {
        Object value = rs.getObject(columnIndex);

        if (value == null) {
            return null;
        }

        // 处理特殊类型
        switch (columnType) {
            case Types.CLOB:
            case Types.NCLOB:
                Clob clob = rs.getClob(columnIndex);
                if (clob != null) {
                    // 限制 CLOB 读取长度，防止 OOM
                    long len = clob.length();
                    // 默认读取前2000字符，足够预览
                    return clob.getSubString(1, (int) Math.min(len, 2000));
                }
                return null;
            case Types.BLOB:
                return "[BLOB]";
            case Types.DATE:
            case Types.TIMESTAMP:
            case Types.TIMESTAMP_WITH_TIMEZONE:
            case Types.ROWID:
                return value.toString();
            default:
                // 兼容 Oracle 未明确映射为 Types.ROWID 的 RowId 类型
                if (value instanceof java.sql.RowId || "oracle.sql.ROWID".equals(value.getClass().getName())) {
                    return value.toString();
                }
                return value;
        }
    }

    /**
     * 获取数据源配置
     */
    private OracleConfig.OracleDataSource getDataSourceConfig(int index) {
        List<OracleConfig.OracleDataSource> datasources = oracleConfig.getDatasources();
        if (datasources == null || index < 0 || index >= datasources.size()) {
            return null;
        }
        return datasources.get(index);
    }

    /**
     * 根据索引获取数据库连接（使用连接池）
     */
    private Connection getConnectionByIndex(int index) throws SQLException {
        HikariDataSource ds = dataSourcePools.get(index);

        // 如果连接池不存在，则创建（懒加载）
        if (ds == null) {
            synchronized (dataSourcePools) {
                // 双重检查
                ds = dataSourcePools.get(index);
                if (ds == null) {
                    OracleConfig.OracleDataSource config = getDataSourceConfig(index);
                    if (config == null) {
                        throw new SQLException("数据源配置不存在: index=" + index);
                    }
                    ds = createDataSourcePool(config);
                    dataSourcePools.put(index, ds);

                    // 缓存数据库类型
                    String dbType = config.getType() != null ? config.getType().toUpperCase() : "ORACLE";
                    dataSourceTypes.put(index, dbType);

                    // 缓存MySQL数据库名称
                    if ("MYSQL".equals(dbType)) {
                        String database = DatabaseDialect.extractDatabaseFromUrl(config.getUrl());
                        if (database != null) {
                            dataSourceDatabases.put(index, database);
                        }
                    }
                }
            }
        }

        return ds.getConnection();
    }

    /**
     * 创建HikariCP连接池（支持Oracle、达梦、MySQL数据库）
     */
    private HikariDataSource createDataSourcePool(OracleConfig.OracleDataSource config) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(config.getUrl());
        hikariConfig.setUsername(config.getUsername());
        hikariConfig.setPassword(config.getPassword());

        // 根据数据库类型设置驱动和测试查询
        String dbType = config.getType() != null ? config.getType().toUpperCase() : "ORACLE";
        if ("MYSQL".equals(dbType)) {
            // MySQL数据库
            hikariConfig.setDriverClassName("com.mysql.cj.jdbc.Driver");
            hikariConfig.setConnectionTestQuery("SELECT 1");
            hikariConfig.setPoolName("MySQLPool-" + config.getName());
            log.info("初始化MySQL数据库连接池: {}, URL: {}", config.getName(), config.getUrl());
        } else if ("DM".equals(dbType)) {
            // 达梦数据库
            hikariConfig.setDriverClassName("dm.jdbc.driver.DmDriver");
            hikariConfig.setConnectionTestQuery("SELECT 1");
            hikariConfig.setPoolName("DMPool-" + config.getName());
            log.info("初始化达梦数据库连接池: {}, URL: {}", config.getName(), config.getUrl());
        } else if ("POSTGRESQL".equals(dbType) || "POSTGRES".equals(dbType) || "PG".equals(dbType)) {
            // PostgreSQL数据库
            hikariConfig.setDriverClassName("org.postgresql.Driver");
            hikariConfig.setConnectionTestQuery("SELECT 1");
            hikariConfig.setPoolName("PostgreSQLPool-" + config.getName());
            log.info("初始化PostgreSQL数据库连接池: {}, URL: {}", config.getName(), config.getUrl());
        } else {
            // Oracle数据库（默认）
            hikariConfig.setDriverClassName("oracle.jdbc.OracleDriver");
            hikariConfig.setConnectionTestQuery("SELECT 1 FROM DUAL");
            hikariConfig.setPoolName("OraclePool-" + config.getName());
            log.info("初始化Oracle数据库连接池: {}, URL: {}", config.getName(), config.getUrl());
        }

        // 读取配置
        OracleConfig.PoolConfig poolConfig = oracleConfig.getPool();
        hikariConfig.setMaximumPoolSize(poolConfig.getMaximumPoolSize());
        hikariConfig.setMinimumIdle(poolConfig.getMinimumIdle());
        hikariConfig.setConnectionTimeout(poolConfig.getConnectionTimeout());
        hikariConfig.setIdleTimeout(poolConfig.getIdleTimeout());
        hikariConfig.setMaxLifetime(poolConfig.getMaxLifetime());

        return new HikariDataSource(hikariConfig);
    }

    /**
     * 关闭数据库资源
     */
    private void closeResources(ResultSet rs, Statement stmt, Connection conn) {
        try {
            if (rs != null)
                rs.close();
        } catch (Exception e) {
            log.error("关闭 ResultSet 失败", e);
        }
        try {
            if (stmt != null)
                stmt.close();
        } catch (Exception e) {
            log.error("关闭 Statement 失败", e);
        }
        try {
            if (conn != null)
                conn.close(); // 归还连接给池
        } catch (Exception e) {
            log.error("归还 Connection 失败", e);
        }
    }

    /**
     * 应用销毁时关闭所有连接池
     */
    @PreDestroy
    public void destroy() {
        log.info("关闭所有数据库连接池（主池 + Slave 池）...");
        for (HikariDataSource ds : dataSourcePools.values()) {
            closePoolSafely(ds);
        }
        dataSourcePools.clear();
        for (HikariDataSource ds : slavePools.values()) {
            closePoolSafely(ds);
        }
        slavePools.clear();
        slavePoolStartTimes.clear();
        lockQueryExecutor.shutdown();
    }

    /**
     * 判断 SQL 类型
     */
    private String getSqlType(String sql) {
        String upperSql = sql.toUpperCase().trim();
        // 跳过行首注释行（如 -- 注释、/* 块注释），找到第一条实际 SQL 关键字行
        String effectiveLine = null;
        for (String line : upperSql.split("\\r?\\n")) {
            String trimmed = line.trim();
            // 跳过空行和注释行
            if (trimmed.isEmpty() || trimmed.startsWith("--") || trimmed.startsWith("/*")) {
                continue;
            }
            effectiveLine = trimmed;
            break;
        }
        if (effectiveLine == null) {
            effectiveLine = upperSql.split("\\r?\\n")[0].trim(); // fallback
        }
        if (effectiveLine.startsWith("SELECT") || effectiveLine.startsWith("WITH")) {
            return "SELECT";
        } else if (effectiveLine.startsWith("SHOW")) {
            return "SHOW";
        } else if (effectiveLine.startsWith("DESC")) {
            return "DESC";
        } else if (effectiveLine.startsWith("DESCRIBE")) {
            return "DESCRIBE";
        } else if (effectiveLine.startsWith("EXPLAIN")) {
            return "EXPLAIN";
        } else if (effectiveLine.startsWith("INSERT")) {
            return "INSERT";
        } else if (effectiveLine.startsWith("UPDATE")) {
            return "UPDATE";
        } else if (effectiveLine.startsWith("DELETE")) {
            return "DELETE";
        } else if (effectiveLine.startsWith("CREATE")) {
            return "CREATE";
        } else if (effectiveLine.startsWith("DROP")) {
            return "DROP";
        } else if (effectiveLine.startsWith("ALTER")) {
            return "ALTER";
        } else {
            return "OTHER";
        }
    }

    private boolean isResultSetSqlType(String sqlType) {
        return "SELECT".equals(sqlType)
                || "SHOW".equals(sqlType)
                || "DESC".equals(sqlType)
                || "DESCRIBE".equals(sqlType)
                || "EXPLAIN".equals(sqlType);
    }

    @Override
    // 禁用缓存，每次都从数据库查询最新数据
    // @org.springframework.cache.annotation.Cacheable(value = "oracleObjects", key
    // = "#type + '_' + #datasourceIndex", unless = "#result == null ||
    // #result.isEmpty()")
    public List<Map<String, String>> getDatabaseObjects(String type, int datasourceIndex) {
        List<Map<String, String>> result = new ArrayList<>();

        // 获取数据库类型和数据库名称（MySQL需要）
        String dbType = dataSourceTypes.getOrDefault(datasourceIndex, "ORACLE");
        String database = dataSourceDatabases.getOrDefault(datasourceIndex, null);

        // 根据类型和数据库类型构建不同的查询 SQL
        String sql = DatabaseDialect.buildObjectQuerySql(type, dbType, database);
        if (sql == null) {
            log.warn("不支持的对象类型: {}", type);
            return result;
        }

        Connection conn = null;
        Statement stmt = null;
        ResultSet rs = null;

        try {
            conn = getConnectionByIndex(datasourceIndex);
            stmt = conn.createStatement();
            // 设置较短的超时，避免元数据查询卡死
            stmt.setQueryTimeout(30);

            rs = stmt.executeQuery(sql);

            while (rs.next()) {
                Map<String, String> obj = new HashMap<>();
                obj.put("name", rs.getString(1));
                result.add(obj);
            }

            log.info("获取 {} 列表成功，共 {} 个对象 [数据库类型: {}]", type, result.size(), dbType);
        } catch (Exception e) {
            log.error("获取数据库对象列表失败: type={}, dbType={}", type, dbType, e);
        } finally {
            closeResources(rs, stmt, conn);
        }

        return result;
    }

    @Override
    public String getObjectDDL(String type, String name, int datasourceIndex) {
        // 表类型需要获取完整DDL（包括索引、触发器等）
        if ("tables".equalsIgnoreCase(type)) {
            return getCompleteTableDDL(name, datasourceIndex);
        }

        // 获取数据库类型
        String dbType = dataSourceTypes.getOrDefault(datasourceIndex, "ORACLE");

        // PostgreSQL使用不同的DDL获取方式
        if (DatabaseDialect.isPostgreSQL(dbType)) {
            return getPostgreSQLObjectDDL(type, name, datasourceIndex);
        }

        // MySQL使用不同的DDL获取方式
        if (DatabaseDialect.isMySQL(dbType)) {
            return getMySQLObjectDDL(type, name, datasourceIndex);
        }

        // 其他对象类型的DDL获取(Oracle/DM)
        String ddl = "";
        Connection conn = null;
        CallableStatement cstmt = null;

        try {
            conn = getConnectionByIndex(datasourceIndex);

            // 使用 DBMS_METADATA.GET_DDL 获取DDL
            String objectType = getOracleObjectType(type);
            if (objectType == null) {
                return "-- 不支持的对象类型: " + type;
            }

            String sql = "BEGIN ? := DBMS_METADATA.GET_DDL(?, ?); END;";
            cstmt = conn.prepareCall(sql);
            cstmt.setQueryTimeout(60); // 设置DDL获取超时

            cstmt.registerOutParameter(1, java.sql.Types.CLOB);
            cstmt.setString(2, objectType);
            cstmt.setString(3, name.toUpperCase());

            try {
                cstmt.execute();
            } catch (java.sql.SQLException sqlEx) {
                // 对于procedures类型，如果使用PROCEDURE失败，尝试FUNCTION
                if ("procedures".equalsIgnoreCase(type) && sqlEx.getMessage().contains("ORA-31603")) {
                    log.info("对象 {} 不是PROCEDURE类型，尝试使用FUNCTION类型获取DDL", name);
                    if (cstmt != null)
                        cstmt.close();

                    cstmt = conn.prepareCall(sql);
                    cstmt.setQueryTimeout(60);
                    cstmt.registerOutParameter(1, java.sql.Types.CLOB);
                    cstmt.setString(2, "FUNCTION");
                    cstmt.setString(3, name.toUpperCase());
                    cstmt.execute();
                } else {
                    throw sqlEx;
                }
            }

            java.sql.Clob clob = cstmt.getClob(1);

            // 安全读取CLOB
            if (clob != null) {
                // 限制DDL最大长度，防止极其巨大的DDL导致问题
                long length = clob.length();
                if (length > 100000) { // 超过100KB截断提示
                    ddl = clob.getSubString(1, 100000) + "\n\n-- (DDL过长，已截断显示)";
                } else {
                    ddl = clob.getSubString(1, (int) length);
                }
            }

            log.info("获取 {} DDL成功: {}", name, type);
        } catch (Exception e) {
            log.error("获取DDL失败: type={}, name={}", type, name, e);
            ddl = "-- 获取DDL失败: " + e.getMessage();
        } finally {
            try {
                if (cstmt != null)
                    cstmt.close();
                if (conn != null)
                    conn.close();
            } catch (Exception e) {
                log.error("关闭资源失败", e);
            }
        }

        return ddl;
    }

    /**
     * 获取表的完整DDL（包括索引、触发器、约束等）
     */
    private String getCompleteTableDDL(String tableName, int datasourceIndex) {
        StringBuilder fullDDL = new StringBuilder();
        Connection conn = null;

        try {
            conn = getConnectionByIndex(datasourceIndex);
            String tableNameUpper = tableName.toUpperCase();

            // 1. 获取建表语句
            fullDDL.append("-- ==========================================\n");
            fullDDL.append("-- 表: ").append(tableNameUpper).append("\n");
            fullDDL.append("-- ==========================================\n\n");
            fullDDL.append(getDDLByMetadata(conn, "TABLE", tableNameUpper));
            fullDDL.append("\n\n");

            // 2. 获取索引
            try {
                fullDDL.append("-- ==========================================\n");
                fullDDL.append("-- 索引\n");
                fullDDL.append("-- ==========================================\n\n");
                fullDDL.append(getTableIndexesDDL(conn, tableNameUpper));
            } catch (Exception e) {
                log.warn("获取索引失败: {}", e.getMessage());
                fullDDL.append("-- 获取索引失败: ").append(e.getMessage()).append("\n\n");
            }

            // 3. 获取触发器
            try {
                fullDDL.append("\n-- ==========================================\n");
                fullDDL.append("-- 触发器\n");
                fullDDL.append("-- ==========================================\n\n");
                fullDDL.append(getTableTriggersDDL(conn, tableNameUpper));
            } catch (Exception e) {
                log.warn("获取触发器失败(可能是字符集原因，请检查orai18n依赖): {}", e.getMessage());
                fullDDL.append("-- 获取触发器失败: ").append(e.getMessage()).append("\n\n");
            }

            // 4. 获取注释
            try {
                fullDDL.append("\n-- ==========================================\n");
                fullDDL.append("-- 注释\n");
                fullDDL.append("-- ==========================================\n\n");
                fullDDL.append(getTableCommentsDDL(conn, tableNameUpper));
            } catch (Exception e) {
                log.warn("获取注释失败: {}", e.getMessage());
                fullDDL.append("-- 获取注释失败: ").append(e.getMessage()).append("\n\n");
            }

            log.info("获取表 {} 完整DDL成功", tableNameUpper);
        } catch (Exception e) {
            log.error("获取表完整DDL失败: {}", tableName, e);
            // 即使核心失败，也尝试返回已构建的部分
            if (fullDDL.length() > 0) {
                fullDDL.append("\n-- (后续获取失败: ").append(e.getMessage()).append(")");
                return fullDDL.toString();
            }
            return "-- 获取表完整DDL失败: " + e.getMessage();
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    log.error("关闭连接失败", e);
                }
            }
        }

        return fullDDL.toString();
    }

    /**
     * 使用DBMS_METADATA获取对象DDL
     */
    private String getDDLByMetadata(Connection conn, String objectType, String objectName) throws SQLException {
        // 设置DBMS_METADATA参数，确保包含完整信息（分区、存储、约束等）
        try (CallableStatement setStmt = conn.prepareCall("BEGIN " +
                "DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM, 'STORAGE', FALSE); " +
                "DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM, 'TABLESPACE', FALSE); " +
                "DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM, 'SEGMENT_ATTRIBUTES', FALSE); " +
                "DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM, 'SQLTERMINATOR', TRUE); " +
                "DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM, 'PRETTY', TRUE); " +
                "END;")) {
            setStmt.execute();
        } catch (SQLException e) {
            log.warn("设置DBMS_METADATA参数失败，继续获取DDL: {}", e.getMessage());
        }

        String sql = "BEGIN ? := DBMS_METADATA.GET_DDL(?, ?); END;";
        try (CallableStatement cstmt = conn.prepareCall(sql)) {
            cstmt.registerOutParameter(1, java.sql.Types.CLOB);
            cstmt.setString(2, objectType);
            cstmt.setString(3, objectName);
            cstmt.execute();

            java.sql.Clob clob = cstmt.getClob(1);
            if (clob != null) {
                return clob.getSubString(1, (int) clob.length());
            }
        } catch (SQLException e) {
            return "-- 获取" + objectType + " DDL失败: " + e.getMessage();
        }
        return "";
    }

    /**
     * 获取表的所有索引DDL
     */
    private String getTableIndexesDDL(Connection conn, String tableName) throws SQLException {
        StringBuilder indexDDL = new StringBuilder();
        String sql = "SELECT INDEX_NAME FROM USER_INDEXES WHERE TABLE_NAME = ? AND INDEX_NAME NOT IN " +
                "(SELECT CONSTRAINT_NAME FROM USER_CONSTRAINTS WHERE TABLE_NAME = ? AND CONSTRAINT_TYPE IN ('P', 'U'))";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName);
            ps.setString(2, tableName);
            ResultSet rs = ps.executeQuery();

            while (rs.next()) {
                String indexName = rs.getString("INDEX_NAME");
                try {
                    String ddl = getDDLByMetadata(conn, "INDEX", indexName);
                    indexDDL.append(ddl).append("\n/\n\n");
                } catch (Exception e) {
                    indexDDL.append("-- 获取索引 ").append(indexName).append(" DDL失败\n\n");
                }
            }
        }

        return indexDDL.length() > 0 ? indexDDL.toString() : "-- 无索引\n";
    }

    /**
     * 获取表的所有触发器DDL
     */
    private String getTableTriggersDDL(Connection conn, String tableName) throws SQLException {
        StringBuilder triggerDDL = new StringBuilder();
        String sql = "SELECT TRIGGER_NAME FROM USER_TRIGGERS WHERE TABLE_NAME = ?";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName);
            ResultSet rs = ps.executeQuery();

            while (rs.next()) {
                String triggerName = rs.getString("TRIGGER_NAME");
                try {
                    String ddl = getDDLByMetadata(conn, "TRIGGER", triggerName);
                    triggerDDL.append(ddl).append("\n/\n\n");
                } catch (Exception e) {
                    triggerDDL.append("-- 获取触发器 ").append(triggerName).append(" DDL失败\n\n");
                }
            }
        }

        return triggerDDL.length() > 0 ? triggerDDL.toString() : "-- 无触发器\n";
    }

    /**
     * 获取表和列的注释DDL
     */
    private String getTableCommentsDDL(Connection conn, String tableName) throws SQLException {
        StringBuilder commentDDL = new StringBuilder();

        // 表注释
        String tableSql = "SELECT COMMENTS FROM USER_TAB_COMMENTS WHERE TABLE_NAME = ? AND COMMENTS IS NOT NULL";
        try (PreparedStatement ps = conn.prepareStatement(tableSql)) {
            ps.setString(1, tableName);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String comment = rs.getString("COMMENTS");
                commentDDL.append("COMMENT ON TABLE ").append(tableName)
                        .append(" IS '").append(comment.replace("'", "''")).append("';\n");
            }
        }

        // 列注释
        String colSql = "SELECT COLUMN_NAME, COMMENTS FROM USER_COL_COMMENTS WHERE TABLE_NAME = ? AND COMMENTS IS NOT NULL";
        try (PreparedStatement ps = conn.prepareStatement(colSql)) {
            ps.setString(1, tableName);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String columnName = rs.getString("COLUMN_NAME");
                String comment = rs.getString("COMMENTS");
                commentDDL.append("COMMENT ON COLUMN ").append(tableName).append(".").append(columnName)
                        .append(" IS '").append(comment.replace("'", "''")).append("';\n");
            }
        }

        return commentDDL.length() > 0 ? commentDDL.toString() : "-- 无注释\n";
    }

    /**
     * 将对象类型转换为Oracle DBMS_METADATA需要的类型名称
     */
    private String getOracleObjectType(String type) {
        switch (type.toLowerCase()) {
            case "views":
                return "VIEW";
            case "indexes":
                return "INDEX";
            case "procedures":
                return "PROCEDURE";
            case "triggers":
                return "TRIGGER";
            case "sequences":
                return "SEQUENCE";
            default:
                return null;
        }
    }

    @Override
    public void clearObjectsCache(int datasourceIndex) {
        // 缓存已禁用，此方法仅保留日志
        log.info("清除数据源 {} 的对象缓存（已禁用缓存，无需清除）", datasourceIndex);
    }

    @Override
    public List<Map<String, Object>> getTableStructure(String tableName, int datasourceIndex) {
        List<Map<String, Object>> result = new ArrayList<>();
        Connection conn = null;
        Statement stmt = null;
        ResultSet rs = null;

        try {
            conn = getConnectionByIndex(datasourceIndex);
            stmt = conn.createStatement();
            stmt.setQueryTimeout(30);

            // 获取数据库类型和数据库名称（MySQL需要）并构建查询SQL
            String dbType = dataSourceTypes.getOrDefault(datasourceIndex, "ORACLE");
            String database = dataSourceDatabases.getOrDefault(datasourceIndex, null);
            String sql = DatabaseDialect.buildTableStructureQuerySql(tableName, dbType, database);

            rs = stmt.executeQuery(sql);

            while (rs.next()) {
                Map<String, Object> column = new LinkedHashMap<>();
                // 使用列索引而不是列名，避免ZHS16GBK字符集问题
                column.put("columnName", rs.getString(1)); // COLUMN_NAME
                column.put("dataType", rs.getString(2)); // DATA_TYPE
                column.put("nullable", rs.getString(3)); // NULLABLE
                column.put("defaultValue", rs.getString(4)); // DATA_DEFAULT
                column.put("comments", rs.getString(5)); // COMMENTS
                column.put("isPrimaryKey", "Y".equals(rs.getString(6))); // IS_PRIMARY_KEY
                result.add(column);
            }

            log.info("获取表 {} 结构成功，共 {} 列 [数据库类型: {}]", tableName, result.size(), dbType);
        } catch (Exception e) {
            log.error("获取表结构失败: tableName={}", tableName, e);
        } finally {
            closeResources(rs, stmt, conn);
        }

        return result;
    }

    @Override
    public List<Map<String, Object>> getTableIndexes(String tableName, int datasourceIndex) {
        List<Map<String, Object>> result = new ArrayList<>();
        Connection conn = null;
        Statement stmt = null;
        ResultSet rs = null;

        try {
            conn = getConnectionByIndex(datasourceIndex);
            stmt = conn.createStatement();
            stmt.setQueryTimeout(30);

            // 获取数据库类型和数据库名称（MySQL需要）并构建查询SQL
            String dbType = dataSourceTypes.getOrDefault(datasourceIndex, "ORACLE");
            String database = dataSourceDatabases.getOrDefault(datasourceIndex, null);
            String sql = DatabaseDialect.buildTableIndexesQuerySql(tableName, dbType, database);

            rs = stmt.executeQuery(sql);

            while (rs.next()) {
                Map<String, Object> index = new LinkedHashMap<>();
                index.put("indexName", rs.getString(1));
                index.put("columnName", rs.getString(2));
                index.put("uniqueness", rs.getString(3));
                index.put("indexType", rs.getString(4));
                result.add(index);
            }

            log.info("获取表 {} 索引成功，共 {} 个索引 [数据库类型: {}]", tableName, result.size(), dbType);
        } catch (Exception e) {
            log.error("获取表索引失败: tableName={}", tableName, e);
        } finally {
            closeResources(rs, stmt, conn);
        }

        return result;
    }

    @Override
    public String getCreateTableSQL(String tableName, int datasourceIndex) {
        // 直接使用完整DDL生成方法（包含索引、触发器、分区、注释等）
        return getCompleteTableDDL(tableName, datasourceIndex);
    }

    @Override
    public List<Map<String, Object>> getTablePartitions(String tableName, int datasourceIndex) {
        List<Map<String, Object>> result = new ArrayList<>();
        Connection conn = null;
        Statement stmt = null;
        ResultSet rs = null;

        try {
            conn = getConnectionByIndex(datasourceIndex);
            stmt = conn.createStatement();
            stmt.setQueryTimeout(30);

            String dbType = dataSourceTypes.getOrDefault(datasourceIndex, "ORACLE");
            String database = dataSourceDatabases.getOrDefault(datasourceIndex, null);
            String sql = DatabaseDialect.buildTablePartitionsQuerySql(tableName, dbType, database);

            rs = stmt.executeQuery(sql);

            while (rs.next()) {
                Map<String, Object> partition = new LinkedHashMap<>();
                partition.put("partitionName", rs.getString(1)); // PARTITION_NAME
                partition.put("partitionType", rs.getString(2)); // PARTITION_TYPE
                partition.put("partitionKey", rs.getString(3)); // PARTITION_KEY
                partition.put("partitionValue", rs.getString(4)); // PARTITION_VALUE
                partition.put("subPartitionCount", rs.getInt(5)); // SUBPARTITION_COUNT
                result.add(partition);
            }

            log.info("获取表 {} 的分区信息成功，共 {} 个分区 [数据库类型: {}]", tableName, result.size(), dbType);
        } catch (Exception e) {
            log.error("获取表分区信息失败: tableName={}", tableName, e);
        } finally {
            closeResources(rs, stmt, conn);
        }

        return result;
    }

    @Override
    public List<Map<String, Object>> getTableTriggers(String tableName, int datasourceIndex) {
        List<Map<String, Object>> result = new ArrayList<>();
        Connection conn = null;
        Statement stmt = null;
        ResultSet rs = null;

        try {
            conn = getConnectionByIndex(datasourceIndex);
            stmt = conn.createStatement();
            stmt.setQueryTimeout(30);

            String dbType = dataSourceTypes.getOrDefault(datasourceIndex, "ORACLE");
            String database = dataSourceDatabases.getOrDefault(datasourceIndex, null);
            String sql = DatabaseDialect.buildTableTriggersQuerySql(tableName, dbType, database);

            rs = stmt.executeQuery(sql);

            while (rs.next()) {
                Map<String, Object> trigger = new LinkedHashMap<>();
                trigger.put("triggerName", rs.getString(1)); // TRIGGER_NAME
                trigger.put("triggeringEvent", rs.getString(2)); // TRIGGERING_EVENT
                trigger.put("triggerType", rs.getString(3)); // TRIGGER_TYPE
                trigger.put("status", rs.getString(4)); // STATUS
                result.add(trigger);
            }

            log.info("获取表 {} 的触发器信息成功，共 {} 个触发器 [数据库类型: {}]", tableName, result.size(), dbType);
        } catch (Exception e) {
            log.error("获取表触发器信息失败: tableName={}", tableName, e);
        } finally {
            closeResources(rs, stmt, conn);
        }

        return result;
    }

    /**
     * 获取PostgreSQL对象的DDL
     */
    private String getPostgreSQLObjectDDL(String type, String name, int datasourceIndex) {
        StringBuilder ddl = new StringBuilder();
        Connection conn = null;
        Statement stmt = null;
        ResultSet rs = null;

        try {
            conn = getConnectionByIndex(datasourceIndex);
            stmt = conn.createStatement();
            stmt.setQueryTimeout(30);

            switch (type.toLowerCase()) {
                case "views":
                    // 获取视图定义
                    String viewSql = String.format(
                            "SELECT 'CREATE OR REPLACE VIEW ' || viewname || ' AS ' || chr(10) || definition AS ddl " +
                                    "FROM pg_catalog.pg_views " +
                                    "WHERE schemaname = 'public' AND viewname = '%s'",
                            name);
                    rs = stmt.executeQuery(viewSql);
                    if (rs.next()) {
                        ddl.append(rs.getString("ddl")).append(";");
                    }
                    break;

                case "indexes":
                    // 获取索引定义
                    String indexSql = String.format(
                            "SELECT indexdef AS ddl " +
                                    "FROM pg_catalog.pg_indexes " +
                                    "WHERE schemaname = 'public' AND indexname = '%s'",
                            name);
                    rs = stmt.executeQuery(indexSql);
                    if (rs.next()) {
                        ddl.append(rs.getString("ddl")).append(";");
                    }
                    break;

                case "sequences":
                    // 获取序列定义
                    String seqSql = String.format(
                            "SELECT 'CREATE SEQUENCE ' || sequencename || chr(10) || " +
                                    "  '  START WITH ' || start_value || chr(10) || " +
                                    "  '  INCREMENT BY ' || increment_by || chr(10) || " +
                                    "  '  MINVALUE ' || min_value || chr(10) || " +
                                    "  '  MAXVALUE ' || max_value || chr(10) || " +
                                    "  '  CACHE ' || cache_size || chr(10) || " +
                                    "  CASE WHEN cycle THEN '  CYCLE' ELSE '  NO CYCLE' END AS ddl " +
                                    "FROM pg_catalog.pg_sequences " +
                                    "WHERE schemaname = 'public' AND sequencename = '%s'",
                            name);
                    rs = stmt.executeQuery(seqSql);
                    if (rs.next()) {
                        ddl.append(rs.getString("ddl")).append(";");
                    }
                    break;

                case "procedures":
                    // 获取函数/存储过程定义
                    String procSql = String.format(
                            "SELECT pg_get_functiondef(p.oid) AS ddl " +
                                    "FROM pg_catalog.pg_proc p " +
                                    "JOIN pg_catalog.pg_namespace n ON p.pronamespace = n.oid " +
                                    "WHERE n.nspname = 'public' AND p.proname = '%s'",
                            name);
                    rs = stmt.executeQuery(procSql);
                    if (rs.next()) {
                        ddl.append(rs.getString("ddl"));
                    }
                    break;

                default:
                    ddl.append("-- 不支持的对象类型: ").append(type);
            }

            if (ddl.length() == 0) {
                ddl.append("-- 未找到对象: ").append(name);
            }

            log.info("获取PostgreSQL {} DDL成功: {}", name, type);
        } catch (Exception e) {
            log.error("获取PostgreSQL DDL失败: type={}, name={}", type, name, e);
            return "-- 获取DDL失败: " + e.getMessage();
        } finally {
            closeResources(rs, stmt, conn);
        }

        return ddl.toString();
    }

    /**
     * 获取MySQL对象的DDL
     */
    private String getMySQLObjectDDL(String type, String name, int datasourceIndex) {
        StringBuilder ddl = new StringBuilder();
        Connection conn = null;
        Statement stmt = null;
        ResultSet rs = null;

        try {
            conn = getConnectionByIndex(datasourceIndex);
            stmt = conn.createStatement();
            stmt.setQueryTimeout(30);

            switch (type.toLowerCase()) {
                case "views":
                    // 获取视图定义
                    String viewSql = String.format("SHOW CREATE VIEW `%s`", name);
                    rs = stmt.executeQuery(viewSql);
                    if (rs.next()) {
                        ddl.append(rs.getString(2)); // CREATE VIEW语句
                    }
                    break;

                case "indexes":
                    // MySQL的索引是表的一部分,需要查表名再SHOW CREATE TABLE
                    String database = dataSourceDatabases.getOrDefault(datasourceIndex, null);
                    String findTableSql = String.format(
                            "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.STATISTICS " +
                                    "WHERE TABLE_SCHEMA = '%s' AND INDEX_NAME = '%s' LIMIT 1",
                            database, name);
                    rs = stmt.executeQuery(findTableSql);
                    if (rs.next()) {
                        String tableName = rs.getString("TABLE_NAME");
                        rs.close();
                        rs = null;

                        // 获取表的DDL,然后提取索引部分
                        String tableDdlSql = String.format("SHOW CREATE TABLE `%s`", tableName);
                        rs = stmt.executeQuery(tableDdlSql);
                        if (rs.next()) {
                            String fullDdl = rs.getString(2);
                            // 从完整DDL中提取该索引的定义
                            String[] lines = fullDdl.split("\n");
                            for (String line : lines) {
                                if (line.contains("KEY `" + name + "`") ||
                                        line.contains("INDEX `" + name + "`") ||
                                        line.contains("UNIQUE KEY `" + name + "`")) {
                                    ddl.append("-- 索引定义(来自表 ").append(tableName).append(")\n");
                                    ddl.append("ALTER TABLE `").append(tableName).append("` ADD ");
                                    ddl.append(line.trim().replaceAll(",$", ""));
                                    break;
                                }
                            }
                        }
                    }
                    break;

                case "procedures":
                    // 获取存储过程/函数定义
                    try {
                        String procSql = String.format("SHOW CREATE PROCEDURE `%s`", name);
                        rs = stmt.executeQuery(procSql);
                        if (rs.next()) {
                            ddl.append(rs.getString(3)); // Create Procedure语句
                        }
                    } catch (Exception e1) {
                        // 可能是函数而不是存储过程
                        try {
                            String funcSql = String.format("SHOW CREATE FUNCTION `%s`", name);
                            rs = stmt.executeQuery(funcSql);
                            if (rs.next()) {
                                ddl.append(rs.getString(3)); // Create Function语句
                            }
                        } catch (Exception e2) {
                            ddl.append("-- 无法获取存储过程/函数定义");
                        }
                    }
                    break;

                default:
                    ddl.append("-- 不支持的对象类型: ").append(type);
            }

            if (ddl.length() == 0) {
                ddl.append("-- 未找到对象: ").append(name);
            }

            log.info("获取MySQL {} DDL成功: {}", name, type);
        } catch (Exception e) {
            log.error("获取MySQL DDL失败: type={}, name={}", type, name, e);
            return "-- 获取DDL失败: " + e.getMessage();
        } finally {
            closeResources(rs, stmt, conn);
        }

        return ddl.toString();
    }

    // ==================== 解锁库表功能 ====================

    /**
     * 获取指定数据源的所有节点地址（主 + slave）
     */
    private List<String> getNodeAddresses(int datasourceIndex) {
        List<String> nodes = new ArrayList<>();
        OracleConfig.OracleDataSource ds = getDataSourceConfig(datasourceIndex);
        if (ds == null) return nodes;

        // 主节点：从 URL 提取 host:port
        String masterHost = extractHostFromJdbcUrl(ds.getUrl());
        if (masterHost != null) nodes.add(masterHost);

        // Slave 节点
        String slaveStr = ds.getSlave();
        if (slaveStr != null && !slaveStr.trim().isEmpty()) {
            for (String ip : slaveStr.split(",")) {
                String trimmed = ip.trim();
                if (!trimmed.isEmpty()) {
                    // 复用主节点端口
                    String host = trimmed;
                    String port = extractPortFromJdbcUrl(ds.getUrl());
                    if (port != null) {
                        nodes.add(host + ":" + port);
                    } else {
                        nodes.add(host);
                    }
                }
            }
        }
        return nodes.stream().distinct().collect(Collectors.toList());
    }

    private String extractHostFromJdbcUrl(String url) {
        if (url == null) return null;
        // jdbc:oracle:thin:@//host:port/serviceName  或  jdbc:oracle:thin:@host:port:serviceName
        int atIdx = url.indexOf('@');
        if (atIdx < 0) return null;
        String afterAt = url.substring(atIdx + 1);
        if (afterAt.startsWith("//")) {
            afterAt = afterAt.substring(2);
        }
        int colonIdx = afterAt.indexOf(':');
        if (colonIdx > 0) return afterAt.substring(0, colonIdx);
        int slashIdx = afterAt.indexOf('/');
        if (slashIdx > 0) return afterAt.substring(0, slashIdx);
        return afterAt.split("[/,]")[0];
    }

    private String extractPortFromJdbcUrl(String url) {
        if (url == null) return null;
        int atIdx = url.indexOf('@');
        if (atIdx < 0) return null;
        String afterAt = url.substring(atIdx + 1);
        if (afterAt.startsWith("//")) {
            afterAt = afterAt.substring(2);
        }
        int colonIdx = afterAt.indexOf(':');
        if (colonIdx < 0) return null;
        String afterColon = afterAt.substring(colonIdx + 1);
        int slashIdx = afterColon.indexOf('/');
        if (slashIdx > 0) afterColon = afterColon.substring(0, slashIdx);
        return afterColon.split("[/,]")[0].trim();
    }

    /**
     * 从指定节点获取连接（Slave 池按需懒创建，超过生命周期自动销毁）
     * 查询完成后 caller 必须负责关闭连接
     */
    private Connection getConnectionByNode(int datasourceIndex, String nodeAddress) throws SQLException {
        String poolKey = datasourceIndex + "@" + nodeAddress;
        HikariDataSource ds = slavePools.get(poolKey);

        if (ds != null) {
            // 检查是否超过生命周期，需要重建
            Long startTime = slavePoolStartTimes.get(poolKey);
            if (startTime != null && System.currentTimeMillis() - startTime > SLAVE_POOL_MAX_LIFE_MS) {
                log.info("Slave 连接池生命周期到达，关闭旧池重建: {}", poolKey);
                closePoolSafely(ds);
                slavePools.remove(poolKey);
                slavePoolStartTimes.remove(poolKey);
                ds = null;
            }
        }

        if (ds == null) {
            synchronized (slavePools) {
                ds = slavePools.get(poolKey);
                if (ds == null) {
                    OracleConfig.OracleDataSource master = getDataSourceConfig(datasourceIndex);
                    if (master == null) throw new SQLException("数据源不存在: " + datasourceIndex);

                    HikariConfig cfg = new HikariConfig();
                    cfg.setJdbcUrl(buildUrlForNode(master.getUrl(), nodeAddress));
                    cfg.setUsername(master.getUsername());
                    cfg.setPassword(master.getPassword());
                    cfg.setDriverClassName("oracle.jdbc.OracleDriver");
                    cfg.setConnectionTestQuery("SELECT 1 FROM DUAL");
                    cfg.setPoolName("SlavePool-" + poolKey.replace(":", "_"));
                    cfg.setMaximumPoolSize(3);
                    cfg.setMinimumIdle(0);               // 空闲立即回收，不常驻
                    cfg.setIdleTimeout(30 * 1000);       // 30 秒无活动则关闭空闲连接
                    cfg.setMaxLifetime(SLAVE_POOL_MAX_LIFE_MS); // 定期轮换物理连接
                    cfg.setConnectionTimeout(oracleConfig.getPool().getConnectionTimeout());

                    ds = new HikariDataSource(cfg);
                    slavePools.put(poolKey, ds);
                    slavePoolStartTimes.put(poolKey, System.currentTimeMillis());
                    log.info("创建 Slave 连接池: datasource={}, node={}", datasourceIndex, nodeAddress);
                }
            }
        }
        return ds.getConnection();
    }

    /**
     * 线程安全地关闭连接池（若尚未关闭则强制关闭，并等待物理连接真正断开）
     */
    private void closePoolSafely(HikariDataSource ds) {
        try {
            if (ds != null && !ds.isClosed()) {
                ds.close();
            }
        } catch (Exception e) {
            log.warn("关闭 Slave 连接池时出现异常: {}", e.getMessage());
        }
    }

    private String buildUrlForNode(String masterUrl, String nodeAddress) {
        // jdbc:oracle:thin:@//host:port/serviceName  或  jdbc:oracle:thin:@host:port:serviceName
        int atIdx = masterUrl.indexOf('@');
        if (atIdx < 0) return masterUrl;

        String prefix = masterUrl.substring(0, atIdx + 1);   // "jdbc:oracle:thin:@"
        String afterAt = masterUrl.substring(atIdx + 1);     // "//host:port/serviceName"
        if (afterAt.startsWith("//")) afterAt = afterAt.substring(2);

        String host = nodeAddress;
        String port = null;
        if (nodeAddress.contains(":")) {
            String[] parts = nodeAddress.split(":");
            host = parts[0];
            port = parts[1];
        }

        // 找到 afterAt 中 "/" 后面的 service 部分
        int slashIdx = afterAt.indexOf('/');
        String servicePart = (slashIdx >= 0) ? afterAt.substring(slashIdx) : "";

        String fullHost = (port != null) ? host + ":" + port : host;
        return prefix + "//" + fullHost + servicePart;
    }

    @Override
    public List<Map<String, Object>> getLockedObjects(int datasourceIndex) {
        OracleConfig.OracleDataSource ds = getDataSourceConfig(datasourceIndex);
        if (ds == null || !"ORACLE".equalsIgnoreCase(ds.getType())) {
            return Collections.emptyList();
        }

        List<String> nodes = getNodeAddresses(datasourceIndex);

        // 统一并行查询，节点数 ≥ 1 时均走并行（内部根据实际情况串行或并行）
        List<Future<List<Map<String, Object>>>> futures = new ArrayList<>();
        for (String node : nodes) {
            final String n = node;
            futures.add(lockQueryExecutor.submit(() -> queryLocksOnNode(datasourceIndex, n)));
        }

        Map<String, Map<String, Object>> deduped = new LinkedHashMap<>();
        for (Future<List<Map<String, Object>>> f : futures) {
            try {
                List<Map<String, Object>> rows = f.get(30, TimeUnit.SECONDS);
                for (Map<String, Object> row : rows) {
                    String key = row.get("SID") + ":" + row.get("SERIAL_NUM");
                    Map<String, Object> existing = deduped.get(key);
                    if (existing == null || compareLockDuration(row, existing) > 0) {
                        deduped.put(key, row);
                    }
                }
            } catch (Exception e) {
                log.warn("节点锁查询失败: {}", e.getMessage());
            }
        }

        // 清理已超时的 slave 池（懒清理，避免物理连接长期占用）
        evictExpiredSlavePools();

        return new ArrayList<>(deduped.values());
    }

    /**
     * 查询指定节点的锁表信息
     * 注意：主节点走 dataSourcePools（常驻池）；slave 节点走 slavePools（生命周期池）
     */
    private List<Map<String, Object>> queryLocksOnNode(int datasourceIndex, String nodeAddress) {
        List<Map<String, Object>> result = new ArrayList<>();
        Connection conn = null;
        Statement stmt = null;
        ResultSet rs = null;
        try {
            if (nodeAddress != null) {
                conn = getConnectionByNode(datasourceIndex, nodeAddress);
            } else {
                conn = getConnectionByIndex(datasourceIndex);
            }
            stmt = conn.createStatement();
            stmt.setQueryTimeout(30);
            rs = stmt.executeQuery(LOCK_QUERY_SQL);
            ResultSetMetaData meta = rs.getMetaData();
            int cols = meta.getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= cols; i++) {
                    row.put(meta.getColumnLabel(i), rs.getObject(i));
                }
                if (nodeAddress != null) {
                    row.put("NODE", nodeAddress);
                }
                result.add(row);
            }
        } catch (Exception e) {
            log.error("查询锁表失败 node={}: {}", nodeAddress, e.getMessage());
        } finally {
            closeResources(rs, stmt, conn); // 连接归还给池（slave 池定期自动销毁）
        }
        return result;
    }

    /**
     * 清理已超时的 slave 连接池，防止连接长期闲置导致 ORA-3113
     * 采用懒清理策略：每次查询完成后顺手检查并移除超时的池
     */
    private void evictExpiredSlavePools() {
        long now = System.currentTimeMillis();
        List<String> expiredKeys = new ArrayList<>();
        for (Map.Entry<String, Long> entry : slavePoolStartTimes.entrySet()) {
            if (now - entry.getValue() > SLAVE_POOL_MAX_LIFE_MS) {
                expiredKeys.add(entry.getKey());
            }
        }
        for (String key : expiredKeys) {
            log.info("清理超时的 Slave 连接池: {}", key);
            closePoolSafely(slavePools.remove(key));
            slavePoolStartTimes.remove(key);
        }
    }

    private int compareLockDuration(Map<String, Object> a, Map<String, Object> b) {
        Object da = a.get("LOCK_DURATION_SECONDS");
        Object db = b.get("LOCK_DURATION_SECONDS");
        if (da == null) return 1;
        if (db == null) return -1;
        return Long.compare(((Number) da).longValue(), ((Number) db).longValue());
    }

    @Override
    public Map<String, Object> unlockSession(String sid, String serial, int datasourceIndex) {
        Map<String, Object> res = new LinkedHashMap<>();
        OracleConfig.OracleDataSource ds = getDataSourceConfig(datasourceIndex);
        if (ds == null || !"ORACLE".equalsIgnoreCase(ds.getType())) {
            res.put("success", false);
            res.put("message", "仅 Oracle 数据源支持解锁操作");
            return res;
        }

        // 先检查会话是否存在
        String checkSql = "SELECT COUNT(*) FROM v$session WHERE sid = " + sid + " AND serial# = " + serial;
        String killSql = "ALTER SYSTEM KILL SESSION '" + sid + "," + serial + "'";
        Connection conn = null;
        Statement stmt = null;
        ResultSet rs = null;

        try {
            conn = getConnectionByIndex(datasourceIndex);
            stmt = conn.createStatement();
            stmt.setQueryTimeout(10);

            rs = stmt.executeQuery(checkSql);
            if (!rs.next() || rs.getInt(1) == 0) {
                res.put("success", false);
                res.put("message", "会话 SID=" + sid + ", Serial#=" + serial + " 不存在或已消失");
                return res;
            }
            closeResources(rs, stmt, null);
            rs = null;
            stmt = null;

            stmt = conn.createStatement();
            stmt.setQueryTimeout(10);
            stmt.execute(killSql);
            log.info("成功杀掉会话: SID={}, Serial#={}, datasource={}({})", sid, serial, datasourceIndex, ds.getName());

            res.put("success", true);
            res.put("message", "会话 SID=" + sid + ", Serial#=" + serial + " 已成功终止");
        } catch (Exception e) {
            log.error("解锁会话失败: SID={}, Serial#={}", sid, serial, e);
            res.put("success", false);
            res.put("message", "解锁失败: " + e.getMessage());
        } finally {
            closeResources(rs, stmt, conn);
        }
        return res;
    }

    private static final Set<String> MYSQL_PROCESS_DATABASE_WHITELIST =
            new HashSet<>(Arrays.asList("tm_xj", "tm_xj_jike", "ingp_auth", "ingp_auth_jike"));

    @Override
    public List<Map<String, Object>> getMysqlWsrepProcesses(int datasourceIndex, String databaseName) {
        validateMysqlProcessDatasource(datasourceIndex, databaseName);

        String sql = "SELECT ID, USER, HOST, DB, COMMAND, TIME, STATE, INFO " +
                "FROM information_schema.PROCESSLIST " +
                "WHERE DB = ? AND STATE LIKE ? AND COMMAND = 'Query' " +
                "ORDER BY TIME DESC, ID DESC";

        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = getConnectionByIndex(datasourceIndex);
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setQueryTimeout(15);
            stmt.setString(1, databaseName);
            stmt.setString(2, "%wsrep:%");
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
                        row.put(columns.get(i - 1), getColumnValue(rs, i, metaData.getColumnType(i)));
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

    @Override
    public Map<String, Object> killMysqlProcesses(int datasourceIndex, String databaseName, List<Long> processIds, String username) {
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

        List<Long> killedIds = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        String validateSql = "SELECT COUNT(*) FROM information_schema.PROCESSLIST " +
                "WHERE ID = ? AND DB = ? AND STATE LIKE ? AND COMMAND = 'Query'";

        try (Connection conn = getConnectionByIndex(datasourceIndex);
                PreparedStatement validateStmt = conn.prepareStatement(validateSql);
                Statement killStmt = conn.createStatement()) {
            validateStmt.setQueryTimeout(10);
            killStmt.setQueryTimeout(10);

            for (Long processId : requestedIds) {
                try {
                    validateStmt.setLong(1, processId);
                    validateStmt.setString(2, databaseName);
                    validateStmt.setString(3, "%wsrep:%");
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
            OracleConfig.OracleDataSource dsConfig = getDataSourceConfig(datasourceIndex);
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
        OracleConfig.OracleDataSource ds = getDataSourceConfig(datasourceIndex);
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
}
