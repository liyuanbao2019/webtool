package com.li.jc.webtool.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Request models for the SQL tool CSV / delimited text import. */
public final class DataImportRequest {

    private DataImportRequest() {
    }

    /** How the uploaded text file is split into records and fields. */
    @Data
    public static class ParseOptions {
        /** UTF-8, GBK, GB18030 ... */
        private String encoding = "UTF-8";
        /** Literal separator; escapes \t, \\, \\uXXXX and \\xHH are accepted. Empty means one column per line. */
        private String delimiter = ",";
        /** Quote character; empty string means plain text without quoting. */
        private String quote = "\"";
        private boolean hasHeader = true;
        /** Physical lines to skip before the header / first data row. */
        private int skipLines;
        private boolean trimValues = true;
        /** Drop the empty field produced by a separator at the end of each line, e.g. "a|b|c|". */
        private boolean ignoreTrailingDelimiter;
    }

    @Data
    public static class Preview {
        private String importId;
        private ParseOptions options = new ParseOptions();
    }

    @Data
    public static class ColumnMapping {
        public static final String SOURCE_COLUMN = "COLUMN";
        public static final String SOURCE_CONSTANT = "CONSTANT";

        /** Target table column name as reported by the table-columns endpoint. */
        private String column;
        /** COLUMN = value from file field {@link #sourceIndex}; CONSTANT = {@link #constantValue} for all rows. */
        private String source = SOURCE_COLUMN;
        /** 0-based field index in the file. */
        private Integer sourceIndex;
        private String constantValue;
    }

    /** One column of a table created by the import. */
    @Data
    public static class NewColumn {
        /** 0-based field index in the file. */
        private Integer sourceIndex;
        private String name;
        /** TEXT, LONG_TEXT, INTEGER, BIGINT, DECIMAL, DATE, DATETIME. */
        private String type;
        /** TEXT: maximum characters. */
        private Integer length;
        /** DECIMAL: total digits. */
        private Integer precision;
        /** DECIMAL: digits after the point; DATETIME: fractional second digits. */
        private Integer scale;
    }

    /** Suggest column names and types for a new table from the file content. */
    @Data
    public static class InferColumns {
        private String importId;
        private Integer datasourceIndex;
        private ParseOptions options = new ParseOptions();
        private List<String> nullMarkers = new ArrayList<>();
        private String dateFormat;
    }

    /** Render (and validate) the CREATE TABLE statement for the given definition. */
    @Data
    public static class CreateTablePreview {
        private Integer datasourceIndex;
        private String tableName;
        private List<NewColumn> columns = new ArrayList<>();
    }

    @Data
    public static class Start {
        public static final String STRATEGY_ROLLBACK_ALL = "ROLLBACK_ALL";
        public static final String STRATEGY_SKIP_ROW = "SKIP_ROW";
        public static final String STRATEGY_STOP = "STOP";
        public static final String MODE_EXISTING = "EXISTING";
        public static final String MODE_CREATE = "CREATE";

        private String importId;
        private Integer datasourceIndex;
        /** EXISTING: import into {@link #tableName}; CREATE: create {@link #tableName} from {@link #newColumns} first. */
        private String mode = MODE_EXISTING;
        private String tableName;
        /** CREATE mode: the columns of the new table; the mappings are derived from them. */
        private List<NewColumn> newColumns = new ArrayList<>();
        /** CREATE mode: drop the new table again when the import ends without any committed row. */
        private boolean dropTableOnFailure = true;
        private ParseOptions options = new ParseOptions();
        private List<ColumnMapping> mappings = new ArrayList<>();
        /**
         * ROLLBACK_ALL: one transaction, any error rolls everything back.
         * SKIP_ROW: commit per batch, failed rows are skipped and reported.
         * STOP: commit per batch, stop at the first failed row and keep rows committed before it.
         */
        private String errorStrategy = STRATEGY_ROLLBACK_ALL;
        /** Only parse and convert every row, never write to the database. */
        private boolean validateOnly;
        private int batchSize = 500;
        private int maxErrors = 1000;
        private boolean emptyAsNull = true;
        /** Values treated as NULL, e.g. NULL, \N. */
        private List<String> nullMarkers = new ArrayList<>();
        /** Optional java.time pattern tried before the built-in date formats, e.g. dd/MM/yyyy HH:mm. */
        private String dateFormat;
        /** Reject rows whose field count differs from the header (or first row when there is no header). */
        private boolean strictColumnCount = true;
    }
}
