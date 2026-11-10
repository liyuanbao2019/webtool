package com.li.jc.webtool.service.dataimport;

import com.li.jc.webtool.dto.DataImportRequest;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Suggests column names and types for a new table from the file's values.
 * Value checks use the same {@link ImportValueConverter} as the import itself, so a suggested type accepts
 * every scanned value. Typing is conservative: numbers with leading zeros (codes) or more than 15 digits
 * (ID card / bank card numbers) stay text, and plain digit strings never become dates.
 */
public final class NewTableColumnInferrer {

    /** Longer digit strings are identifiers rather than quantities. */
    static final int MAX_INTEGER_DIGITS = 15;
    private static final Pattern INTEGER = Pattern.compile("[+-]?(\\d+)");
    private static final Pattern DECIMAL = Pattern.compile("[+-]?(\\d+)\\.(\\d+)");
    private static final Pattern NOT_IDENTIFIER = Pattern.compile("[^\\p{L}\\p{N}_$#]+");
    private static final Pattern SIMPLE_ASCII = Pattern.compile("[A-Za-z0-9_$#]+");
    /** Text length used for columns without any value. */
    static final int EMPTY_COLUMN_LENGTH = 100;
    private static final int MYSQL_ROW_BYTES = 65_000;

    private final ImportValueConverter converter;
    private final NewTableDdl ddl;
    private final List<Stats> stats = new ArrayList<>();
    private long rows;

    public NewTableColumnInferrer(ImportValueConverter converter, NewTableDdl ddl) {
        this.converter = converter;
        this.ddl = ddl;
    }

    public void accept(List<String> fields) {
        rows++;
        for (int i = 0; i < fields.size(); i++) {
            while (stats.size() <= i) {
                stats.add(new Stats());
            }
            stats.get(i).accept(fields.get(i));
        }
    }

    public long getRows() {
        return rows;
    }

    /**
     * One suggestion per file column.
     *
     * @param headers  header names, empty when the file has no header row
     * @param complete whether every row of the file was scanned; if not, lengths get extra headroom
     */
    public List<Suggestion> suggest(List<String> headers, boolean complete) {
        int count = Math.max(headers.size(), stats.size());
        List<Suggestion> result = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (int i = 0; i < count; i++) {
            String header = i < headers.size() ? headers.get(i) : null;
            Stats column = i < stats.size() ? stats.get(i) : new Stats();
            DataImportRequest.NewColumn suggestion = new DataImportRequest.NewColumn();
            suggestion.setSourceIndex(i);
            suggestion.setName(unique(identifier(header, "col_" + (i + 1)), used));
            column.applyType(suggestion, complete);
            result.add(new Suggestion(header, suggestion, column.sample, column.maxLength, column.nonNull));
        }
        if ("MYSQL".equals(ddl.getDialect())) {
            fitMysqlRowSize(result);
        }
        return result;
    }

    /** Table name from the file name, e.g. "客户 2024.csv" becomes 客户_2024. */
    public String suggestTableName(String fileName) {
        String base = fileName == null ? "" : fileName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        return identifier(base, "import_data");
    }

    /** {@code base} plus "_number", shortened so the result stays within the name length limit. */
    public String withSuffix(String base, int number) {
        String suffix = "_" + number;
        return truncate(base, ddl.getMaxNameLength() - ddl.identifierLength(suffix)) + suffix;
    }

    String identifier(String raw, String fallback) {
        String name = raw == null ? "" : raw.trim();
        if (!name.isEmpty() && name.charAt(0) == '﻿') {
            name = name.substring(1);
        }
        name = NOT_IDENTIFIER.matcher(name).replaceAll("_").replaceAll("_{2,}", "_");
        name = name.replaceAll("^_+|_+$", "");
        if (name.isEmpty()) {
            name = fallback;
        }
        if (Character.isDigit(name.charAt(0))) {
            name = (fallback.startsWith("col") ? "c_" : "t_") + name;
        }
        if (SIMPLE_ASCII.matcher(name).matches() && ("MYSQL".equals(ddl.getDialect())
                || "STARROCKS".equals(ddl.getDialect()))) {
            name = name.toLowerCase(Locale.ROOT);
        }
        return truncate(ddl.canonicalName(name), ddl.getMaxNameLength());
    }

    private String unique(String base, Set<String> used) {
        String name = base;
        for (int n = 2; used.contains(name.toLowerCase(Locale.ROOT)); n++) {
            name = withSuffix(base, n);
        }
        used.add(name.toLowerCase(Locale.ROOT));
        return name;
    }

    private String truncate(String name, int max) {
        String result = name;
        while (!result.isEmpty() && ddl.identifierLength(result) > max) {
            result = result.substring(0, result.offsetByCodePoints(0, result.codePointCount(0, result.length()) - 1));
        }
        return result;
    }

