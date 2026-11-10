package com.li.jc.webtool.service.dataimport;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAccessor;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts text values to JDBC parameter objects according to the target column type.
 * Problems are reported as {@link ConversionException} so the import can attribute them to a row and column.
 */
public class ImportValueConverter {

    private static final Pattern DATE_TIME = Pattern.compile(
            "^(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})(?:[ T](\\d{1,2}):(\\d{1,2})(?::(\\d{1,2})(?:[.,](\\d{1,9}))?)?)?(?:Z|[+-]\\d{2}:?\\d{2})?$");
    private static final Pattern COMPACT_DATE_TIME = Pattern.compile("^(\\d{4})(\\d{2})(\\d{2})(?:[ T]?(\\d{2})(\\d{2})(\\d{2})?)?$");
    private static final Pattern TIME = Pattern.compile("^(\\d{1,2}):(\\d{1,2})(?::(\\d{1,2})(?:[.,](\\d{1,9}))?)?$");

    private final boolean emptyAsNull;
    private final Set<String> nullMarkers;
    private final DateTimeFormatter customFormatter;
    private final boolean dateCarriesTime;
    private final boolean postgres;

    /**
     * @param dbType ORACLE / DM / MYSQL / STARROCKS / POSTGRESQL; Oracle and DM DATE columns keep the time part
     */
    public ImportValueConverter(boolean emptyAsNull, Collection<String> nullMarkers, String dateFormat, String dbType) {
        this.emptyAsNull = emptyAsNull;
        this.nullMarkers = new HashSet<>();
        if (nullMarkers != null) {
            for (String marker : nullMarkers) {
                if (marker != null && !marker.isEmpty()) {
                    this.nullMarkers.add(marker);
                }
            }
        }
        String type = dbType == null ? "" : dbType.toUpperCase(Locale.ROOT);
        this.dateCarriesTime = "ORACLE".equals(type) || "DM".equals(type);
        this.postgres = type.startsWith("POSTGRES") || "PG".equals(type);
        if (dateFormat == null || dateFormat.trim().isEmpty()) {
            this.customFormatter = null;
        } else {
            try {
                this.customFormatter = DateTimeFormatter.ofPattern(dateFormat.trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("日期格式不正确: " + dateFormat + "（" + e.getMessage() + "）");
            }
        }
    }

    /** Missing, empty or one of the configured NULL markers. */
    public boolean isBlankOrNull(String raw) {
        return raw == null || raw.isEmpty() || nullMarkers.contains(raw);
    }

    public Object convert(String raw, ImportColumn column) throws ConversionException {
        if (raw == null || nullMarkers.contains(raw)) {
            return null;
        }
        if (raw.isEmpty() && (emptyAsNull || !column.isCharacterType())) {
            return null;
        }
        if (!column.isImportable()) {
            throw new ConversionException("二进制字段不支持从文本导入");
        }
        switch (column.getJdbcType()) {
            case Types.CHAR:
            case Types.VARCHAR:
            case Types.NCHAR:
            case Types.NVARCHAR:
            case Types.LONGVARCHAR:
            case Types.LONGNVARCHAR:
            case Types.CLOB:
            case Types.NCLOB:
                int limit = column.checkedLength();
                if (limit > 0) {
                    int length = raw.codePointCount(0, raw.length());
                    if (length > limit) {
                        throw new ConversionException("长度 " + length + " 超过字段长度 " + limit);
                    }
                }
                return raw;
            case Types.TINYINT:
            case Types.SMALLINT:
            case Types.INTEGER:
            case Types.BIGINT:
                return toInteger(raw.trim(), column.getJdbcType());
            case Types.NUMERIC:
            case Types.DECIMAL:
                return toDecimal(raw.trim());
            case Types.FLOAT:
            case Types.REAL:
            case Types.DOUBLE:
                return toDecimal(raw.trim()).doubleValue();
            case Types.BIT:
            case Types.BOOLEAN:
                return toBoolean(raw.trim());
            case Types.DATE:
                LocalDateTime date = toDateTime(raw.trim());
                return dateCarriesTime ? Timestamp.valueOf(date) : java.sql.Date.valueOf(date.toLocalDate());
            case Types.TIMESTAMP:
            case Types.TIMESTAMP_WITH_TIMEZONE:
                return Timestamp.valueOf(toDateTime(raw.trim()));
            case Types.TIME:
            case Types.TIME_WITH_TIMEZONE:
                return Time.valueOf(toTime(raw.trim()));
            default:
                return raw;
        }
    }

    public void bind(PreparedStatement statement, int index, Object value, ImportColumn column) throws SQLException {
        if (value == null) {
            statement.setNull(index, nullSqlType(column));
        } else if (postgres && column.getJdbcType() == Types.OTHER) {
            // uuid, json, enum ... are sent untyped so PostgreSQL casts the text itself
            statement.setObject(index, value, Types.OTHER);
        } else {
            statement.setObject(index, value);
        }
    }

    private int nullSqlType(ImportColumn column) {
        switch (column.getJdbcType()) {
            case Types.CHAR:
            case Types.VARCHAR:
            case Types.NCHAR:
            case Types.NVARCHAR:
            case Types.LONGVARCHAR:
            case Types.LONGNVARCHAR:
            case Types.CLOB:
            case Types.NCLOB:
            case Types.TINYINT:
            case Types.SMALLINT:
            case Types.INTEGER:
            case Types.BIGINT:
            case Types.NUMERIC:
            case Types.DECIMAL:
            case Types.FLOAT:
            case Types.REAL:
            case Types.DOUBLE:
            case Types.BIT:
            case Types.BOOLEAN:
            case Types.DATE:
            case Types.TIME:
            case Types.TIMESTAMP:
                return column.getJdbcType();
            default:
                return postgres ? Types.OTHER : Types.NULL;
        }
    }

    private Object toInteger(String value, int jdbcType) throws ConversionException {
        BigInteger number;
        try {
            number = toDecimal(value).toBigIntegerExact();
        } catch (ArithmeticException e) {
            throw new ConversionException("整数字段不能包含小数: '" + abbreviate(value) + "'");
        }
        if (jdbcType == Types.BIGINT || number.bitLength() >= 32) {
            if (number.bitLength() >= 64) {
                throw new ConversionException("数值超出范围: '" + abbreviate(value) + "'");
            }
            return number.longValue();
        }
        return number.intValue();
    }

    private BigDecimal toDecimal(String value) throws ConversionException {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw new ConversionException("无法转换为数字: '" + abbreviate(value) + "'");
        }
    }

