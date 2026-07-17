package com.gxcj.xjtool.service.impl;

/**
 * 数据库方言适配器
 * 用于处理Oracle、达梦、MySQL、PostgreSQL数据库的元数据查询SQL差异
 */
public class DatabaseDialect {

    /**
     * 判断数据库类型
     */
    public static boolean isMySQL(String dbType) {
        return "MYSQL".equalsIgnoreCase(dbType) || "STARROCKS".equalsIgnoreCase(dbType);
    }

    public static boolean isDM(String dbType) {
        return "DM".equalsIgnoreCase(dbType);
    }

    public static boolean isOracle(String dbType) {
        return "ORACLE".equalsIgnoreCase(dbType) || dbType == null;
    }

    public static boolean isPostgreSQL(String dbType) {
        return "POSTGRESQL".equalsIgnoreCase(dbType) || "POSTGRES".equalsIgnoreCase(dbType)
                || "PG".equalsIgnoreCase(dbType);
    }

    /**
     * 根据对象类型构建查询 SQL
     */
    public static String buildObjectQuerySql(String type, String dbType, String database) {
        if (isMySQL(dbType)) {
            // MySQL使用INFORMATION_SCHEMA
            switch (type.toLowerCase()) {
                case "tables":
                    return String.format(
                            "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES " +
                                    "WHERE TABLE_SCHEMA = '%s' AND TABLE_TYPE = 'BASE TABLE' " +
                                    "ORDER BY TABLE_NAME",
                            database);
                case "views":
                    return String.format(
                            "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.VIEWS " +
                                    "WHERE TABLE_SCHEMA = '%s' " +
                                    "ORDER BY TABLE_NAME",
                            database);
                case "indexes":
                    return String.format(
                            "SELECT DISTINCT INDEX_NAME FROM INFORMATION_SCHEMA.STATISTICS " +
                                    "WHERE TABLE_SCHEMA = '%s' " +
                                    "ORDER BY INDEX_NAME",
                            database);
                case "procedures":
                    return String.format(
                            "SELECT ROUTINE_NAME FROM INFORMATION_SCHEMA.ROUTINES " +
                                    "WHERE ROUTINE_SCHEMA = '%s' " +
                                    "ORDER BY ROUTINE_NAME",
                            database);
                case "triggers":
                    return String.format(
                            "SELECT TRIGGER_NAME FROM INFORMATION_SCHEMA.TRIGGERS " +
                                    "WHERE TRIGGER_SCHEMA = '%s' " +
                                    "ORDER BY TRIGGER_NAME",
                            database);
                default:
                    return null;
            }
        } else if (isPostgreSQL(dbType)) {
            // PostgreSQL使用pg_catalog系统表
            switch (type.toLowerCase()) {
                case "tables":
                    return "SELECT tablename FROM pg_catalog.pg_tables " +
                            "WHERE schemaname = 'public' " +
                            "ORDER BY tablename";
                case "views":
                    return "SELECT viewname FROM pg_catalog.pg_views " +
                            "WHERE schemaname = 'public' " +
                            "ORDER BY viewname";
                case "indexes":
                    return "SELECT indexname FROM pg_catalog.pg_indexes " +
                            "WHERE schemaname = 'public' " +
                            "ORDER BY indexname";
                case "procedures":
                    return "SELECT proname FROM pg_catalog.pg_proc p " +
                            "JOIN pg_catalog.pg_namespace n ON p.pronamespace = n.oid " +
                            "WHERE n.nspname = 'public' AND p.prokind IN ('f', 'p') " +
                            "ORDER BY proname";
                case "triggers":
                    return "SELECT tgname FROM pg_catalog.pg_trigger t " +
                            "JOIN pg_catalog.pg_class c ON t.tgrelid = c.oid " +
                            "JOIN pg_catalog.pg_namespace n ON c.relnamespace = n.oid " +
                            "WHERE n.nspname = 'public' AND NOT t.tgisinternal " +
                            "ORDER BY tgname";
                case "sequences":
                    return "SELECT sequencename FROM pg_catalog.pg_sequences " +
                            "WHERE schemaname = 'public' " +
                            "ORDER BY sequencename";
                default:
                    return null;
            }
        } else {
            // Oracle和达梦使用USER_xxx系统视图
            boolean isDM = isDM(dbType);
            switch (type.toLowerCase()) {
                case "tables":
                    return isDM ? "SELECT TABLE_NAME FROM USER_TABLES ORDER BY TABLE_NAME"
                            : "SELECT table_name FROM user_tables ORDER BY table_name";
                case "views":
                    return isDM ? "SELECT VIEW_NAME FROM USER_VIEWS ORDER BY VIEW_NAME"
                            : "SELECT view_name FROM user_views ORDER BY view_name";
                case "indexes":
                    return isDM ? "SELECT INDEX_NAME FROM USER_INDEXES ORDER BY INDEX_NAME"
                            : "SELECT index_name FROM user_indexes ORDER BY index_name";
                case "procedures":
                    return isDM
                            ? "SELECT OBJECT_NAME FROM USER_PROCEDURES WHERE OBJECT_TYPE IN ('PROCEDURE', 'FUNCTION') ORDER BY OBJECT_NAME"
                            : "SELECT object_name FROM user_procedures WHERE object_type IN ('PROCEDURE', 'FUNCTION') ORDER BY object_name";
                case "triggers":
                    return isDM
                            ? "SELECT TRIGGER_NAME FROM USER_TRIGGERS ORDER BY TRIGGER_NAME"
                            : "SELECT trigger_name FROM user_triggers ORDER BY trigger_name";
                case "sequences":
                    return isDM ? "SELECT SEQUENCE_NAME FROM USER_SEQUENCES ORDER BY SEQUENCE_NAME"
                            : "SELECT sequence_name FROM user_sequences ORDER BY sequence_name";
                default:
                    return null;
            }
        }
    }

