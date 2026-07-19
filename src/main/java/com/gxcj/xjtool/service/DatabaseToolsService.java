package com.gxcj.xjtool.service;

import com.gxcj.xjtool.config.DatabaseConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Database administration helpers used by the SQL tool.
 *
 * The implementation intentionally works against the datasource selected in the SQL tool
 * instead of reusing the fixed hosts and SSH credentials from xjwlcsMonitor.  Every query is
 * selected by database type so Oracle, DM and MySQL do not accidentally execute each other's
 * maintenance syntax.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatabaseToolsService {

    private static final int MAX_ROWS = 500;
    private final DatabaseConfig databaseConfig;
    private final OracleSshCommandService oracleSshCommandService;
    private final OracleHealthCheckService oracleHealthCheckService;
    private final OracleRecycleBinService oracleRecycleBinService;

    public Map<String, Object> queryLargeTables(int datasourceIndex, double minSizeGb) {
        DataSourceContext context = context(datasourceIndex);
        double threshold = Math.max(0D, minSizeGb);
        List<Map<String, Object>> rows;
        try (Connection connection = openConnection(context)) {
            if (context.isMySql()) {
                rows = query(connection,
                        "SELECT TABLE_SCHEMA AS SCHEMA_NAME, TABLE_NAME, ENGINE, " +
                                "ROUND((DATA_LENGTH + INDEX_LENGTH) / 1024 / 1024 / 1024, 3) AS SIZE_GB, " +
                                "ROUND(DATA_LENGTH / 1024 / 1024 / 1024, 3) AS DATA_GB, " +
                                "ROUND(INDEX_LENGTH / 1024 / 1024 / 1024, 3) AS INDEX_GB, " +
                                "TABLE_ROWS AS ESTIMATED_ROWS, CREATE_TIME, UPDATE_TIME " +
                                "FROM information_schema.TABLES " +
                                "WHERE TABLE_TYPE = 'BASE TABLE' " +
                                "AND TABLE_SCHEMA NOT IN ('mysql','information_schema','performance_schema','sys') " +
                                "AND (DATA_LENGTH + INDEX_LENGTH) >= ? * 1024 * 1024 * 1024 " +
                                "ORDER BY (DATA_LENGTH + INDEX_LENGTH) DESC LIMIT 500",
                        threshold);
            } else {
                rows = queryOracleCompatibleLargeTables(connection, threshold, context);
            }
        } catch (SQLException e) {
            throw databaseFailure("查询大表失败", context, e);
        }

        Map<String, Object> result = success(context);
        result.put("rows", limit(rows));
        result.put("count", rows.size());
        result.put("minSizeGb", threshold);
        result.put("note", context.isMySql()
                ? "MySQL 表大小来自 information_schema，行数为存储引擎估算值。"
                : "容量来自段信息，行数来自最近一次统计信息；未收集统计信息时可能为空。"
        );
        return result;
    }

    private List<Map<String, Object>> queryOracleCompatibleLargeTables(Connection connection, double threshold,
                                                                        DataSourceContext context) throws SQLException {
        String dbaSql = "SELECT s.OWNER AS SCHEMA_NAME, s.SEGMENT_NAME AS TABLE_NAME, " +
                "ROUND(SUM(s.BYTES) / 1024 / 1024 / 1024, 3) AS SIZE_GB, " +
                "MAX(t.NUM_ROWS) AS ESTIMATED_ROWS, MAX(t.LAST_ANALYZED) AS LAST_ANALYZED " +
                "FROM DBA_SEGMENTS s LEFT JOIN DBA_TABLES t " +
                "ON t.OWNER = s.OWNER AND t.TABLE_NAME = s.SEGMENT_NAME " +
                "WHERE s.SEGMENT_TYPE IN ('TABLE','TABLE PARTITION','TABLE SUBPARTITION') " +
                "GROUP BY s.OWNER, s.SEGMENT_NAME " +
                "HAVING SUM(s.BYTES) >= ? * 1024 * 1024 * 1024 " +
                "ORDER BY SUM(s.BYTES) DESC";
        try {
            return query(connection, dbaSql, threshold);
        } catch (SQLException permissionFailure) {
            log.debug("DBA segment query unavailable on {}, falling back to USER views: {}",
                    context.name, permissionFailure.getMessage());
            String userSql = "SELECT USER AS SCHEMA_NAME, s.SEGMENT_NAME AS TABLE_NAME, " +
                    "ROUND(SUM(s.BYTES) / 1024 / 1024 / 1024, 3) AS SIZE_GB, " +
                    "MAX(t.NUM_ROWS) AS ESTIMATED_ROWS, MAX(t.LAST_ANALYZED) AS LAST_ANALYZED " +
                    "FROM USER_SEGMENTS s LEFT JOIN USER_TABLES t ON t.TABLE_NAME = s.SEGMENT_NAME " +
                    "WHERE s.SEGMENT_TYPE IN ('TABLE','TABLE PARTITION','TABLE SUBPARTITION') " +
                    "GROUP BY s.SEGMENT_NAME HAVING SUM(s.BYTES) >= ? * 1024 * 1024 * 1024 " +
                    "ORDER BY SUM(s.BYTES) DESC";
            return query(connection, userSql, threshold);
        }
    }

    public ReportFile exportLargeTables(int datasourceIndex, double minSizeGb) {
        Map<String, Object> result = queryLargeTables(datasourceIndex, minSizeGb);
        List<Map<String, Object>> rows = castList(result.get("rows"));
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("大表清单");
            sheet.createFreezePane(0, 1);
            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFillForegroundColor(IndexedColors.ROYAL_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            headerStyle.setFont(headerFont);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            CellStyle gbStyle = workbook.createCellStyle();
            gbStyle.setDataFormat(workbook.createDataFormat().getFormat("0.000"));

            List<String> columns = rows.isEmpty()
                    ? Arrays.asList("SCHEMA_NAME", "TABLE_NAME", "SIZE_GB", "ESTIMATED_ROWS")
                    : new ArrayList<>(rows.get(0).keySet());
            Row header = sheet.createRow(0);
            for (int i = 0; i < columns.size(); i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(columns.get(i));
                cell.setCellStyle(headerStyle);
            }
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                Row excelRow = sheet.createRow(rowIndex + 1);
                Map<String, Object> source = rows.get(rowIndex);
                for (int columnIndex = 0; columnIndex < columns.size(); columnIndex++) {
                    Object value = source.get(columns.get(columnIndex));
                    Cell cell = excelRow.createCell(columnIndex);
                    if (value instanceof Number) cell.setCellValue(((Number) value).doubleValue());
                    else cell.setCellValue(value == null ? "" : String.valueOf(value));
                    if (columns.get(columnIndex).endsWith("_GB")) {
                        cell.setCellStyle(gbStyle);
                    }
                }
            }
            for (int i = 0; i < columns.size(); i++) {
                sheet.autoSizeColumn(i);
                sheet.setColumnWidth(i, Math.min(sheet.getColumnWidth(i) + 512, 40 * 256));
            }
            workbook.write(output);
            String fileName = "大表清单_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".xlsx";
            return new ReportFile(fileName, output.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("导出大表 Excel 失败: " + e.getMessage(), e);
        }
    }

    public Map<String, Object> queryArchiveLogs(int datasourceIndex) {
        DataSourceContext context = context(datasourceIndex);
        Map<String, Object> result = success(context);
        try (Connection connection = openConnection(context)) {
            if (context.isOracle()) {
                result.put("rows", limit(query(connection,
                        "SELECT TO_CHAR(COMPLETION_TIME,'YYYY-MM-DD') AS ARCHIVE_DAY, COUNT(*) AS LOG_COUNT, " +
                                "ROUND(SUM(BLOCKS * BLOCK_SIZE) / 1024 / 1024, 2) AS SIZE_MB, " +
                                "MIN(SEQUENCE#) AS MIN_SEQUENCE, MAX(SEQUENCE#) AS MAX_SEQUENCE, " +
                                "MIN(FIRST_TIME) AS FIRST_TIME, MAX(NEXT_TIME) AS LAST_TIME " +
                                "FROM V$ARCHIVED_LOG WHERE DELETED = 'NO' AND COMPLETION_TIME IS NOT NULL " +
                                "GROUP BY TO_CHAR(COMPLETION_TIME,'YYYY-MM-DD') ORDER BY ARCHIVE_DAY DESC")));
                result.put("summary", firstOrEmpty(query(connection,
                        "SELECT NAME AS FRA_PATH, ROUND(SPACE_LIMIT/1024/1024,2) AS LIMIT_MB, " +
                                "ROUND(SPACE_USED/1024/1024,2) AS USED_MB, " +
                                "ROUND(CASE WHEN SPACE_LIMIT=0 THEN 0 ELSE SPACE_USED*100/SPACE_LIMIT END,2) AS USED_PERCENT " +
                                "FROM V$RECOVERY_FILE_DEST")));
                result.put("cleanupMode", "RMAN");
                result.put("note", "Oracle 归档文件必须遵循备份与 Data Guard 删除策略，通过 RMAN 清理；本工具只生成 RMAN 命令，不通过 JDBC 直接删文件。");
            } else if (context.isDm()) {
                result.put("rows", limit(query(connection, "SELECT * FROM V$ARCH_FILE")));
                result.put("summary", firstOrEmpty(tryQuery(connection, "SELECT * FROM V$ARCH_STATUS")));
                result.put("cleanupMode", "NATIVE");
                result.put("note", "达梦使用 SF_ARCHIVELOG_DELETE_BEFORE_TIME 按时间清理，执行前请确认备库重演位置和归档备份策略。"
                );
            } else {
                result.put("rows", limit(query(connection, "SHOW BINARY LOGS")));
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.putAll(firstOrEmpty(tryQuery(connection, "SHOW VARIABLES LIKE 'log_bin'")));
                summary.putAll(prefix(firstOrEmpty(tryQuery(connection, "SHOW VARIABLES LIKE 'binlog_expire_logs_seconds'")), "EXPIRE_"));
                result.put("summary", summary);
                result.put("cleanupMode", "NATIVE");
                result.put("note", "MySQL 没有 Oracle 归档日志；此处按 MySQL 特性管理二进制日志（binlog）。"
                );
            }
        } catch (SQLException e) {
            throw databaseFailure("查询归档/二进制日志失败", context, e);
        }
        List<?> rows = (List<?>) result.get("rows");
        result.put("count", rows == null ? 0 : rows.size());
        return result;
    }

    public Map<String, Object> cleanupArchiveLogs(int datasourceIndex, int retentionDays, boolean confirmed) {
        DataSourceContext context = context(datasourceIndex);
        int days = Math.max(1, Math.min(retentionDays, 3650));
        if (!confirmed) {
            throw new IllegalArgumentException("必须显式确认归档清理操作");
        }
        Map<String, Object> result = success(context);
        result.put("retentionDays", days);

        if (context.isOracle()) {
            String command = "DELETE NOPROMPT ARCHIVELOG ALL COMPLETED BEFORE 'SYSDATE-" + days + "';";
            OracleSshCommandService.CommandResult sshResult =
                    oracleSshCommandService.cleanupArchiveLogs(datasourceIndex, days);
            result.put("executed", true);
            result.put("command", command);
            result.put("output", sshResult.combinedOutput());
            result.put("message", "已通过 oracle SSH 账号执行 RMAN 归档清理，RMAN 删除策略会继续保护尚未满足备份或备库应用条件的日志。");
            return result;
        }

        try (Connection connection = openConnection(context)) {
            if (context.isDm()) {
                Timestamp cutoff = Timestamp.valueOf(LocalDateTime.now().minusDays(days));
                List<Map<String, Object>> rows = query(connection,
                        "SELECT SF_ARCHIVELOG_DELETE_BEFORE_TIME(?) AS DELETE_RESULT", cutoff);
                result.put("result", firstOrEmpty(rows));
                result.put("message", "达梦归档清理函数已执行。"
                );
            } else {
                Timestamp cutoff = Timestamp.valueOf(LocalDateTime.now().minusDays(days));
                try (PreparedStatement statement = connection.prepareStatement("PURGE BINARY LOGS BEFORE ?")) {
                    statement.setTimestamp(1, cutoff);
                    statement.setQueryTimeout(60);
                    statement.execute();
                }
                result.put("message", "MySQL 二进制日志清理已执行。"
                );
            }
            result.put("executed", true);
            log.warn("Database archive cleanup executed datasource={} type={} retentionDays={}",
                    context.name, context.type, days);
            return result;
        } catch (SQLException e) {
            throw databaseFailure("归档/二进制日志清理失败", context, e);
        }
    }

    public Map<String, Object> queryRecycleBin(int datasourceIndex) {
        DataSourceContext context = context(datasourceIndex);
        Map<String, Object> result = success(context);
        if (context.isMySql()) {
            result.put("supported", false);
            result.put("rows", Collections.emptyList());
            result.put("count", 0);
            result.put("note", "MySQL 没有原生回收站。DROP 后无法通过数据库回收站恢复，请依赖备份、延迟从库或审计恢复流程。"
            );
            return result;
        }

        try (Connection connection = openConnection(context)) {
            if (context.isOracle()) {
                OracleRecycleBinService.Snapshot snapshot = oracleRecycleBinService.query(
                        jdbcTemplate(connection), MAX_ROWS);
                result.put("supported", true);
                result.put("rows", snapshot.getRows());
                result.put("count", snapshot.getCount());
                result.put("sizeMb", snapshot.getSizeMb());
                result.put("scope", snapshot.getScope().name());
                result.put("note", snapshot.isDatabaseScope()
                        ? "当前账号可查看全库 DBA_RECYCLEBIN；清理将使用 PURGE DBA_RECYCLEBIN，需要 SYSDBA 权限。"
                        : "当前账号无 DBA_RECYCLEBIN 查询权限，仅展示并清理当前用户回收站。");
                return result;
            }
            List<Map<String, Object>> rows;
            try {
                rows = query(connection,
                        "SELECT OBJECT_NAME, ORIGINAL_NAME, OPERATION, TYPE, TS_NAME, CREATETIME, DROPTIME " +
                                "FROM RECYCLEBIN ORDER BY DROPTIME DESC");
            } catch (SQLException incompatibleColumn) {
                rows = query(connection, "SELECT * FROM RECYCLEBIN");
            }
            result.put("supported", true);
            result.put("rows", limit(rows));
            result.put("count", rows.size());
            result.put("note", "达梦兼容回收站语法，默认清理当前用户回收站。");
            return result;
        } catch (SQLException e) {
            throw databaseFailure("查询回收站失败", context, e);
        }
    }

    public Map<String, Object> cleanupRecycleBin(int datasourceIndex, boolean confirmed) {
        DataSourceContext context = context(datasourceIndex);
        if (!confirmed) {
            throw new IllegalArgumentException("必须显式确认回收站清理操作");
        }
        if (context.isMySql()) {
            Map<String, Object> unsupported = success(context);
            unsupported.put("supported", false);
            unsupported.put("executed", false);
            unsupported.put("message", "MySQL 没有原生回收站，未执行任何操作。"
            );
            return unsupported;
        }
        int before = 0;
        try (Connection connection = openConnection(context)) {
            if (context.isOracle()) {
                before = oracleRecycleBinService.purgeDetectedScope(jdbcTemplate(connection));
            } else {
                try {
                    before = number(firstOrEmpty(query(connection,
                            "SELECT COUNT(*) AS CNT FROM RECYCLEBIN")).get("CNT"));
                } catch (SQLException ignored) {
                    log.debug("Unable to count recycle bin objects before purge: {}", ignored.getMessage());
                }
                try (Statement statement = connection.createStatement()) {
                    statement.setQueryTimeout(60);
                    statement.execute("PURGE RECYCLEBIN");
                }
            }
        } catch (SQLException e) {
            throw databaseFailure("清理回收站失败", context, e);
        }
        Map<String, Object> result = success(context);
        result.put("supported", true);
        result.put("executed", true);
        result.put("cleanedCount", before);
        result.put("message", context.isOracle() ? "检测到的 Oracle 回收站范围已清理。" : "当前用户回收站已清理。"
        );
        log.warn("Database recycle bin purged datasource={} type={} count={}", context.name, context.type, before);
        return result;
    }

    private static JdbcTemplate jdbcTemplate(Connection connection) {
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        jdbc.setQueryTimeout(60);
        jdbc.setMaxRows(MAX_ROWS);
        return jdbc;
    }

    public Map<String, Object> runHealthCheck(int datasourceIndex) {
        DataSourceContext context = context(datasourceIndex);
        if (context.isOracle()) {
            return oracleHealthCheckService.run(datasourceIndex);
        }
        List<Map<String, Object>> checks = new ArrayList<>();
        try (Connection connection = openConnection(context)) {
            DatabaseMetaData metaData = connection.getMetaData();
            checks.add(check("连接与版本", "NORMAL",
                    metaData.getDatabaseProductName() + " " + metaData.getDatabaseProductVersion(),
                    "数据库连接正常。"));

            if (context.isDm()) {
                addDmHealthChecks(connection, checks);
            } else {
                addMySqlHealthChecks(connection, checks);
            }
        } catch (SQLException e) {
            throw databaseFailure("数据库体检失败", context, e);
        }

        int warningCount = 0;
        int criticalCount = 0;
        int skippedCount = 0;
        for (Map<String, Object> check : checks) {
            String status = String.valueOf(check.get("status"));
            if ("WARNING".equals(status)) warningCount++;
            if ("CRITICAL".equals(status)) criticalCount++;
            if ("SKIPPED".equals(status)) skippedCount++;
        }
        Map<String, Object> result = success(context);
        result.put("checks", checks);
        result.put("total", checks.size());
        result.put("warningCount", warningCount);
        result.put("criticalCount", criticalCount);
        result.put("skippedCount", skippedCount);
        result.put("generatedAt", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()));
        return result;
    }

    public ReportFile exportHealthCheckReport(int datasourceIndex) {
        DataSourceContext context = context(datasourceIndex);
        if (!context.isOracle()) {
            throw new IllegalArgumentException("完整智能体检明细报告仅适用于 Oracle 数据源");
        }
        return oracleHealthCheckService.exportBundle(datasourceIndex);
    }

    private void addOracleHealthChecks(Connection connection, List<Map<String, Object>> checks) {
        addSqlCheck(connection, checks, "实例状态",
                "SELECT INSTANCE_NAME, STATUS, DATABASE_STATUS, HOST_NAME, VERSION FROM V$INSTANCE",
                "实例应处于 OPEN/ACTIVE 状态。"
        );
        addSqlCheck(connection, checks, "数据库角色与打开模式",
                "SELECT NAME, DATABASE_ROLE, OPEN_MODE, LOG_MODE, FORCE_LOGGING FROM V$DATABASE",
                "检查角色、打开模式和归档状态。"
        );
        addSqlCheck(connection, checks, "表空间使用率",
                "SELECT TABLESPACE_NAME, ROUND(USED_PERCENT,2) AS USED_PERCENT FROM DBA_TABLESPACE_USAGE_METRICS " +
                        "WHERE USED_PERCENT >= 80 ORDER BY USED_PERCENT DESC",
                "返回记录表示表空间使用率达到 80% 以上。", true);
        addCountCheck(connection, checks, "无效对象",
                "SELECT COUNT(*) AS CNT FROM DBA_OBJECTS WHERE STATUS='INVALID' AND OWNER NOT IN ('SYS','SYSTEM')",
                "存在无效对象，建议确认最近发布或依赖变更。"
        );
        addCountCheck(connection, checks, "阻塞会话",
                "SELECT COUNT(*) AS CNT FROM V$SESSION WHERE BLOCKING_SESSION IS NOT NULL",
                "存在阻塞会话，请结合解锁库表工具确认。"
        );
        addSqlCheck(connection, checks, "FRA 空间",
                "SELECT NAME, ROUND(SPACE_USED*100/NULLIF(SPACE_LIMIT,0),2) AS USED_PERCENT FROM V$RECOVERY_FILE_DEST " +
                        "WHERE SPACE_LIMIT > 0 AND SPACE_USED*100/SPACE_LIMIT >= 80",
                "返回记录表示快速恢复区使用率达到 80% 以上。", true);
    }

    private void addDmHealthChecks(Connection connection, List<Map<String, Object>> checks) {
        addSqlCheck(connection, checks, "实例状态",
                "SELECT INSTANCE_NAME, STATUS$, MODE$ FROM V$INSTANCE",
                "检查达梦实例状态和运行模式。"
        );
        addCountCheck(connection, checks, "阻塞锁",
                "SELECT COUNT(*) AS CNT FROM V$LOCK WHERE BLOCKED=1",
                "存在等待锁，请结合解锁库表工具确认阻塞源。"
        );
        addSqlCheck(connection, checks, "活动慢会话",
                "SELECT SESS_ID, USER_NAME, CLNT_IP, DATEDIFF(SS,LAST_RECV_TIME,SYSDATE) AS EXEC_SECONDS, SQL_TEXT " +
                        "FROM V$SESSIONS WHERE STATE='ACTIVE' AND DATEDIFF(SS,LAST_RECV_TIME,SYSDATE) >= 30",
                "返回记录表示 SQL 已活动至少 30 秒。", true);
        addSqlCheck(connection, checks, "归档状态",
                "SELECT * FROM V$ARCH_STATUS",
                "检查本地/远程归档链路状态。"
        );
        addSqlCheck(connection, checks, "参数变更",
                "SELECT PARA_NAME, PARA_VALUE, FILE_VALUE FROM V$DM_INI WHERE PARA_VALUE<>FILE_VALUE",
                "返回记录表示内存参数与配置文件值不一致。", true);
    }

    private void addMySqlHealthChecks(Connection connection, List<Map<String, Object>> checks) {
        addSqlCheck(connection, checks, "连接使用率",
                "SELECT @@hostname AS HOST_NAME, @@version AS VERSION, " +
                        "(SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Threads_connected') AS CONNECTED, " +
                        "@@max_connections AS MAX_CONNECTIONS",
                "关注当前连接数与 max_connections 的余量。"
        );
        addSqlCheck(connection, checks, "长时间运行 SQL",
                "SELECT ID, USER, HOST, DB, TIME, STATE, INFO FROM information_schema.PROCESSLIST " +
                        "WHERE COMMAND='Query' AND ID<>CONNECTION_ID() AND TIME>=30 ORDER BY TIME DESC LIMIT 100",
                "返回记录表示 SQL 已运行至少 30 秒。", true);
        addCountCheck(connection, checks, "锁等待",
                "SELECT COUNT(*) AS CNT FROM performance_schema.data_lock_waits",
                "存在 InnoDB 数据锁等待，请结合解锁库表工具确认。"
        );
        addSqlCheck(connection, checks, "大表与碎片",
                "SELECT TABLE_SCHEMA, TABLE_NAME, ROUND((DATA_LENGTH+INDEX_LENGTH)/1024/1024,2) AS SIZE_MB, " +
                        "ROUND(DATA_FREE/1024/1024,2) AS FREE_MB FROM information_schema.TABLES " +
                        "WHERE TABLE_SCHEMA NOT IN ('mysql','information_schema','performance_schema','sys') " +
                        "AND DATA_FREE >= 1024*1024*100 ORDER BY DATA_FREE DESC LIMIT 100",
                "返回记录表示可回收空间达到 100MB 以上。", true);
        List<Map<String, Object>> replication = tryQuery(connection, "SHOW REPLICA STATUS");
        if (replication.isEmpty()) replication = tryQuery(connection, "SHOW SLAVE STATUS");
        checks.add(check("复制状态", "NORMAL", replication.isEmpty() ? "当前实例未配置异步复制或无查看权限" : replication.get(0),
                replication.isEmpty() ? "单机/组复制环境可忽略；异步复制环境请确认权限。" : "检查 IO/SQL 线程和复制延迟。"));
    }

    public Map<String, Object> queryPerformanceSnapshots(int datasourceIndex) {
        DataSourceContext context = context(datasourceIndex);
        Map<String, Object> result = success(context);
        if (!context.isOracle()) {
            result.put("snapshotMode", false);
            result.put("rows", Collections.emptyList());
            result.put("note", context.isDm()
                    ? "达梦适配为当前动态性能报告，不依赖 Oracle AWR 快照。"
                    : "MySQL 适配为 performance_schema 当前性能报告，不依赖 Oracle AWR 快照。"
            );
            return result;
        }
        try (Connection connection = openConnection(context)) {
            List<Map<String, Object>> rows = query(connection,
                    "SELECT * FROM (SELECT SNAP_ID, INSTANCE_NUMBER, BEGIN_INTERVAL_TIME, END_INTERVAL_TIME " +
                            "FROM DBA_HIST_SNAPSHOT WHERE INSTANCE_NUMBER=(SELECT INSTANCE_NUMBER FROM V$INSTANCE) " +
                            "ORDER BY SNAP_ID DESC) WHERE ROWNUM <= 100");
            result.put("snapshotMode", true);
            result.put("rows", rows);
            result.put("note", "Oracle AWR 属于 Diagnostic Pack 功能，请确认目标库许可证允许使用。"
            );
            return result;
        } catch (SQLException e) {
            result.put("snapshotMode", false);
            result.put("rows", Collections.emptyList());
            result.put("note", "当前账号无法读取 AWR 快照，将导出当前状态性能报告：" + e.getMessage());
            return result;
        }
    }

    public ReportFile exportPerformanceReport(int datasourceIndex, Long beginSnapId, Long endSnapId) {
        DataSourceContext context = context(datasourceIndex);
        String html;
        String reportKind;
        if (context.isOracle() && beginSnapId != null && endSnapId != null) {
            if (beginSnapId >= endSnapId) {
                throw new IllegalArgumentException("结束快照必须大于开始快照");
            }
            html = generateOracleAwr(context, beginSnapId, endSnapId);
            reportKind = "awr";
        } else {
            html = generateCurrentPerformanceReport(datasourceIndex, context);
            reportKind = context.type.toLowerCase(Locale.ROOT) + "-performance";
        }
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        return new ReportFile(reportKind + "_report_" + timestamp + ".html",
                html.getBytes(StandardCharsets.UTF_8));
    }

    private String generateOracleAwr(DataSourceContext context, long beginSnapId, long endSnapId) {
        try (Connection connection = openConnection(context)) {
            Map<String, Object> identity = firstOrEmpty(query(connection,
                    "SELECT DBID, (SELECT INSTANCE_NUMBER FROM V$INSTANCE) AS INSTANCE_NUMBER FROM V$DATABASE"));
            long dbid = ((Number) identity.get("DBID")).longValue();
            int instanceNumber = ((Number) identity.get("INSTANCE_NUMBER")).intValue();
            String sql = "SELECT OUTPUT FROM TABLE(DBMS_WORKLOAD_REPOSITORY.AWR_REPORT_HTML(?,?,?,?))";
            StringBuilder html = new StringBuilder(256 * 1024);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, dbid);
                statement.setInt(2, instanceNumber);
                statement.setLong(3, beginSnapId);
                statement.setLong(4, endSnapId);
                statement.setQueryTimeout(300);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        String line = resultSet.getString(1);
                        if (line != null) html.append(line).append('\n');
                    }
                }
            }
            if (html.length() == 0) throw new IllegalStateException("AWR 报告内容为空");
            return html.toString();
        } catch (SQLException e) {
            throw databaseFailure("生成 Oracle AWR 报告失败", context, e);
        }
    }

    private String generateCurrentPerformanceReport(int datasourceIndex, DataSourceContext context) {
        Map<String, Object> health = runHealthCheck(datasourceIndex);
        Map<String, Object> largeTables;
        try {
            largeTables = queryLargeTables(datasourceIndex, 0.1D);
        } catch (RuntimeException e) {
            largeTables = new LinkedHashMap<>();
            largeTables.put("rows", Collections.emptyList());
            largeTables.put("note", e.getMessage());
        }
        Map<String, Object> archives;
        try {
            archives = queryArchiveLogs(datasourceIndex);
        } catch (RuntimeException e) {
            archives = new LinkedHashMap<>();
            archives.put("note", e.getMessage());
            archives.put("rows", Collections.emptyList());
        }

        StringBuilder html = new StringBuilder(32 * 1024);
        html.append("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\"><title>数据库性能报告</title>")
                .append("<style>body{font-family:Arial,'Microsoft YaHei',sans-serif;margin:28px;color:#243044}h1,h2{color:#1f4b7a}")
                .append("table{border-collapse:collapse;width:100%;margin:12px 0 24px}th,td{border:1px solid #d8dee8;padding:7px;text-align:left;font-size:13px}")
                .append("th{background:#eef4fb}.NORMAL{color:#198754}.WARNING{color:#b7791f}.CRITICAL{color:#dc3545}.SKIPPED{color:#6c757d}</style></head><body>")
                .append("<h1>").append(escape(context.type)).append(" 数据库性能报告</h1>")
                .append("<p>数据源：").append(escape(context.name)).append("；生成时间：")
                .append(escape(String.valueOf(health.get("generatedAt")))).append("</p>")
                .append("<h2>智能体检</h2>");
        appendChecks(html, castList(health.get("checks")));
        html.append("<h2>大表（0.1GB 以上）</h2>");
        appendTable(html, castList(largeTables.get("rows")));
        html.append("<h2>归档/日志概览</h2><p>")
                .append(escape(String.valueOf(archives.get("note")))).append("</p>");
        appendTable(html, castList(archives.get("rows")));
        html.append("</body></html>");
        return html.toString();
    }

    private void appendChecks(StringBuilder html, List<Map<String, Object>> checks) {
        html.append("<table><thead><tr><th>检查项</th><th>状态</th><th>结果</th><th>建议</th></tr></thead><tbody>");
        for (Map<String, Object> item : checks) {
            String status = String.valueOf(item.get("status"));
            html.append("<tr><td>").append(escape(item.get("name"))).append("</td><td class=\"")
                    .append(escape(status)).append("\">").append(escape(status)).append("</td><td>")
                    .append(escape(item.get("detail"))).append("</td><td>")
                    .append(escape(item.get("suggestion"))).append("</td></tr>");
        }
        html.append("</tbody></table>");
    }

    private void appendTable(StringBuilder html, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            html.append("<p>暂无数据或当前账号无查看权限。</p>");
            return;
        }
        List<String> columns = new ArrayList<>(rows.get(0).keySet());
        if (columns.size() > 12) columns = columns.subList(0, 12);
        html.append("<table><thead><tr>");
        for (String column : columns) html.append("<th>").append(escape(column)).append("</th>");
        html.append("</tr></thead><tbody>");
        for (Map<String, Object> row : rows) {
            html.append("<tr>");
            for (String column : columns) html.append("<td>").append(escape(row.get(column))).append("</td>");
            html.append("</tr>");
        }
        html.append("</tbody></table>");
    }

    private void addCountCheck(Connection connection, List<Map<String, Object>> checks, String name,
                               String sql, String warningSuggestion) {
        try {
            Map<String, Object> row = firstOrEmpty(query(connection, sql));
            int count = number(row.get("CNT"));
            checks.add(check(name, count > 0 ? "WARNING" : "NORMAL", row,
                    count > 0 ? warningSuggestion : "未发现异常。"));
        } catch (SQLException e) {
            checks.add(skipped(name, e));
        }
    }

    private void addSqlCheck(Connection connection, List<Map<String, Object>> checks, String name,
                             String sql, String suggestion) {
        addSqlCheck(connection, checks, name, sql, suggestion, false);
    }

    private void addSqlCheck(Connection connection, List<Map<String, Object>> checks, String name,
                             String sql, String suggestion, boolean rowsMeanWarning) {
        try {
            List<Map<String, Object>> rows = query(connection, sql);
            String status = rowsMeanWarning && !rows.isEmpty() ? "WARNING" : "NORMAL";
            Object detail = rows.isEmpty() ? "未发现异常" : limit(rows);
            checks.add(check(name, status, detail,
                    status.equals("WARNING") ? suggestion : "检查通过。"));
        } catch (SQLException e) {
            checks.add(skipped(name, e));
        }
    }

    private Map<String, Object> skipped(String name, Exception e) {
        return check(name, "SKIPPED", "权限不足或当前版本不支持：" + e.getMessage(),
                "可使用具备监控视图权限的账号重试。"
        );
    }

    private Map<String, Object> check(String name, String status, Object detail, String suggestion) {
        Map<String, Object> check = new LinkedHashMap<>();
        check.put("name", name);
        check.put("status", status);
        check.put("detail", detail);
        check.put("suggestion", suggestion);
        return check;
    }

    private DataSourceContext context(int datasourceIndex) {
        List<DatabaseConfig.DataSourceConfig> dataSources = databaseConfig.getDatasources();
        if (dataSources == null || datasourceIndex < 0 || datasourceIndex >= dataSources.size()) {
            throw new IllegalArgumentException("数据源不存在: " + datasourceIndex);
        }
        DatabaseConfig.DataSourceConfig config = dataSources.get(datasourceIndex);
        String type = config.getType() == null ? "ORACLE" : config.getType().trim().toUpperCase(Locale.ROOT);
        if (!"ORACLE".equals(type) && !"DM".equals(type) && !"MYSQL".equals(type)) {
            throw new IllegalArgumentException("该工具仅支持 Oracle、达梦和 MySQL，当前类型: " + type);
        }
        return new DataSourceContext(config, type);
    }

    private Connection openConnection(DataSourceContext context) throws SQLException {
        if (context.isOracle()) {
            Properties properties = new Properties();
            properties.setProperty("user", context.config.getUsername());
            properties.setProperty("password", context.config.getPassword());
            properties.setProperty("oracle.net.CONNECT_TIMEOUT", "10000");
            properties.setProperty("oracle.jdbc.ReadTimeout", "60000");
            return DriverManager.getConnection(context.config.getUrl(), properties);
        }
        return DriverManager.getConnection(context.config.getUrl(), context.config.getUsername(), context.config.getPassword());
    }

    private List<Map<String, Object>> query(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(60);
            statement.setMaxRows(MAX_ROWS);
            for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                ResultSetMetaData metaData = resultSet.getMetaData();
                int columnCount = metaData.getColumnCount();
                while (resultSet.next() && rows.size() < MAX_ROWS) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int column = 1; column <= columnCount; column++) {
                        String label = metaData.getColumnLabel(column);
                        if (label == null || label.trim().isEmpty()) label = metaData.getColumnName(column);
                        row.put(label.toUpperCase(Locale.ROOT), normalizeValue(resultSet.getObject(column)));
                    }
                    rows.add(row);
                }
                return rows;
            }
        }
    }

    private List<Map<String, Object>> tryQuery(Connection connection, String sql) {
        try {
            return query(connection, sql);
        } catch (SQLException e) {
            log.debug("Optional database tool query failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private Object normalizeValue(Object value) throws SQLException {
        if (value instanceof Clob) {
            Clob clob = (Clob) value;
            long length = Math.min(clob.length(), 4000);
            return clob.getSubString(1, (int) length);
        }
        if (value instanceof Blob) return "<BLOB>";
        return value;
    }

    private Map<String, Object> success(DataSourceContext context) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("databaseType", context.type);
        result.put("datasourceName", context.name);
        return result;
    }

    private IllegalStateException databaseFailure(String action, DataSourceContext context, SQLException e) {
        log.error("{} datasource={} type={}", action, context.name, context.type, e);
        return new IllegalStateException(action + " [" + context.type + "]: " + e.getMessage(), e);
    }

    private List<Map<String, Object>> limit(List<Map<String, Object>> rows) {
        return rows.size() <= MAX_ROWS ? rows : new ArrayList<>(rows.subList(0, MAX_ROWS));
    }

    private Map<String, Object> firstOrEmpty(List<Map<String, Object>> rows) {
        return rows == null || rows.isEmpty() ? new LinkedHashMap<String, Object>() : rows.get(0);
    }

    private Map<String, Object> prefix(Map<String, Object> source, String prefix) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) result.put(prefix + entry.getKey(), entry.getValue());
        return result;
    }

    private int number(Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castList(Object value) {
        return value instanceof List ? (List<Map<String, Object>>) value : Collections.<Map<String, Object>>emptyList();
    }

    private String escape(Object value) {
        if (value == null) return "";
        return String.valueOf(value).replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static final class DataSourceContext {
        private final DatabaseConfig.DataSourceConfig config;
        private final String type;
        private final String name;

        private DataSourceContext(DatabaseConfig.DataSourceConfig config, String type) {
            this.config = config;
            this.type = type;
            this.name = config.getName() == null ? "unnamed" : config.getName();
        }

        private boolean isOracle() { return "ORACLE".equals(type); }
        private boolean isDm() { return "DM".equals(type); }
        private boolean isMySql() { return "MYSQL".equals(type); }
    }

    public static final class ReportFile {
        private final String fileName;
        private final byte[] content;

        public ReportFile(String fileName, byte[] content) {
            this.fileName = fileName;
            this.content = content;
        }

        public String getFileName() { return fileName; }
        public byte[] getContent() { return content; }
    }
}
