package com.li.jc.webtool.service.dataimport;

import com.li.jc.webtool.config.DatabaseConfig;
import com.li.jc.webtool.dto.DataImportRequest;
import com.li.jc.webtool.service.SqlAuditService;
import com.li.jc.webtool.service.impl.DatabaseConnectionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DataImportServiceTest {

    @TempDir
    Path tempDir;

    private String url;
    private Connection keepAlive;
    private DataImportService service;
    private SqlAuditService audit;

    @BeforeEach
    void setUp() throws Exception {
        url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        keepAlive = DriverManager.getConnection(url);
        try (Statement statement = keepAlive.createStatement()) {
            statement.execute("CREATE TABLE customer (id INT PRIMARY KEY, name VARCHAR(20) NOT NULL)");
        }
        DatabaseConnectionManager connections = mock(DatabaseConnectionManager.class);
        DatabaseConfig.DataSourceConfig source = new DatabaseConfig.DataSourceConfig();
        source.setName("h2");
        when(connections.getDataSourceConfig(0)).thenReturn(source);
        when(connections.getDatabaseType(0)).thenReturn("POSTGRESQL");
        when(connections.getConnection(0)).thenAnswer(invocation -> DriverManager.getConnection(url));
        audit = mock(SqlAuditService.class);
        service = new DataImportService(connections, new DatabaseConfig(), audit, tempDir);
    }

    @AfterEach
    void tearDown() throws Exception {
        service.shutdown();
        keepAlive.close();
    }

    @Test
    void uploadDetectsFormatPreviewAndImportForOwningSessionOnly() throws Exception {
        byte[] content = "编号|姓名\n1|张三\n2|李四|多余\n".getBytes(Charset.forName("GBK"));
        Map<String, Object> uploaded = service.upload(new MockMultipartFile("file", "客户.txt", "text/plain", content),
                "s1", "alice");
        String importId = (String) uploaded.get("importId");

        assertEquals("GB18030", uploaded.get("encoding"));
        assertEquals("|", uploaded.get("delimiter"));
        assertThrows(IllegalArgumentException.class, () -> service.status(importId, "other-session"));

        DataImportRequest.Preview preview = new DataImportRequest.Preview();
        preview.setImportId(importId);
        preview.getOptions().setEncoding("GB18030");
        preview.getOptions().setDelimiter("|");
        Map<String, Object> previewResult = service.preview(preview, "s1");
        assertEquals(Arrays.asList("编号", "姓名", "(无表头列3)"), previewResult.get("headers"));
        assertEquals(1, previewResult.get("mismatchedRows"));

        Map<String, Object> columns = service.tableColumns(0, "customer");
        assertEquals("PUBLIC.CUSTOMER", columns.get("table"));
        assertEquals(2, ((List<?>) columns.get("columns")).size());

        DataImportRequest.Start start = new DataImportRequest.Start();
        start.setImportId(importId);
        start.setDatasourceIndex(0);
        start.setTableName("customer");
        start.setOptions(preview.getOptions());
        start.setErrorStrategy("skip_row");
        start.setMappings(Arrays.asList(mapping("ID", 0), mapping("NAME", 1)));
        service.start(start, "s1", "alice");
        Map<String, Object> status = awaitFinished(importId);

        assertEquals("PARTIAL", status.get("status"), String.valueOf(status.get("message")));
        assertEquals(1L, status.get("committedRows"));
        assertEquals(Boolean.TRUE, status.get("hasReport"));
        try (Statement statement = keepAlive.createStatement();
             ResultSet rs = statement.executeQuery("SELECT name FROM customer")) {
            assertTrue(rs.next());
            assertEquals("张三", rs.getString(1));
        }
        DataImportService.ErrorFile report = service.errorFile(importId, "s1", "report");
        String reportText = new String(Files.readAllBytes(report.getPath()), StandardCharsets.UTF_8);
        assertTrue(reportText.contains("列数不符"));
        DataImportService.ErrorFile raw = service.errorFile(importId, "s1", "raw");
        assertEquals("客户_未导入数据.txt", raw.getFileName());
        assertEquals("编号|姓名\n2|李四|多余\n",
                new String(Files.readAllBytes(raw.getPath()), Charset.forName("GB18030")).replace("\r\n", "\n"));
        verify(audit).logSqlExecution(eq("alice"), contains("DATA_IMPORT INTO PUBLIC.CUSTOMER"), eq(true), isNull(),
                anyLong(), eq(1), eq("h2"));

        service.discard(importId, "s1");
        assertThrows(IllegalArgumentException.class, () -> service.status(importId, "s1"));
        assertFalse(Files.exists(raw.getPath()));
    }

    @Test
    void createModeInfersColumnsCreatesTableImportsAndReportsSummary() throws Exception {
        byte[] content = "ID,Name,Amount,Day\n1,张三,10.50,2024-01-05\n2,李四,3,2024-01-06\n"
                .getBytes(StandardCharsets.UTF_8);
        String importId = (String) service.upload(new MockMultipartFile("file", "客户 名单.csv", "text/csv", content),
                "s1", "alice").get("importId");

        DataImportRequest.InferColumns infer = new DataImportRequest.InferColumns();
        infer.setImportId(importId);
        infer.setDatasourceIndex(0);
        Map<String, Object> inferred = service.inferColumns(infer, "s1");
        assertEquals("客户_名单", inferred.get("tableName"));
        assertEquals(Boolean.TRUE, inferred.get("complete"));
        List<?> suggested = (List<?>) inferred.get("columns");
        assertEquals(Arrays.asList("id:INTEGER", "name:TEXT", "amount:DECIMAL", "day:DATE"),
                suggested.stream().map(item -> ((Map<?, ?>) item).get("name") + ":" + ((Map<?, ?>) item).get("type"))
                        .collect(java.util.stream.Collectors.toList()));
        List<DataImportRequest.NewColumn> columns = Arrays.asList(
                NewTableDdlTest.col(0, "id", "INTEGER", null, null, null),
                NewTableDdlTest.col(1, "name", "TEXT", 50, null, null),
                NewTableDdlTest.col(2, "amount", "DECIMAL", null, 6, 2),
                NewTableDdlTest.col(3, "day", "DATE", null, null, null));

        DataImportRequest.CreateTablePreview preview = new DataImportRequest.CreateTablePreview();
        preview.setDatasourceIndex(0);
        preview.setTableName("客户_名单");
        preview.setColumns(columns);
        Map<String, Object> ddl = service.previewCreateTable(preview);
        assertTrue(String.valueOf(ddl.get("ddl")).startsWith("CREATE TABLE \"客户_名单\""), String.valueOf(ddl.get("ddl")));
        assertEquals(Boolean.FALSE, ddl.get("tableExists"));

        DataImportRequest.Start start = createStart(importId, "客户_名单", columns, "ROLLBACK_ALL");
        service.start(start, "s1", "alice");
        Map<String, Object> status = awaitFinished(importId);

        assertEquals("SUCCESS", status.get("status"), String.valueOf(status.get("message")));
        assertTrue(String.valueOf(status.get("message")).startsWith("已新建表 客户_名单"), String.valueOf(status.get("message")));
        assertEquals("CREATE", status.get("mode"));
        assertEquals(Boolean.TRUE, status.get("tableCreated"));
        assertEquals("PUBLIC.客户_名单", status.get("table"));
        List<?> summary = (List<?>) status.get("columns");
        assertEquals(4, summary.size());
        assertEquals("name", ((Map<?, ?>) summary.get(1)).get("name"));
        assertEquals("Name", ((Map<?, ?>) summary.get(1)).get("sourceName"));
        try (Statement statement = keepAlive.createStatement();
             ResultSet rs = statement.executeQuery("SELECT \"name\", \"amount\" FROM \"客户_名单\" ORDER BY \"id\"")) {
            assertTrue(rs.next());
            assertEquals("张三", rs.getString(1));
            assertEquals(0, new java.math.BigDecimal("10.50").compareTo(rs.getBigDecimal(2)));
            assertTrue(rs.next());
        }
        verify(audit).logSqlExecution(eq("alice"), startsWith("CREATE TABLE \"客户_名单\""), eq(true), isNull(),
                anyLong(), eq(0), eq("h2"));
        verify(audit).logSqlExecution(eq("alice"), contains("(NEW TABLE)"), eq(true), isNull(), anyLong(), eq(2), eq("h2"));

        IllegalArgumentException exists = assertThrows(IllegalArgumentException.class,
                () -> service.start(createStart(importId, "客户_名单", columns, "ROLLBACK_ALL"), "s1", "alice"));
        assertTrue(exists.getMessage().contains("已存在"), exists.getMessage());
        assertEquals(Boolean.TRUE, service.previewCreateTable(preview).get("tableExists"));
    }

    @Test
    void createModeDropsNewTableWhenNothingWasImportedAndKeepsItWithData() throws Exception {
        byte[] content = "id,name\n1,张三\n2,王小二五\n".getBytes(StandardCharsets.UTF_8);
        String importId = (String) service.upload(new MockMultipartFile("file", "t.csv", "text/csv", content),
                "s1", "alice").get("importId");
        List<DataImportRequest.NewColumn> columns = Arrays.asList(
                NewTableDdlTest.col(0, "id", "INTEGER", null, null, null),
                NewTableDdlTest.col(1, "name", "TEXT", 2, null, null));

        service.start(createStart(importId, "new_table", columns, "ROLLBACK_ALL"), "s1", "alice");
        Map<String, Object> rolledBack = awaitFinished(importId);
        assertEquals("ROLLED_BACK", rolledBack.get("status"));
        assertEquals(Boolean.TRUE, rolledBack.get("tableDropped"));
        assertTrue(String.valueOf(rolledBack.get("message")).contains("已自动删除本次新建的表 new_table"),
                String.valueOf(rolledBack.get("message")));
        assertFalse(tableExists("new_table"));
        verify(audit).logSqlExecution(eq("alice"), eq("DROP TABLE \"new_table\""), eq(true), isNull(), anyLong(),
                eq(0), eq("h2"));

        service.start(createStart(importId, "new_table", columns, "SKIP_ROW"), "s1", "alice");
        Map<String, Object> partial = awaitFinished(importId);
        assertEquals("PARTIAL", partial.get("status"));
        assertEquals(Boolean.FALSE, partial.get("tableDropped"));
        assertTrue(String.valueOf(partial.get("message")).contains("已保留（含已导入的 1 行）"),
                String.valueOf(partial.get("message")));
        assertTrue(tableExists("new_table"));
    }

    @Test
    void excelUploadsAreConvertedAndErrorsUseExcelRowNumbers() throws Exception {
        Path book;
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("客户");
            String[][] rows = {{"id", "name", "note"}, {"1", "张\n三", "x"}, null, {"2", "李四"}, {"abc", "王五"}};
            for (int r = 0; r < rows.length; r++) {
                if (rows[r] == null) {
                    continue; // Excel row 3 stays blank
                }
                org.apache.poi.ss.usermodel.Row row = sheet.createRow(r);
                for (int c = 0; c < rows[r].length; c++) {
                    if (r > 0 && c == 0 && rows[r][c].matches("\\d+")) {
                        row.createCell(c).setCellValue(Double.parseDouble(rows[r][c]));
                    } else {
                        row.createCell(c).setCellValue(rows[r][c]);
                    }
                }
            }
            workbook.createSheet("其他").createRow(0).createCell(0).setCellValue("only");
            book = tempDir.resolve("客户.xlsx");
            try (java.io.OutputStream out = Files.newOutputStream(book)) {
                workbook.write(out);
            }
        }
        Map<String, Object> uploaded = service.upload(new MockMultipartFile("file", "客户.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", Files.readAllBytes(book)), "s1", "alice");
        String importId = (String) uploaded.get("importId");
        assertEquals("EXCEL", uploaded.get("sourceType"));
        assertEquals(Arrays.asList("客户", "其他"), uploaded.get("sheets"));
        assertEquals(",", uploaded.get("delimiter"));

        DataImportRequest.Preview preview = new DataImportRequest.Preview();
        preview.setImportId(importId);
        preview.getOptions().setDelimiter("|"); // ignored for Excel
        Map<String, Object> previewResult = service.preview(preview, "s1");
        assertEquals(Arrays.asList("id", "name", "note"), previewResult.get("headers"));
        assertEquals(0, previewResult.get("mismatchedRows"));
        assertEquals(Arrays.asList("2", "李四", ""), ((List<?>) previewResult.get("rows")).get(1));

        DataImportRequest.Start start = new DataImportRequest.Start();
        start.setImportId(importId);
        start.setDatasourceIndex(0);
        start.setTableName("customer");
        start.setErrorStrategy("SKIP_ROW");
        start.setMappings(Arrays.asList(mapping("ID", 0), mapping("NAME", 1)));
        service.start(start, "s1", "alice");
        Map<String, Object> status = awaitFinished(importId);

        assertEquals("PARTIAL", status.get("status"), String.valueOf(status.get("message")));
        assertEquals(2L, status.get("committedRows"));
        Map<?, ?> error = (Map<?, ?>) ((List<?>) status.get("errors")).get(0);
        assertEquals(5L, error.get("line"));
        assertEquals("ID", error.get("column"));
        DataImportService.ErrorFile raw = service.errorFile(importId, "s1", "raw");
        assertEquals("客户_未导入数据.csv", raw.getFileName());
        assertEquals("﻿id,name,note\nabc,王五\n",
                new String(Files.readAllBytes(raw.getPath()), StandardCharsets.UTF_8).replace("\r\n", "\n"));

        Map<String, Object> other = service.selectSheet(importId, 1, "s1");
        assertEquals(1, other.get("sheetIndex"));
        assertEquals("IDLE", service.status(importId, "s1").get("status"));
        assertEquals(Arrays.asList("only"), service.preview(preview, "s1").get("headers"));
    }

    @Test
    void excelMergedTwoRowHeaderIsDetectedAndCombined() throws Exception {
        Path book;
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("分摊");
            String[] top = {"月份", "部门", "姓名", "分摊比例"};
            String[] sub = {"资产运营", "算网运营", "其他"};
            org.apache.poi.ss.usermodel.Row first = sheet.createRow(0);
            org.apache.poi.ss.usermodel.Row second = sheet.createRow(1);
            for (int c = 0; c < top.length; c++) {
                first.createCell(c).setCellValue(top[c]);
            }
            for (int c = 0; c < 3; c++) {
                sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0, 1, c, c));
            }
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 3, 5));
            for (int c = 0; c < sub.length; c++) {
                second.createCell(3 + c).setCellValue(sub[c]);
            }
            second.createCell(6).setCellValue("备注");
            Object[][] data = {{6, "西北中心", "韩兴辉", 1, null, null}, {6, "西北中心", "王鹏业", null, 0.33, 0.67}};
            for (int r = 0; r < data.length; r++) {
                org.apache.poi.ss.usermodel.Row row = sheet.createRow(2 + r);
                for (int c = 0; c < data[r].length; c++) {
                    Object value = data[r][c];
                    if (value instanceof Number) {
                        row.createCell(c).setCellValue(((Number) value).doubleValue());
                    } else if (value != null) {
                        row.createCell(c).setCellValue((String) value);
                    }
                }
            }
            book = tempDir.resolve("分摊.xlsx");
            try (java.io.OutputStream out = Files.newOutputStream(book)) {
                workbook.write(out);
            }
        }
        String importId = (String) service.upload(new MockMultipartFile("file", "分摊.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", Files.readAllBytes(book)),
                "s1", "alice").get("importId");

        DataImportRequest.Preview preview = new DataImportRequest.Preview();
        preview.setImportId(importId);
        Map<String, Object> single = service.preview(preview, "s1");
        assertEquals(2, single.get("detectedHeaderRows"));
        assertEquals(4, single.get("expectedColumns"));

        preview.getOptions().setHeaderRows(2);
        Map<String, Object> merged = service.preview(preview, "s1");
        assertEquals(Arrays.asList("月份", "部门", "姓名", "资产运营", "算网运营", "其他", "备注"), merged.get("headers"));
        assertEquals(0, merged.get("mismatchedRows"));
        assertEquals(2, merged.get("scannedRows"));
        assertEquals(Arrays.asList("6", "西北中心", "王鹏业", "", "0.33", "0.67", ""),
                ((List<?>) merged.get("rows")).get(1));

        DataImportRequest.Start start = new DataImportRequest.Start();
        start.setImportId(importId);
        start.setDatasourceIndex(0);
        start.setTableName("customer");
        start.setValidateOnly(true);
        start.getOptions().setHeaderRows(2);
        start.setMappings(Arrays.asList(mapping("ID", 0), mapping("NAME", 2)));
        service.start(start, "s1", "alice");
        Map<String, Object> status = awaitFinished(importId);
        assertEquals(0L, status.get("errorCount"), String.valueOf(status.get("errors")));
        assertEquals(2L, status.get("processedRows"));
    }

    @Test
    void textUploadsCannotSwitchSheets() throws Exception {
        String importId = (String) service.upload(new MockMultipartFile("file", "a.csv", "text/csv",
                "id\n1\n".getBytes(StandardCharsets.UTF_8)), "s1", "alice").get("importId");
        assertThrows(IllegalArgumentException.class, () -> service.selectSheet(importId, 1, "s1"));
    }

    private boolean tableExists(String name) throws Exception {
        try (ResultSet rs = keepAlive.getMetaData().getTables(null, "PUBLIC", name, null)) {
            return rs.next();
        }
    }

    private static DataImportRequest.Start createStart(String importId, String table,
            List<DataImportRequest.NewColumn> columns, String strategy) {
        DataImportRequest.Start start = new DataImportRequest.Start();
        start.setImportId(importId);
        start.setDatasourceIndex(0);
        start.setMode("create");
        start.setTableName(table);
        start.setNewColumns(columns);
        start.setErrorStrategy(strategy);
        return start;
    }

    private Map<String, Object> awaitFinished(String importId) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Map<String, Object> status = service.status(importId, "s1");
            if (Boolean.TRUE.equals(status.get("finished"))) {
                return status;
            }
            Thread.sleep(25);
        }
        fail("import did not finish");
        return null;
    }

    private static DataImportRequest.ColumnMapping mapping(String column, int index) {
        DataImportRequest.ColumnMapping mapping = new DataImportRequest.ColumnMapping();
        mapping.setColumn(column);
        mapping.setSourceIndex(index);
        return mapping;
    }
}