    /**
     * 构建查询表结构的SQL
     */
    public static String buildTableStructureQuerySql(String tableName, String dbType, String database) {
        String schema = null;
        String actualTable = tableName;
        if (tableName.contains(".")) {
            String[] parts = tableName.split("\\.");
            if (parts.length == 2) {
                schema = parts[0].toUpperCase();
                actualTable = parts[1].toUpperCase();
            }
        } else {
            actualTable = tableName.toUpperCase();
        }

        if (isMySQL(dbType)) {
            String targetSchema = schema != null ? schema : database;
            // MySQL使用INFORMATION_SCHEMA.COLUMNS
            return String.format(
                    "SELECT " +
                            "    c.COLUMN_NAME, " +
                            "    CONCAT(c.COLUMN_TYPE, " +
                            "        CASE WHEN c.IS_NULLABLE = 'NO' THEN '' ELSE '' END) AS DATA_TYPE, " +
                            "    c.IS_NULLABLE, " +
                            "    c.COLUMN_DEFAULT, " +
                            "    c.COLUMN_COMMENT, " +
                            "    CASE WHEN k.COLUMN_NAME IS NOT NULL THEN 'Y' ELSE 'N' END AS IS_PRIMARY_KEY " +
                            "FROM INFORMATION_SCHEMA.COLUMNS c " +
                            "LEFT JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE k " +
                            "    ON c.TABLE_SCHEMA = k.TABLE_SCHEMA " +
                            "    AND c.TABLE_NAME = k.TABLE_NAME " +
                            "    AND c.COLUMN_NAME = k.COLUMN_NAME " +
                            "    AND k.CONSTRAINT_NAME = 'PRIMARY' " +
                            "WHERE c.TABLE_SCHEMA = '%s' AND c.TABLE_NAME = '%s' " +
                            "ORDER BY c.ORDINAL_POSITION",
                    targetSchema, actualTable);
        } else if (isPostgreSQL(dbType)) {
            String targetSchema = schema != null ? schema.toLowerCase() : "public";
            actualTable = actualTable.toLowerCase();
            // PostgreSQL使用information_schema和pg_catalog
            return String.format(
                    "SELECT " +
                            "    c.column_name AS COLUMN_NAME, " +
                            "    CASE " +
                            "        WHEN c.data_type = 'character varying' THEN 'character varying(' || c.character_maximum_length || ')' "
                            +
                            "        WHEN c.data_type = 'character' THEN 'character(' || c.character_maximum_length || ')' "
                            +
                            "        WHEN c.data_type = 'numeric' AND c.numeric_precision IS NOT NULL THEN " +
                            "            'numeric(' || c.numeric_precision || CASE WHEN c.numeric_scale > 0 THEN ',' || c.numeric_scale ELSE '' END || ')' "
                            +
                            "        ELSE c.udt_name " +
                            "    END AS DATA_TYPE, " +
                            "    c.is_nullable AS NULLABLE, " +
                            "    c.column_default AS DATA_DEFAULT, " +
                            "    pgd.description AS COMMENTS, " +
                            "    CASE WHEN pk.column_name IS NOT NULL THEN 'Y' ELSE 'N' END AS IS_PRIMARY_KEY " +
                            "FROM information_schema.columns c " +
                            "LEFT JOIN pg_catalog.pg_description pgd " +
                            "    ON pgd.objoid = (SELECT oid FROM pg_catalog.pg_class WHERE relname = '%s' AND relnamespace = (SELECT oid FROM pg_catalog.pg_namespace WHERE nspname = '%s')) "
                            +
                            "    AND pgd.objsubid = c.ordinal_position " +
                            "LEFT JOIN ( " +
                            "    SELECT kcu.column_name " +
                            "    FROM information_schema.table_constraints tc " +
                            "    JOIN information_schema.key_column_usage kcu " +
                            "        ON tc.constraint_name = kcu.constraint_name " +
                            "        AND tc.table_schema = kcu.table_schema " +
                            "    WHERE tc.constraint_type = 'PRIMARY KEY' " +
                            "        AND tc.table_schema = '%s' " +
                            "        AND tc.table_name = '%s' " +
                            ") pk ON c.column_name = pk.column_name " +
                            "WHERE c.table_schema = '%s' AND c.table_name = '%s' " +
                            "ORDER BY c.ordinal_position",
                    actualTable, targetSchema, targetSchema, actualTable, targetSchema, actualTable);
        } else {
            // Oracle和达梦
            boolean isDM = isDM(dbType);
            String viewPrefix = (schema != null) ? "ALL_" : "USER_";
            String ownerFilter = (schema != null) ? String.format("AND c.OWNER = '%s' ", schema) : "";
            String ownerFilterCons = (schema != null) ? String.format("AND cons.OWNER = '%s' ", schema) : "";

            return String.format(
                    "SELECT " +
                            "    c.COLUMN_NAME, " +
                            "    c.DATA_TYPE || " +
                            "        CASE " +
                            "            WHEN c.DATA_TYPE IN ('VARCHAR2', 'VARCHAR', 'CHAR', 'NVARCHAR2', 'NCHAR') THEN '(' || c.DATA_LENGTH || ')' "
                            +
                            "            WHEN c.DATA_TYPE IN ('NUMBER', 'NUMERIC', 'DECIMAL') AND c.DATA_PRECISION IS NOT NULL THEN '(' || c.DATA_PRECISION || CASE WHEN c.DATA_SCALE > 0 THEN ',' || c.DATA_SCALE ELSE '' END || ')' "
                            +
                            "            ELSE '' " +
                            "        END AS DATA_TYPE, " +
                            "    c.NULLABLE, " +
                            "    c.DATA_DEFAULT, " +
                            "    cc.COMMENTS, " +
                            "    CASE WHEN pk.COLUMN_NAME IS NOT NULL THEN 'Y' ELSE 'N' END AS IS_PRIMARY_KEY " +
                            "FROM %sTAB_COLUMNS c " +
                            "LEFT JOIN %sCOL_COMMENTS cc ON c.TABLE_NAME = cc.TABLE_NAME AND c.COLUMN_NAME = cc.COLUMN_NAME "
                            + (schema != null ? "AND c.OWNER = cc.OWNER " : "") +
                            "LEFT JOIN ( " +
                            "    SELECT cols.COLUMN_NAME " +
                            "    FROM %sCONSTRAINTS cons " +
                            "    JOIN %sCONS_COLUMNS cols ON cons.CONSTRAINT_NAME = cols.CONSTRAINT_NAME " +
                            "    AND cons.OWNER = cols.OWNER " +
                            "    WHERE cons.CONSTRAINT_TYPE = 'P' AND cons.TABLE_NAME = '%s' %s " +
                            ") pk ON c.COLUMN_NAME = pk.COLUMN_NAME " +
                            "WHERE c.TABLE_NAME = '%s' %s " +
                            "ORDER BY c.COLUMN_ID",
                    viewPrefix, viewPrefix, viewPrefix, viewPrefix, actualTable, ownerFilterCons, actualTable,
                    ownerFilter);
        }
    }

