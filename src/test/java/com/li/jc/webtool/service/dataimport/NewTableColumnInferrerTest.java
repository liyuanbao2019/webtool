package com.li.jc.webtool.service.dataimport;

import com.li.jc.webtool.dto.DataImportRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class NewTableColumnInferrerTest {

    private static NewTableColumnInferrer inferrer(String dbType) {
        return new NewTableColumnInferrer(new ImportValueConverter(true, Arrays.asList("NULL", "\\N"), null, dbType),
                new NewTableDdl(dbType, "ORACLE".equals(dbType) ? "\"" : "`", "ORACLE".equals(dbType) ? 30 : 64));
    }

    private static List<DataImportRequest.NewColumn> infer(NewTableColumnInferrer inferrer, List<String> headers,
            boolean complete, String[]... rows) {
        for (String[] row : rows) {
            inferrer.accept(Arrays.asList(row));
        }
        return inferrer.suggest(headers, complete).stream().map(NewTableColumnInferrer.Suggestion::getColumn)
                .collect(Collectors.toList());
    }

    private static String describe(DataImportRequest.NewColumn column) {
        switch (column.getType()) {
            case "TEXT":
                return "TEXT(" + column.getLength() + ")";
            case "DECIMAL":
                return "DECIMAL(" + column.getPrecision() + "," + column.getScale() + ")";
            default:
                return column.getType();
        }
    }

    @Test
    void infersConservativeTypes() {
        List<String> headers = Arrays.asList("id", "big", "amount", "code", "id_card", "day", "time", "ymd", "name", "empty");
        List<DataImportRequest.NewColumn> columns = infer(inferrer("MYSQL"), headers, true,
                new String[]{"1", "12345678901", "10.5", "007", "110101199001011234", "2024-01-05", "2024-01-05 08:30:00", "20240105", "张三", ""},
                new String[]{"2", "-3", "-1234.25", "123", "11010119900101123X", "2024/1/6", "2024-01-06 00:00:00", "20240106", "李四四", "NULL"},
                new String[]{"NULL", "", "7", "", "", "", "", "", "", "\\N"});
        assertEquals(Arrays.asList("INTEGER", "BIGINT", "DECIMAL(8,2)", "TEXT(50)", "TEXT(50)", "DATE", "DATETIME",
                        "INTEGER", "TEXT(50)", "TEXT(100)"),
                columns.stream().map(NewTableColumnInferrerTest::describe).collect(Collectors.toList()));
        assertEquals(Integer.valueOf(0), columns.get(6).getScale());
    }

    @Test
    void sampledFilesGetHeadroom() {
        String longText = String.join("", Collections.nCopies(60, "x"));
        List<DataImportRequest.NewColumn> complete = infer(inferrer("MYSQL"), Collections.emptyList(), true,
                new String[]{longText, "123456789", "12.5"});
        List<DataImportRequest.NewColumn> sampled = infer(inferrer("MYSQL"), Collections.emptyList(), false,
                new String[]{longText, "123456789", "12.5"});
        assertEquals(Arrays.asList("TEXT(100)", "INTEGER", "DECIMAL(5,1)"),
                complete.stream().map(NewTableColumnInferrerTest::describe).collect(Collectors.toList()));
        assertEquals(Arrays.asList("TEXT(200)", "BIGINT", "DECIMAL(7,1)"),
                sampled.stream().map(NewTableColumnInferrerTest::describe).collect(Collectors.toList()));
    }

    @Test
    void veryLongTextBecomesLongText() {
        String text = String.join("", Collections.nCopies(1001, "长"));
        List<DataImportRequest.NewColumn> oracle = infer(inferrer("ORACLE"), Collections.emptyList(), true,
                new String[]{text});
        assertEquals("LONG_TEXT", oracle.get(0).getType());
        List<DataImportRequest.NewColumn> mysql = infer(inferrer("MYSQL"), Collections.emptyList(), true,
                new String[]{text});
        assertEquals("TEXT(2000)", describe(mysql.get(0)));
    }

    @Test
    void mysqlRowSizeLimitTurnsWidestColumnsIntoLongText() {
        String[] row = new String[20];
        List<String> headers = new ArrayList<>();
        for (int i = 0; i < row.length; i++) {
            row[i] = String.join("", Collections.nCopies(i == 0 ? 3000 : 900, "a"));
            headers.add("c" + i);
        }
        List<DataImportRequest.NewColumn> columns = infer(inferrer("MYSQL"), headers, true, row);
        assertEquals("LONG_TEXT", columns.get(0).getType());
        long bytes = columns.stream().filter(c -> "TEXT".equals(c.getType())).mapToLong(c -> c.getLength() * 4L).sum();
        assertTrue(bytes <= 65_000, String.valueOf(bytes));
    }

    @Test
    void namesAreCleanedUniqueAndWithinLimits() {
        List<String> headers = Arrays.asList(" Customer Name ", "customer-name", "", "2024销量", "金额(元)",
                "﻿id", "一二三四五六七八九十一二");
        List<DataImportRequest.NewColumn> oracle = infer(inferrer("ORACLE"), headers, true,
                new String[]{"a", "b", "c", "d", "e", "f", "g"});
        assertEquals(Arrays.asList("CUSTOMER_NAME", "CUSTOMER_NAME_2", "COL_3", "C_2024销量", "金额_元", "ID",
                        "一二三四五六七八九十"),
                oracle.stream().map(DataImportRequest.NewColumn::getName).collect(Collectors.toList()));
        List<DataImportRequest.NewColumn> mysql = infer(inferrer("MYSQL"), Arrays.asList("Customer Name"), true,
                new String[]{"a", "extra"});
        assertEquals(Arrays.asList("customer_name", "col_2"),
                mysql.stream().map(DataImportRequest.NewColumn::getName).collect(Collectors.toList()));
    }

    @Test
    void suggestsTableNamesFromFileNames() {
        assertEquals("客户_2024", inferrer("MYSQL").suggestTableName("客户 2024.csv"));
        assertEquals("T_2024_SALES", inferrer("ORACLE").suggestTableName("2024 sales.txt"));
        assertEquals("import_data", inferrer("MYSQL").suggestTableName("().csv"));
        assertEquals("t_1", inferrer("MYSQL").suggestTableName("(1).csv"));
        assertEquals("ORDERS_3", inferrer("ORACLE").withSuffix("ORDERS", 3));
    }
}