    /** MySQL rows are limited to 65535 bytes of VARCHAR data: turn the widest text columns into LONGTEXT. */
    private static void fitMysqlRowSize(List<Suggestion> suggestions) {
        while (true) {
            long bytes = 0;
            DataImportRequest.NewColumn widest = null;
            for (Suggestion suggestion : suggestions) {
                DataImportRequest.NewColumn column = suggestion.getColumn();
                if (NewTableDdl.Kind.TEXT.name().equals(column.getType())) {
                    bytes += column.getLength() * 4L + 2;
                    if (widest == null || column.getLength() > widest.getLength()) {
                        widest = column;
                    }
                } else {
                    bytes += 16;
                }
            }
            if (bytes <= MYSQL_ROW_BYTES || widest == null) {
                return;
            }
            widest.setType(NewTableDdl.Kind.LONG_TEXT.name());
            widest.setLength(null);
        }
    }

    private final class Stats {
        private long nonNull;
        private boolean integer = true;
        private boolean numeric = true;
        private boolean temporal = true;
        private boolean hasTime;
        private int fraction;
        private int maxIntDigits;
        private int maxScale;
        private int maxLength;
        private String sample;

        void accept(String raw) {
            if (converter.isBlankOrNull(raw)) {
                return;
            }
            nonNull++;
            if (sample == null) {
                sample = ImportValueConverter.abbreviate(raw);
            }
            maxLength = Math.max(maxLength, raw.codePointCount(0, raw.length()));
            String value = raw.trim();
            if (numeric) {
                checkNumber(value);
            }
            if (temporal) {
                checkTemporal(value);
            }
        }

        private void checkNumber(String value) {
            Matcher m = INTEGER.matcher(value);
            if (m.matches()) {
                String digits = m.group(1);
                if (leadingZero(digits) || digits.length() > MAX_INTEGER_DIGITS) {
                    integer = false;
                    numeric = false;
                } else {
                    maxIntDigits = Math.max(maxIntDigits, digits.length());
                }
                return;
            }
            integer = false;
            m = DECIMAL.matcher(value);
            if (m.matches() && !leadingZero(m.group(1))
                    && m.group(1).length() + m.group(2).length() <= NewTableDdl.MAX_DECIMAL_PRECISION) {
                maxIntDigits = Math.max(maxIntDigits, m.group(1).length());
                maxScale = Math.max(maxScale, m.group(2).length());
            } else {
                numeric = false;
            }
        }

        private void checkTemporal(String value) {
            if (value.chars().allMatch(Character::isDigit)) {
                temporal = false;
                return;
            }
            try {
                LocalDateTime parsed = converter.toDateTime(value);
                if (value.indexOf(':') >= 0 || !LocalTime.MIDNIGHT.equals(parsed.toLocalTime())) {
                    hasTime = true;
                }
                if (parsed.getNano() > 0) {
                    fraction = Math.max(fraction, parsed.getNano() % 1_000_000 == 0 ? 3 : 6);
                }
            } catch (ImportValueConverter.ConversionException e) {
                temporal = false;
            }
        }

        void applyType(DataImportRequest.NewColumn column, boolean complete) {
            if (nonNull == 0) {
                text(column, EMPTY_COLUMN_LENGTH);
            } else if (integer) {
                column.setType((maxIntDigits >= (complete ? 10 : 9) ? NewTableDdl.Kind.BIGINT
                        : NewTableDdl.Kind.INTEGER).name());
            } else if (numeric && maxIntDigits + maxScale <= NewTableDdl.MAX_DECIMAL_PRECISION) {
                column.setType(NewTableDdl.Kind.DECIMAL.name());
                column.setScale(maxScale);
                column.setPrecision(Math.min(NewTableDdl.MAX_DECIMAL_PRECISION,
                        maxIntDigits + maxScale + (complete ? 2 : 4)));
            } else if (temporal) {
                column.setType((hasTime ? NewTableDdl.Kind.DATETIME : NewTableDdl.Kind.DATE).name());
                if (hasTime) {
                    column.setScale(fraction);
                }
            } else {
                int limit = ddl.autoTextLimit();
                if (maxLength > limit) {
                    column.setType(NewTableDdl.Kind.LONG_TEXT.name());
                } else {
                    text(column, Math.min(limit, complete ? maxLength : maxLength * 2));
                }
            }
        }

        private void text(DataImportRequest.NewColumn column, int target) {
            int length = NewTableDdl.TEXT_TIERS[NewTableDdl.TEXT_TIERS.length - 1];
            for (int tier : NewTableDdl.TEXT_TIERS) {
                if (tier >= target) {
                    length = tier;
                    break;
                }
            }
            column.setType(NewTableDdl.Kind.TEXT.name());
            column.setLength(length);
        }
    }

    private static boolean leadingZero(String digits) {
        return digits.length() > 1 && digits.charAt(0) == '0';
    }

    /** Suggested column plus what the scan saw, for display. */
    public static final class Suggestion {
        private final String header;
        private final DataImportRequest.NewColumn column;
        private final String sample;
        private final int maxLength;
        private final long nonNull;

        Suggestion(String header, DataImportRequest.NewColumn column, String sample, int maxLength, long nonNull) {
            this.header = header;
            this.column = column;
            this.sample = sample;
            this.maxLength = maxLength;
            this.nonNull = nonNull;
        }

        public String getHeader() {
            return header;
        }

        public DataImportRequest.NewColumn getColumn() {
            return column;
        }

        public String getSample() {
            return sample;
        }

        public int getMaxLength() {
            return maxLength;
        }

        public long getNonNull() {
            return nonNull;
        }
    }
}