    /**
     * 构建查询表索引的SQL
     */
    public static String buildTableIndexesQuerySql(String tableName, String dbType, String database) {
        String schema = null;
        String actualTable = tableName;
        if (tableName.contains(".")) {
            String[] parts = tableName.split("\\.");
            if (parts.length == 2) {
                schema = parts[0].toUpperCase();
                actualTable = parts[1].toUpperCase();
            }
        } else {
            actualTable = tableName.toUpperCase();
        }

        if (isMySQL(dbType)) {
            String targetSchema = schema != null ? schema : database;
            // MySQL使用INFORMATION_SCHEMA.STATISTICS
            return String.format(
                    "SELECT " +
                            "    INDEX_NAME, " +
                            "    COLUMN_NAME, " +
                            "    CASE WHEN NON_UNIQUE = 0 THEN 'UNIQUE' ELSE 'NONUNIQUE' END AS UNIQUENESS, " +
                            "    INDEX_TYPE " +
                            "FROM INFORMATION_SCHEMA.STATISTICS " +
                            "WHERE TABLE_SCHEMA = '%s' AND TABLE_NAME = '%s' " +
                            "ORDER BY INDEX_NAME, SEQ_IN_INDEX",
                    targetSchema, actualTable);
        } else if (isPostgreSQL(dbType)) {
            String targetSchema = schema != null ? schema.toLowerCase() : "public";
            actualTable = actualTable.toLowerCase();
            // PostgreSQL使用pg_catalog
            return String.format(
                    "SELECT " +
                            "    i.indexname AS INDEX_NAME, " +
                            "    a.attname AS COLUMN_NAME, " +
                            "    CASE WHEN ix.indisunique THEN 'UNIQUE' ELSE 'NONUNIQUE' END AS UNIQUENESS, " +
                            "    am.amname AS INDEX_TYPE " +
                            "FROM pg_catalog.pg_indexes i " +
                            "JOIN pg_catalog.pg_class c ON c.relname = i.indexname " +
                            "JOIN pg_catalog.pg_index ix ON ix.indexrelid = c.oid " +
                            "JOIN pg_catalog.pg_attribute a ON a.attrelid = ix.indrelid AND a.attnum = ANY(ix.indkey) "
                            +
                            "JOIN pg_catalog.pg_am am ON am.oid = c.relam " +
                            "WHERE i.schemaname = '%s' AND i.tablename = '%s' " +
                            "ORDER BY i.indexname, a.attnum",
                    targetSchema, actualTable);
        } else {
            // Oracle和达梦
            String viewPrefix = (schema != null) ? "ALL_" : "USER_";
            String ownerFilter = (schema != null) ? String.format("AND i.OWNER = '%s' ", schema) : "";

            return String.format(
                    "SELECT " +
                            "    i.INDEX_NAME, " +
                            "    ic.COLUMN_NAME, " +
                            "    i.UNIQUENESS, " +
                            "    i.INDEX_TYPE " +
                            "FROM %sINDEXES i " +
                            "JOIN %sIND_COLUMNS ic ON i.INDEX_NAME = ic.INDEX_NAME " +
                            (schema != null ? "AND i.OWNER = ic.INDEX_OWNER " : "") +
                            "WHERE i.TABLE_NAME = '%s' %s " +
                            "ORDER BY i.INDEX_NAME, ic.COLUMN_POSITION",
                    viewPrefix, viewPrefix, actualTable, ownerFilter);
        }
    }

