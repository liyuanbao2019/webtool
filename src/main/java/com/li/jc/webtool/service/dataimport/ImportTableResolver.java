package com.li.jc.webtool.service.dataimport;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Resolves a user supplied table name ("TABLE" or "SCHEMA.TABLE") through JDBC metadata.
 * Only identifiers reported by the database are ever placed into generated SQL.
 */
public final class ImportTableResolver {

    private ImportTableResolver() {
    }

    public static ImportTable resolve(Connection conn, String dbType, String input) throws SQLException {
        if (input == null || input.trim().isEmpty()) {
            throw new IllegalArgumentException("请选择目标表");
        }
        String[] parts = splitQualifiedName(input.trim());
        String requestedSchema = parts[0];
        String requestedTable = parts[1];
        boolean mysql = isMysqlFamily(dbType);
        DatabaseMetaData meta = conn.getMetaData();

        List<String> schemaCandidates = new ArrayList<>();
        if (requestedSchema != null) {
            schemaCandidates.addAll(caseVariants(requestedSchema));
        } else if (mysql) {
            schemaCandidates.add(conn.getCatalog());
        } else {
            String current = safeCurrentSchema(conn);
            if (current != null) {
                schemaCandidates.add(current);
            }
            schemaCandidates.add(null);
        }

        for (String schema : schemaCandidates) {
            for (String table : caseVariants(requestedTable)) {
                ImportTable found = read(conn, meta, mysql, schema, table);
                if (found != null) {
                    return found;
                }
            }
        }

        if (requestedSchema == null && ("ORACLE".equals(dbType) || "DM".equals(dbType))) {
            ImportTable viaSynonym = resolveSynonym(conn, meta, requestedTable);
            if (viaSynonym != null) {
                return viaSynonym;
            }
        }
        throw new IllegalArgumentException("目标表不存在或无访问权限: " + input.trim());
    }

