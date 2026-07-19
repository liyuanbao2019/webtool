package com.gxcj.xjtool.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** SQL statement splitting, JDBC normalization and statement classification. */
@Slf4j
@Component
class DatabaseSqlParser {

    List<String> splitStatements(String sqlInput, String databaseType) {
        List<String> validSqls = new ArrayList<>();
        String preprocessed = stripTripleDashComments(sqlInput);
        try {
            com.alibaba.druid.DbType dbType;
            if (DatabaseDialect.isMySQL(databaseType)) {
                dbType = com.alibaba.druid.DbType.mysql;
            } else if ("POSTGRESQL".equalsIgnoreCase(databaseType)
                    || "POSTGRES".equalsIgnoreCase(databaseType)
                    || "PG".equalsIgnoreCase(databaseType)) {
                dbType = com.alibaba.druid.DbType.postgresql;
            } else if ("DM".equalsIgnoreCase(databaseType)) {
                dbType = com.alibaba.druid.DbType.dm;
            } else {
                dbType = com.alibaba.druid.DbType.oracle;
            }

            List<com.alibaba.druid.sql.ast.SQLStatement> statements =
                    com.alibaba.druid.sql.SQLUtils.parseStatements(preprocessed, dbType);
            for (com.alibaba.druid.sql.ast.SQLStatement statement : statements) {
                String sql = formatForJdbcExecution(statement.toString().trim());
                if (!sql.isEmpty() && !"/".equals(sql)) {
                    validSqls.add(sql);
                }
            }
        } catch (com.alibaba.druid.sql.parser.ParserException e) {
            log.warn("Druid SQL 解析失败，降级返回原 SQL: {}", e.getMessage());
            addFallback(validSqls, preprocessed);
        } catch (Exception e) {
            log.error("SQL 分割时发生未知异常", e);
            addFallback(validSqls, preprocessed);
        }
        return validSqls;
    }

    static String stripTripleDashComments(String sql) {
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

    static String formatForJdbcExecution(String sql) {
        sql = sql.trim();
        if (sql.endsWith(";")) {
            String upperSql = sql.toUpperCase();
            boolean keepSemicolon = upperSql.startsWith("DECLARE") || upperSql.startsWith("BEGIN")
                    || upperSql.startsWith("CREATE OR REPLACE PROCEDURE") || upperSql.startsWith("CREATE PROCEDURE")
                    || upperSql.startsWith("CREATE OR REPLACE FUNCTION") || upperSql.startsWith("CREATE FUNCTION")
                    || upperSql.startsWith("CREATE OR REPLACE TRIGGER") || upperSql.startsWith("CREATE TRIGGER")
                    || upperSql.startsWith("CREATE OR REPLACE PACKAGE") || upperSql.startsWith("CREATE PACKAGE")
                    || upperSql.startsWith("CREATE OR REPLACE TYPE") || upperSql.startsWith("CREATE TYPE");
            if (!keepSemicolon) {
                sql = sql.substring(0, sql.length() - 1).trim();
            }
        }
        return sql;
    }

    static String sqlType(String sql) {
        String upperSql = sql.toUpperCase().trim();
        String effectiveLine = null;
        for (String line : upperSql.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("--") || trimmed.startsWith("/*")) {
                continue;
            }
            effectiveLine = trimmed;
            break;
        }
        if (effectiveLine == null) {
            effectiveLine = upperSql.split("\\r?\\n")[0].trim();
        }
        if (effectiveLine.startsWith("SELECT") || effectiveLine.startsWith("WITH")) return "SELECT";
        if (effectiveLine.startsWith("SHOW")) return "SHOW";
        if (effectiveLine.startsWith("DESC")) return "DESC";
        if (effectiveLine.startsWith("DESCRIBE")) return "DESCRIBE";
        if (effectiveLine.startsWith("EXPLAIN")) return "EXPLAIN";
        if (effectiveLine.startsWith("INSERT")) return "INSERT";
        if (effectiveLine.startsWith("UPDATE")) return "UPDATE";
        if (effectiveLine.startsWith("DELETE")) return "DELETE";
        if (effectiveLine.startsWith("CREATE")) return "CREATE";
        if (effectiveLine.startsWith("DROP")) return "DROP";
        if (effectiveLine.startsWith("ALTER")) return "ALTER";
        return "OTHER";
    }

    static boolean isResultSetType(String sqlType) {
        return "SELECT".equals(sqlType)
                || "SHOW".equals(sqlType)
                || "DESC".equals(sqlType)
                || "DESCRIBE".equals(sqlType)
                || "EXPLAIN".equals(sqlType);
    }

    private static void addFallback(List<String> statements, String sql) {
        String fallbackSql = formatForJdbcExecution(sql);
        if (!fallbackSql.isEmpty() && !"/".equals(fallbackSql)) {
            statements.add(fallbackSql);
        }
    }
}
