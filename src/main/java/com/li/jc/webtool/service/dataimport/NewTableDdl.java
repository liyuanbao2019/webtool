package com.li.jc.webtool.service.dataimport;

import com.li.jc.webtool.dto.DataImportRequest;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builds the CREATE TABLE statement for the "create a new table" import mode.
 * Column types are chosen from a small set of logical kinds and rendered per database, so no user typed
 * SQL ever reaches the statement; identifiers are validated and quoted.
 */
public final class NewTableDdl {

    public enum Kind { TEXT, LONG_TEXT, INTEGER, BIGINT, DECIMAL, DATE, DATETIME }

    /** Lengths offered for text columns, smallest first. */
    static final int[] TEXT_TIERS = {50, 100, 200, 500, 1000, 2000, 4000};
    static final int MAX_DECIMAL_PRECISION = 38;
    static final int MAX_COLUMNS = 1000;
    /** Letters (Chinese included), digits, _, $ and #. */
    private static final Pattern IDENTIFIER = Pattern.compile("[\\p{L}\\p{N}_$#]+");

    private final String dialect;
    private final String quoteString;
    private final int maxNameLength;

    public NewTableDdl(String dbType, String quoteString, int maxNameLength) {
        this.dialect = dialect(dbType);
        this.quoteString = quoteString;
        this.maxNameLength = maxNameLength > 0 ? maxNameLength : defaultNameLength(dialect);
    }

    public static NewTableDdl forConnection(Connection conn, String dbType) throws SQLException {
        return new NewTableDdl(dbType, conn.getMetaData().getIdentifierQuoteString(), maxIdentifierLength(conn, dbType));
    }

    /** ORACLE, DM, MYSQL, STARROCKS or POSTGRESQL (also used for unknown types: plain ANSI types). */
    public static String dialect(String dbType) {
        String type = dbType == null ? "" : dbType.trim().toUpperCase(Locale.ROOT);
        if (type.startsWith("POSTGRES") || "PG".equals(type)) {
            return "POSTGRESQL";
        }
        switch (type) {
            case "ORACLE":
            case "DM":
            case "MYSQL":
            case "STARROCKS":
                return type;
            default:
                return "POSTGRESQL";
        }
    }

    public String getDialect() {
        return dialect;
    }

    public int getMaxNameLength() {
        return maxNameLength;
    }

    /** MySQL counts identifier length in characters, the others (effectively) in bytes. */
    public boolean lengthInBytes() {
        return !isMysqlFamily();
    }

    /** Longest text column the inference suggests before switching to a long text type. */
    public int autoTextLimit() {
        return "ORACLE".equals(dialect) || "DM".equals(dialect) ? 1000 : 4000;
    }

    /** Longest text column (characters) accepted for TEXT. */
    public int maxTextLength() {
        switch (dialect) {
            case "ORACLE":
                return 4000;
            case "DM":
                return 8188 / 3;
            case "MYSQL":
                return 16383;
            case "STARROCKS":
                return 1048576 / 3;
            default:
                return 10485760;
        }
    }

    static int maxIdentifierLength(Connection conn, String dbType) {
        String dialect = dialect(dbType);
        try {
            DatabaseMetaData meta = conn.getMetaData();
            if ("ORACLE".equals(dialect)) {
                int major = meta.getDatabaseMajorVersion();
                int minor = meta.getDatabaseMinorVersion();
                return major > 12 || (major == 12 && minor >= 2) ? 128 : 30;
            }
            int reported = Math.min(positive(meta.getMaxTableNameLength()), positive(meta.getMaxColumnNameLength()));
            return reported == Integer.MAX_VALUE ? defaultNameLength(dialect) : Math.min(reported, 128);
        } catch (SQLException | RuntimeException | AbstractMethodError e) {
            return defaultNameLength(dialect);
        }
    }

    private static int positive(int value) {
        return value > 0 ? value : Integer.MAX_VALUE;
    }