    /**
     * Whether a table / view with this name (any letter case) already exists in the connection's current
     * schema (MySQL: current database). Used before creating a new table.
     */
    public static boolean existsInCurrentSchema(Connection conn, String dbType, String table) throws SQLException {
        boolean mysql = isMysqlFamily(dbType);
        String schema = currentSchema(conn, dbType);
        DatabaseMetaData meta = conn.getMetaData();
        for (String variant : caseVariants(table)) {
            for (boolean escaped : new boolean[]{true, false}) {
                String schemaPattern = mysql ? null : (escaped ? escape(meta, schema) : schema);
                try (ResultSet rs = meta.getTables(mysql ? schema : null, schemaPattern,
                        escaped ? escape(meta, variant) : variant, null)) {
                    while (rs.next()) {
                        if (variant.equals(rs.getString("TABLE_NAME"))) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /** Current schema (MySQL family: current database), or null when the driver cannot tell. */
    public static String currentSchema(Connection conn, String dbType) throws SQLException {
        return isMysqlFamily(dbType) ? conn.getCatalog() : safeCurrentSchema(conn);
    }

    private static ImportTable read(Connection conn, DatabaseMetaData meta, boolean mysql, String schema, String table)
            throws SQLException {
        ImportTable found = read(conn, meta, mysql, schema, table, true);
        // Some drivers do not honour the search escape; retry with the plain name (results are filtered exactly)
        return found != null ? found : read(conn, meta, mysql, schema, table, false);
    }

    private static ImportTable read(Connection conn, DatabaseMetaData meta, boolean mysql, String schema, String table,
            boolean escaped) throws SQLException {
        String catalog = mysql ? schema : null;
        String schemaPattern = mysql ? null : (escaped ? escape(meta, schema) : schema);
        String tablePattern = escaped ? escape(meta, table) : table;
        Map<String, List<ImportColumn>> bySchema = new LinkedHashMap<>();
        Map<String, String> actualNames = new LinkedHashMap<>();
        try (ResultSet rs = meta.getColumns(catalog, schemaPattern, tablePattern, "%")) {
            Map<String, TreeMap<Integer, ImportColumn>> ordered = new LinkedHashMap<>();
            while (rs.next()) {
                String tableName = rs.getString("TABLE_NAME");
                // getColumns uses LIKE matching, so "_" may match other tables; keep exact matches only
                String owner = mysql ? rs.getString("TABLE_CAT") : rs.getString("TABLE_SCHEM");
                if (tableName == null || !tableName.equals(table)
                        || (schema != null && owner != null && !owner.equals(schema))) {
                    continue;
                }
                String key = owner == null ? "" : owner;
                actualNames.put(key, tableName);
                ImportColumn column = new ImportColumn();
                column.setName(rs.getString("COLUMN_NAME"));
                column.setJdbcType(rs.getInt("DATA_TYPE"));
                column.setTypeName(rs.getString("TYPE_NAME"));
                column.setSize(rs.getInt("COLUMN_SIZE"));
                column.setDecimalDigits(rs.getInt("DECIMAL_DIGITS"));
                column.setNullable(rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls);
                column.setDefaultValue(trimToNull(rs.getString("COLUMN_DEF")));
                column.setRemarks(trimToNull(rs.getString("REMARKS")));
                column.setAutoIncrement(readAutoIncrement(rs));
                int position = rs.getInt("ORDINAL_POSITION");
                TreeMap<Integer, ImportColumn> columns = ordered.computeIfAbsent(key, k -> new TreeMap<>());
                columns.put(position > 0 ? position : columns.size() + 1, column);
            }
            ordered.forEach((key, columns) -> bySchema.put(key, new ArrayList<>(columns.values())));
        }
        if (bySchema.isEmpty()) {
            return null;
        }
        if (bySchema.size() > 1) {
            throw new IllegalArgumentException("表 " + table + " 在多个 schema 中存在（" + String.join(", ", bySchema.keySet())
                    + "），请使用 SCHEMA.表名 指定");
        }
        String owner = bySchema.keySet().iterator().next();
        String tableName = actualNames.get(owner);
        List<ImportColumn> columns = bySchema.get(owner);
        markPrimaryKeys(meta, mysql ? emptyToNull(owner) : null, mysql ? null : emptyToNull(owner), tableName, columns);
        return new ImportTable(emptyToNull(owner), tableName, buildSqlName(conn, mysql, emptyToNull(owner), tableName),
                columns);
    }

    private static ImportTable resolveSynonym(Connection conn, DatabaseMetaData meta, String name) throws SQLException {
        String sql = "SELECT TABLE_OWNER, TABLE_NAME FROM ALL_SYNONYMS WHERE SYNONYM_NAME = ? "
                + "AND OWNER IN (USER, 'PUBLIC') ORDER BY CASE WHEN OWNER = 'PUBLIC' THEN 1 ELSE 0 END";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name.toUpperCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return read(conn, meta, false, rs.getString(1), rs.getString(2));
                }
            }
        } catch (SQLException e) {
            // ALL_SYNONYMS is not visible for every account; treat as "not found"
        }
        return null;
    }

    private static void markPrimaryKeys(DatabaseMetaData meta, String catalog, String schema, String table,
            List<ImportColumn> columns) {
        Set<String> keys = new HashSet<>();
        try (ResultSet rs = meta.getPrimaryKeys(catalog, schema, table)) {
            while (rs.next()) {
                keys.add(rs.getString("COLUMN_NAME"));
            }
        } catch (SQLException ignored) {
            // primary key info is only informational for the mapping screen
        }
        for (ImportColumn column : columns) {
            column.setPrimaryKey(keys.contains(column.getName()));
        }
    }

    private static boolean readAutoIncrement(ResultSet rs) {
        try {
            return "YES".equalsIgnoreCase(rs.getString("IS_AUTOINCREMENT"));
        } catch (SQLException e) {
            return false;
        }
    }

    private static String buildSqlName(Connection conn, boolean mysql, String owner, String table) throws SQLException {
        String current = mysql ? conn.getCatalog() : safeCurrentSchema(conn);
        String quotedTable = quote(conn, table);
        if (owner == null || owner.equalsIgnoreCase(current)) {
            return quotedTable;
        }
        return quote(conn, owner) + "." + quotedTable;
    }

    public static String quote(Connection conn, String identifier) throws SQLException {
        return quote(conn.getMetaData().getIdentifierQuoteString(), identifier);
    }

    /** Quotes an identifier with the database's quote string (from DatabaseMetaData). */
    public static String quote(String quote, String identifier) throws SQLException {
        if (identifier == null || identifier.isEmpty()) {
            throw new SQLException("Empty identifier");
        }
        for (int i = 0; i < identifier.length(); i++) {
            if (Character.isISOControl(identifier.charAt(i))) {
                throw new SQLException("Unsafe identifier: " + identifier);
            }
        }
        if (quote == null || quote.trim().isEmpty()) {
            if (!identifier.matches("[A-Za-z0-9_$#]+")) {
                throw new SQLException("Unsafe identifier: " + identifier);
            }
            return identifier;
        }
        quote = quote.trim();
        return quote + identifier.replace(quote, quote + quote) + quote;
    }

    static String[] splitQualifiedName(String input) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quoteChar = 0;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (quoteChar != 0) {
                if (c == quoteChar) {
                    quoteChar = 0;
                } else {
                    current.append(c);
                }
            } else if (c == '"' || c == '`') {
                quoteChar = c;
            } else if (c == '.') {
                parts.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString().trim());
        if (parts.size() > 2 || parts.stream().anyMatch(String::isEmpty)) {
            throw new IllegalArgumentException("表名格式不正确，应为 表名 或 SCHEMA.表名: " + input);
        }
        return parts.size() == 1 ? new String[]{null, parts.get(0)} : new String[]{parts.get(0), parts.get(1)};
    }

    private static Set<String> caseVariants(String value) {
        Set<String> variants = new LinkedHashSet<>();
        variants.add(value);
        variants.add(value.toUpperCase(Locale.ROOT));
        variants.add(value.toLowerCase(Locale.ROOT));
        return variants;
    }

    private static String escape(DatabaseMetaData meta, String value) throws SQLException {
        if (value == null) {
            return null;
        }
        String escape = meta.getSearchStringEscape();
        if (escape == null || escape.isEmpty()) {
            return value;
        }
        return value.replace(escape, escape + escape).replace("_", escape + "_").replace("%", escape + "%");
    }

    private static String safeCurrentSchema(Connection conn) {
        try {
            return conn.getSchema();
        } catch (SQLException | AbstractMethodError e) {
            return null;
        }
    }

    private static boolean isMysqlFamily(String dbType) {
        return "MYSQL".equals(dbType) || "STARROCKS".equals(dbType);
    }

    private static String trimToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    /** Resolved table with the SQL-safe name to use in INSERT statements. */
    public static final class ImportTable {
        private final String schema;
        private final String name;
        private final String sqlName;
        private final List<ImportColumn> columns;

        ImportTable(String schema, String name, String sqlName, List<ImportColumn> columns) {
            this.schema = schema;
            this.name = name;
            this.sqlName = sqlName;
            this.columns = Collections.unmodifiableList(columns);
        }

        public String getSchema() {
            return schema;
        }

        public String getName() {
            return name;
        }

        public String getSqlName() {
            return sqlName;
        }

        public List<ImportColumn> getColumns() {
            return columns;
        }

        public ImportColumn findColumn(String columnName) {
            if (columnName == null) {
                return null;
            }
            for (ImportColumn column : columns) {
                if (column.getName().equals(columnName)) {
                    return column;
                }
            }
            for (ImportColumn column : columns) {
                if (column.getName().equalsIgnoreCase(columnName)) {
                    return column;
                }
            }
            return null;
        }

        public String getDisplayName() {
            return schema == null ? name : schema + "." + name;
        }
    }
}
