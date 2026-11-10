package com.li.jc.webtool.service.dataimport;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class ImportValueConverterTest {

    private static ImportColumn column(int jdbcType, int size) {
        ImportColumn column = new ImportColumn();
        column.setName("C");
        column.setJdbcType(jdbcType);
        column.setSize(size);
        return column;
    }

    private final ImportValueConverter converter =
            new ImportValueConverter(true, Arrays.asList("NULL", "\\N"), null, "MYSQL");

    @Test
    void convertsNumbersAndRejectsInvalidValues() throws Exception {
        assertEquals(42, converter.convert(" 42 ", column(Types.INTEGER, 10)));
        assertEquals(3000000000L, converter.convert("3000000000", column(Types.INTEGER, 10)));
        assertEquals(7L, converter.convert("7.0", column(Types.BIGINT, 19)));
        assertEquals(new BigDecimal("12.50"), converter.convert("12.50", column(Types.DECIMAL, 10)));
        assertEquals(1.5d, converter.convert("1.5", column(Types.DOUBLE, 0)));

        assertTrue(assertThrows(ImportValueConverter.ConversionException.class,
                () -> converter.convert("12a", column(Types.DECIMAL, 10))).getMessage().contains("无法转换为数字"));
        assertTrue(assertThrows(ImportValueConverter.ConversionException.class,
                () -> converter.convert("1.5", column(Types.INTEGER, 10))).getMessage().contains("小数"));
    }

    @Test
    void handlesNullMarkersAndEmptyValues() throws Exception {
        assertNull(converter.convert("NULL", column(Types.VARCHAR, 10)));
        assertNull(converter.convert("\\N", column(Types.INTEGER, 10)));
        assertNull(converter.convert("", column(Types.VARCHAR, 10)));
        assertNull(converter.convert("", column(Types.INTEGER, 10)));

        ImportValueConverter keepEmpty = new ImportValueConverter(false, Collections.emptyList(), null, "MYSQL");
        assertEquals("", keepEmpty.convert("", column(Types.VARCHAR, 10)));
        assertNull(keepEmpty.convert("", column(Types.INTEGER, 10)));
    }

    @Test
    void checksCharacterLengthByCodePoints() throws Exception {
        assertEquals("中文字符", converter.convert("中文字符", column(Types.VARCHAR, 4)));
        ImportValueConverter.ConversionException error = assertThrows(ImportValueConverter.ConversionException.class,
                () -> converter.convert("abcdef", column(Types.VARCHAR, 5)));
        assertTrue(error.getMessage().contains("长度 6 超过字段长度 5"));
    }

    @Test
    void parsesCommonDateFormats() throws Exception {
        LocalDateTime expected = LocalDateTime.of(2024, 1, 5, 9, 3, 0);
        assertEquals(expected, converter.toDateTime("2024-01-05 09:03:00"));
        assertEquals(expected, converter.toDateTime("2024/1/5 9:03"));
        assertEquals(expected, converter.toDateTime("2024-01-05T09:03:00"));
        assertEquals(expected, converter.toDateTime("20240105090300"));
        assertEquals(LocalDateTime.of(2024, 1, 5, 0, 0), converter.toDateTime("20240105"));
        assertEquals(LocalDateTime.of(2024, 1, 5, 9, 3, 0, 120_000_000), converter.toDateTime("2024-01-05 09:03:00.12"));

        assertTrue(assertThrows(ImportValueConverter.ConversionException.class,
                () -> converter.toDateTime("2024-02-30")).getMessage().contains("日期无效"));
        assertTrue(assertThrows(ImportValueConverter.ConversionException.class,
                () -> converter.toDateTime("05/01/2024")).getMessage().contains("日期格式无法识别"));
    }

    @Test
    void customDateFormatAndOracleDateKeepsTime() throws Exception {
        ImportValueConverter oracle = new ImportValueConverter(true, null, "dd/MM/yyyy HH:mm", "ORACLE");
        Object value = oracle.convert("05/01/2024 09:03", column(Types.DATE, 7));
        assertEquals(Timestamp.valueOf(LocalDateTime.of(2024, 1, 5, 9, 3)), value);

        Object mysqlDate = converter.convert("2024-01-05 09:03:00", column(Types.DATE, 10));
        assertEquals(java.sql.Date.valueOf("2024-01-05"), mysqlDate);

        assertThrows(IllegalArgumentException.class, () -> new ImportValueConverter(true, null, "yyyy-MM-dd {", "MYSQL"));
    }

    @Test
    void convertsBooleansAndRejectsBinaryColumns() throws Exception {
        assertEquals(Boolean.TRUE, converter.convert("Y", column(Types.BOOLEAN, 1)));
        assertEquals(Boolean.FALSE, converter.convert("0", column(Types.BIT, 1)));
        assertThrows(ImportValueConverter.ConversionException.class, () -> converter.convert("maybe", column(Types.BIT, 1)));
        assertThrows(ImportValueConverter.ConversionException.class, () -> converter.convert("abc", column(Types.BLOB, 0)));
    }
}