    private static int defaultNameLength(String dialect) {
        switch (dialect) {
            case "ORACLE":
                return 30;
            case "MYSQL":
            case "STARROCKS":
                return 64;
            case "DM":
                return 128;
            default:
                return 63;
        }
    }

    public int identifierLength(String name) {
        return lengthInBytes() ? name.getBytes(StandardCharsets.UTF_8).length : name.codePointCount(0, name.length());
    }

    /**
     * Names that are valid unquoted identifiers get the letter case the database folds unquoted names to
     * (Oracle / DM upper case, PostgreSQL lower case), so the table can later be queried without quotes.
     * Only ASCII letters change; MySQL names are kept as typed.
     */
    public String canonicalName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (!IDENTIFIER.matcher(name).matches() || Character.isDigit(name.charAt(0))) {
            return name;
        }
        boolean upper = "ORACLE".equals(dialect) || "DM".equals(dialect);
        if (!upper && !"POSTGRESQL".equals(dialect)) {
            return name;
        }
        StringBuilder result = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (upper && c >= 'a' && c <= 'z') {
                c = (char) (c - 'a' + 'A');
            } else if (!upper && c >= 'A' && c <= 'Z') {
                c = (char) (c - 'A' + 'a');
            }
            result.append(c);
        }
        return result.toString();
    }

    void validateName(String name, String what) {
        if (name.isEmpty()) {
            throw new IllegalArgumentException(what + "不能为空");
        }
        if (!IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException(what + "“" + name + "”只能包含字母、数字、汉字、下划线、$ 和 #");
        }
        if (identifierLength(name) > maxNameLength) {
            throw new IllegalArgumentException(what + "“" + name + "”过长：该数据库最多 " + maxNameLength
                    + (lengthInBytes() ? " 字节（一个汉字按 3 字节计）" : " 个字符"));
        }
    }

    /** Validates the definition and renders CREATE TABLE / DROP TABLE for it. */
    public Plan build(String tableName, List<DataImportRequest.NewColumn> columns) {
        String table = canonicalName(tableName);
        validateName(table, "表名");
        List<DataImportRequest.NewColumn> normalized = new ArrayList<>();
        if (columns != null) {
            for (DataImportRequest.NewColumn column : columns) {
                if (column != null) {
                    normalized.add(normalize(column));
                }
            }
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("新表至少需要一个字段");
        }
        if (normalized.size() > MAX_COLUMNS) {
            throw new IllegalArgumentException("字段过多（" + normalized.size() + "），最多 " + MAX_COLUMNS + " 个");
        }
        Set<String> seen = new HashSet<>();
        for (DataImportRequest.NewColumn column : normalized) {
            if (!seen.add(column.getName().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("字段名重复：" + column.getName() + "（不区分大小写）");
            }
        }

        StringBuilder ddl = new StringBuilder("CREATE TABLE ").append(quote(table)).append(" (");
        List<ImportColumn> importColumns = new ArrayList<>();
        List<String> sqlTypes = new ArrayList<>();
        for (int i = 0; i < normalized.size(); i++) {
            DataImportRequest.NewColumn column = normalized.get(i);
            String sqlType = sqlType(column, i == 0);
            sqlTypes.add(sqlType);
            ddl.append(i == 0 ? "\n  " : ",\n  ").append(quote(column.getName())).append(' ').append(sqlType);
            importColumns.add(importColumn(column, sqlType));
        }
        ddl.append("\n)");
        if ("STARROCKS".equals(dialect)) {
            String key = quote(normalized.get(0).getName());
            ddl.append("\nDUPLICATE KEY(").append(key).append(")\nDISTRIBUTED BY HASH(").append(key).append(')');
        } else if ("MYSQL".equals(dialect)) {
            ddl.append(" DEFAULT CHARSET=utf8mb4");
        }
        return new Plan(table, quote(table), ddl.toString(), "DROP TABLE " + quote(table), normalized, sqlTypes,
                importColumns);
    }

    private DataImportRequest.NewColumn normalize(DataImportRequest.NewColumn source) {
        DataImportRequest.NewColumn column = new DataImportRequest.NewColumn();
        String name = canonicalName(source.getName());
        validateName(name, "字段名");
        column.setName(name);
        if (source.getSourceIndex() == null || source.getSourceIndex() < 0) {
            throw new IllegalArgumentException("字段 " + name + " 没有对应的文件列");
        }
        column.setSourceIndex(source.getSourceIndex());
        Kind kind = parseKind(source.getType(), name);
        column.setType(kind.name());
        switch (kind) {
            case TEXT:
                int length = source.getLength() == null ? 0 : source.getLength();
                if (length < 1 || length > maxTextLength()) {
                    throw new IllegalArgumentException("字段 " + name + " 的长度应在 1 到 " + maxTextLength()
                            + " 之间，更长的内容请选择“长文本”");
                }
                column.setLength(length);
                break;
            case DECIMAL:
                int precision = source.getPrecision() == null ? 0 : source.getPrecision();
                int scale = source.getScale() == null ? 0 : source.getScale();
                if (precision < 1 || precision > MAX_DECIMAL_PRECISION) {
                    throw new IllegalArgumentException("字段 " + name + " 的总位数应在 1 到 " + MAX_DECIMAL_PRECISION + " 之间");
                }
                if (scale < 0 || scale > precision) {
                    throw new IllegalArgumentException("字段 " + name + " 的小数位数应在 0 到 " + precision + " 之间");
                }
                column.setPrecision(precision);
                column.setScale(scale);
                break;
            case DATETIME:
                int fraction = source.getScale() == null ? 0 : source.getScale();
                column.setScale(Math.max(0, Math.min(6, fraction)));
                break;
            default:
                break;
        }
        return column;
    }

    private static Kind parseKind(String type, String column) {
        try {
            return Kind.valueOf(type == null ? "" : type.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("字段 " + column + " 的类型无效: " + type);
        }
    }

    String sqlType(DataImportRequest.NewColumn column, boolean firstColumn) {
        Kind kind = Kind.valueOf(column.getType());
        int length = column.getLength() == null ? 0 : column.getLength();
        int scale = column.getScale() == null ? 0 : column.getScale();
        String decimal = "(" + (column.getPrecision() == null ? 0 : column.getPrecision()) + "," + scale + ")";
        switch (dialect) {
            case "ORACLE":
                switch (kind) {
                    case TEXT: return "VARCHAR2(" + length + " CHAR)";
                    case LONG_TEXT: return "CLOB";
                    case INTEGER: return "NUMBER(10)";
                    case BIGINT: return "NUMBER(19)";
                    case DECIMAL: return "NUMBER" + decimal;
                    case DATE: return "DATE";
                    default: return scale > 0 ? "TIMESTAMP(" + scale + ")" : "DATE";
                }
            case "DM":
                switch (kind) {
                    // DM measures VARCHAR in bytes by default: reserve 3 bytes per character (UTF-8)
                    case TEXT: return "VARCHAR(" + Math.min(8188, length * 3) + ")";
                    case LONG_TEXT: return "CLOB";
                    case INTEGER: return "INT";
                    case BIGINT: return "BIGINT";
                    case DECIMAL: return "DECIMAL" + decimal;
                    case DATE: return "DATE";
                    default: return "TIMESTAMP";
                }
            case "MYSQL":
                switch (kind) {
                    case TEXT: return "VARCHAR(" + length + ")";
                    case LONG_TEXT: return "LONGTEXT";
                    case INTEGER: return "INT";
                    case BIGINT: return "BIGINT";
                    case DECIMAL: return "DECIMAL" + decimal;
                    case DATE: return "DATE";
                    default: return scale > 0 ? "DATETIME(" + scale + ")" : "DATETIME";
                }
            case "STARROCKS":
                switch (kind) {
                    // StarRocks VARCHAR length is in bytes
                    case TEXT: return "VARCHAR(" + Math.min(1048576, length * 3) + ")";
                    // the first column is the sort key, which cannot be STRING
                    case LONG_TEXT: return firstColumn ? "VARCHAR(65533)" : "STRING";
                    case INTEGER: return "INT";
                    case BIGINT: return "BIGINT";
                    case DECIMAL: return "DECIMAL" + decimal;
                    case DATE: return "DATE";
                    default: return "DATETIME";
                }
            default:
                switch (kind) {
                    case TEXT: return "VARCHAR(" + length + ")";
                    case LONG_TEXT: return "TEXT";
                    case INTEGER: return "INTEGER";
                    case BIGINT: return "BIGINT";
                    case DECIMAL: return "NUMERIC" + decimal;
                    case DATE: return "DATE";
                    default: return "TIMESTAMP";
                }
        }
    }

    /** Column metadata matching the planned type, used to validate rows before the table exists. */
    private static ImportColumn importColumn(DataImportRequest.NewColumn column, String sqlType) {
        ImportColumn result = new ImportColumn();
        result.setName(column.getName());
        result.setTypeName(sqlType);
        result.setNullable(true);
        switch (Kind.valueOf(column.getType())) {
            case TEXT:
                result.setJdbcType(Types.VARCHAR);
                result.setSize(column.getLength());
                break;
            case LONG_TEXT:
                result.setJdbcType(Types.CLOB);
                break;
            case INTEGER:
                result.setJdbcType(Types.INTEGER);
                break;
            case BIGINT:
                result.setJdbcType(Types.BIGINT);
                break;
            case DECIMAL:
                result.setJdbcType(Types.DECIMAL);
                result.setSize(column.getPrecision());
                result.setDecimalDigits(column.getScale());
                break;
            case DATE:
                result.setJdbcType(Types.DATE);
                break;
            default:
                result.setJdbcType(Types.TIMESTAMP);
                break;
        }
        return result;
    }

    private boolean isMysqlFamily() {
        return "MYSQL".equals(dialect) || "STARROCKS".equals(dialect);
    }

    private String quote(String identifier) {
        try {
            return ImportTableResolver.quote(quoteString, identifier);
        } catch (SQLException e) {
            throw new IllegalArgumentException("名称不合法: " + identifier);
        }
    }

    /** A validated new table definition. */
    public static final class Plan {
        private final String tableName;
        private final String sqlName;
        private final String createSql;
        private final String dropSql;
        private final List<DataImportRequest.NewColumn> columns;
        private final List<String> sqlTypes;
        private final List<ImportColumn> importColumns;

        Plan(String tableName, String sqlName, String createSql, String dropSql,
                List<DataImportRequest.NewColumn> columns, List<String> sqlTypes, List<ImportColumn> importColumns) {
            this.tableName = tableName;
            this.sqlName = sqlName;
            this.createSql = createSql;
            this.dropSql = dropSql;
            this.columns = Collections.unmodifiableList(columns);
            this.sqlTypes = Collections.unmodifiableList(sqlTypes);
            this.importColumns = Collections.unmodifiableList(importColumns);
        }

        public String getTableName() {
            return tableName;
        }

        public String getCreateSql() {
            return createSql;
        }

        public String getDropSql() {
            return dropSql;
        }

        public List<DataImportRequest.NewColumn> getColumns() {
            return columns;
        }

        public List<String> getSqlTypes() {
            return sqlTypes;
        }

        /** File column i goes to new table column i. */
        public List<DataImportRequest.ColumnMapping> mappings() {
            List<DataImportRequest.ColumnMapping> mappings = new ArrayList<>();
            for (DataImportRequest.NewColumn column : columns) {
                DataImportRequest.ColumnMapping mapping = new DataImportRequest.ColumnMapping();
                mapping.setColumn(column.getName());
                mapping.setSource(DataImportRequest.ColumnMapping.SOURCE_COLUMN);
                mapping.setSourceIndex(column.getSourceIndex());
                mappings.add(mapping);
            }
            return mappings;
        }

        /** Stand-in for the table before it exists (validate only, binding checks). */
        public ImportTableResolver.ImportTable plannedTable() {
            return new ImportTableResolver.ImportTable(null, tableName, sqlName, new ArrayList<>(importColumns));
        }
    }
}
