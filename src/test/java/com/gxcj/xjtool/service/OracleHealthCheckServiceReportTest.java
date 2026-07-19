package com.gxcj.xjtool.service;

import com.gxcj.xjtool.config.DatabaseConfig;
import com.itextpdf.text.pdf.PdfReader;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class OracleHealthCheckServiceReportTest {

    @Test
    void summaryPdfContainsAllTwentyNineInspectionItems() throws Exception {
        OracleHealthCheckService service = new OracleHealthCheckService(
                new DatabaseConfig(), mock(DatabaseService.class), mock(OracleSshCommandService.class),
                mock(OracleRecycleBinService.class), new OracleHealthReportExporter());
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", "2026-03-18 19:02:29");
        report.put("total", 29);
        report.put("warningCount", 8);
        report.put("criticalCount", 2);
        report.put("skippedCount", 0);
        List<Map<String, Object>> checks = new ArrayList<>();
        for (int i = 1; i <= 29; i++) {
            Map<String, Object> check = new LinkedHashMap<>();
            check.put("name", "数据库智能体检项目 " + i);
            check.put("category", i % 2 == 0 ? "Performance" : "Storage");
            check.put("status", i <= 2 ? "CRITICAL" : (i <= 10 ? "WARNING" : "NORMAL"));
            check.put("suggestion", "这是第 " + i + " 项数据库体检建议，用于确认中文字体、分页和卡片内容均可正常呈现。");
            checks.add(check);
        }
        report.put("checks", checks);

        DatabaseToolsService.ReportFile pdf = service.buildSummaryPdf(report);
        for (int i = 0; i < checks.size(); i++) {
            checks.get(i).put("id", "task" + (i + 1));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("INST_ID", i + 1);
            row.put("STATUS", checks.get(i).get("status"));
            row.put("ERROR_HINT", "第 " + (i + 1) + " 项体检明细内容");
            List<Map<String, Object>> detail = new ArrayList<>();
            detail.add(row);
            checks.get(i).put("detail", detail);
        }
        DatabaseToolsService.ReportFile excel = service.buildExcel(report);
        Files.createDirectories(Paths.get("target"));
        Files.write(Paths.get("target", "test-health-summary.pdf"), pdf.getContent());
        Files.write(Paths.get("target", "test-health-detail.xlsx"), excel.getContent());

        assertTrue(pdf.getFileName().endsWith(".pdf"));
        assertTrue(pdf.getContent().length > 10_000);
        PdfReader reader = new PdfReader(pdf.getContent());
        assertTrue(reader.getNumberOfPages() >= 3);
        assertEquals(29, checks.size());
        reader.close();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(excel.getContent()))) {
            assertEquals(30, workbook.getNumberOfSheets());
            assertEquals("诊断汇总", workbook.getSheetName(0));
        }
    }
}