    private Boolean toBoolean(String value) throws ConversionException {
        switch (value.toLowerCase(Locale.ROOT)) {
            case "1":
            case "true":
            case "t":
            case "y":
            case "yes":
            case "是":
                return Boolean.TRUE;
            case "0":
            case "false":
            case "f":
            case "n":
            case "no":
            case "否":
                return Boolean.FALSE;
            default:
                throw new ConversionException("无法转换为布尔值: '" + abbreviate(value) + "'（支持 1/0、true/false、Y/N）");
        }
    }

    LocalDateTime toDateTime(String value) throws ConversionException {
        if (customFormatter != null) {
            try {
                TemporalAccessor parsed = customFormatter.parseBest(value, LocalDateTime::from, LocalDate::from);
                return parsed instanceof LocalDateTime ? (LocalDateTime) parsed : ((LocalDate) parsed).atStartOfDay();
            } catch (DateTimeParseException ignored) {
                // fall back to built-in formats
            }
        }
        try {
            Matcher m = DATE_TIME.matcher(value);
            if (m.matches()) {
                return LocalDateTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                        Integer.parseInt(m.group(3)), intOrZero(m.group(4)), intOrZero(m.group(5)),
                        intOrZero(m.group(6)), nanos(m.group(7)));
            }
            m = COMPACT_DATE_TIME.matcher(value);
            if (m.matches()) {
                return LocalDateTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                        Integer.parseInt(m.group(3)), intOrZero(m.group(4)), intOrZero(m.group(5)),
                        intOrZero(m.group(6)));
            }
        } catch (DateTimeException e) {
            throw new ConversionException("日期无效: '" + abbreviate(value) + "'");
        }
        throw new ConversionException("日期格式无法识别: '" + abbreviate(value)
                + "'（支持 yyyy-MM-dd [HH:mm:ss]、yyyy/MM/dd、yyyyMMdd，其他格式请填写“日期格式”）");
    }

    private LocalTime toTime(String value) throws ConversionException {
        Matcher m = TIME.matcher(value);
        try {
            if (m.matches()) {
                return LocalTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                        intOrZero(m.group(3)), nanos(m.group(4)));
            }
        } catch (DateTimeException e) {
            throw new ConversionException("时间无效: '" + abbreviate(value) + "'");
        }
        return toDateTime(value).toLocalTime();
    }

    private static int intOrZero(String value) {
        return value == null ? 0 : Integer.parseInt(value);
    }

    private static int nanos(String fraction) {
        if (fraction == null) {
            return 0;
        }
        StringBuilder padded = new StringBuilder(fraction);
        while (padded.length() < 9) {
            padded.append('0');
        }
        return Integer.parseInt(padded.toString());
    }

    static String abbreviate(String value) {
        return value.length() <= 60 ? value : value.substring(0, 57) + "...";
    }

    public static class ConversionException extends Exception {
        public ConversionException(String message) {
            super(message);
        }
    }
}