    /**
     * 将对象类型转换为数据库DBMS_METADATA需要的类型名称
     * 注意：达梦、MySQL和PostgreSQL可能不支持DBMS_METADATA包
     */
    public static String getObjectTypeForDDL(String type, String dbType) {
        if (isDM(dbType) || isMySQL(dbType) || isPostgreSQL(dbType)) {
            // 达梦、MySQL和PostgreSQL可能使用不同的DDL获取方式
            return null;
        }

        // Oracle数据库
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

    /**
     * 从JDBC URL中提取数据库名称（仅MySQL需要）
     */
    public static String extractDatabaseFromUrl(String url) {
        if (url == null)
            return null;

        // MySQL URL格式: jdbc:mysql://host:port/database?params
        String prefix = null;
        if (url.startsWith("jdbc:mysql://")) {
            prefix = "jdbc:mysql://";
        } else if (url.startsWith("jdbc:starrocks://")) {
            prefix = "jdbc:starrocks://";
        }
        if (prefix != null) {
            try {
                String afterHost = url.substring(prefix.length());
                int slashIdx = afterHost.indexOf('/');
                if (slashIdx > 0) {
                    String dbPart = afterHost.substring(slashIdx + 1);
                    int questionIdx = dbPart.indexOf('?');
                    return questionIdx > 0 ? dbPart.substring(0, questionIdx) : dbPart;
                }
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 构建查询表分区的SQL
     */
    public static String buildTablePartitionsQuerySql(String tableName, String dbType, String database) {
        String schema = null;
        String actualTable = tableName;
        if (tableName.contains(".")) {
            String[] parts = tableName.split("\\.");
            if (parts.length == 2) {
                schema = parts[0].toUpperCase();
                actualTable = parts[1].toUpperCase();
            }
        } else {
            actualTable = tableName.toUpperCase();
        }

        if (isOracle(dbType) || isDM(dbType)) {
            // Oracle和达梦查询分区信息
            String viewPrefix = (schema != null) ? "ALL_" : "USER_";
            String ownerFilter = (schema != null) ? String.format("AND table_owner = '%s' ", schema) : "";

            return String.format(
                    "SELECT " +
                            "    partition_name AS PARTITION_NAME, " +
                            "    'RANGE' AS PARTITION_TYPE, " +
                            "    '' AS PARTITION_KEY, " +
                            "    high_value AS PARTITION_VALUE, " +
                            "    0 AS SUBPARTITION_COUNT " +
                            "FROM %stab_partitions " +
                            "WHERE table_name = '%s' %s " +
                            "ORDER BY partition_position",
                    viewPrefix.toLowerCase(), actualTable, ownerFilter);
        } else if (isMySQL(dbType)) {
            String targetSchema = schema != null ? schema : database;
            // MySQL查询分区信息
            return String.format(
                    "SELECT " +
                            "    PARTITION_NAME, " +
                            "    PARTITION_METHOD AS PARTITION_TYPE, " +
                            "    PARTITION_EXPRESSION AS PARTITION_KEY, " +
                            "    PARTITION_DESCRIPTION AS PARTITION_VALUE, " +
                            "    IFNULL(SUBPARTITION_ORDINAL_POSITION, 0) AS SUBPARTITION_COUNT " +
                            "FROM information_schema.PARTITIONS " +
                            "WHERE TABLE_SCHEMA = '%s' " +
                            "  AND TABLE_NAME = '%s' " +
                            "  AND PARTITION_NAME IS NOT NULL " +
                            "ORDER BY PARTITION_ORDINAL_POSITION",
                    targetSchema, actualTable);
        }
        throw new RuntimeException("Unsupported database type: " + dbType);
    }

    /**
     * 构建查询表触发器的SQL
     */
    public static String buildTableTriggersQuerySql(String tableName, String dbType, String database) {
        String schema = null;
        String actualTable = tableName;
        if (tableName.contains(".")) {
            String[] parts = tableName.split("\\.");
            if (parts.length == 2) {
                schema = parts[0].toUpperCase();
                actualTable = parts[1].toUpperCase();
            }
        } else {
            actualTable = tableName.toUpperCase();
        }

        if (isOracle(dbType) || isDM(dbType)) {
            // Oracle和达梦查询触发器信息
            String viewPrefix = (schema != null) ? "ALL_" : "USER_";
            String ownerFilter = (schema != null) ? String.format("AND table_owner = '%s' ", schema) : "";

            return String.format(
                    "SELECT " +
                            "    trigger_name AS TRIGGER_NAME, " +
                            "    triggering_event AS TRIGGERING_EVENT, " +
                            "    trigger_type AS TRIGGER_TYPE, " +
                            "    status AS STATUS " +
                            "FROM %striggers " +
                            "WHERE table_name = '%s' %s " +
                            "ORDER BY trigger_name",
                    viewPrefix.toLowerCase(), actualTable, ownerFilter);
        } else if (isMySQL(dbType)) {
            String targetSchema = schema != null ? schema : database;
            // MySQL查询触发器信息
            return String.format(
                    "SELECT " +
                            "    TRIGGER_NAME, " +
                            "    EVENT_MANIPULATION AS TRIGGERING_EVENT, " +
                            "    ACTION_TIMING AS TRIGGER_TYPE, " +
                            "    'ENABLED' AS STATUS " +
                            "FROM information_schema.TRIGGERS " +
                            "WHERE EVENT_OBJECT_SCHEMA = '%s' " +
                            "  AND EVENT_OBJECT_TABLE = '%s' " +
                            "ORDER BY TRIGGER_NAME",
                    targetSchema, actualTable);
        }
        throw new RuntimeException("Unsupported database type: " + dbType);
    }
}
