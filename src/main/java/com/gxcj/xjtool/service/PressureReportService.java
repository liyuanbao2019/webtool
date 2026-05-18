package com.gxcj.xjtool.service;

import com.gxcj.xjtool.model.PressureResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.util.Map;

/**
 * 压测报告导出服务（支持 Excel）
 */
@Slf4j
@Service
public class PressureReportService {

    /**
     * 导出 Excel 报告
     */
    public void exportExcel(PressureResult result, HttpServletResponse response) throws IOException {
        String filename = generateFilename("xlsx");
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=" + filename);

        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            // 创建样式
            CellStyle headerStyle = createHeaderStyle(workbook);
            CellStyle valueStyle = createValueStyle(workbook);
            CellStyle titleStyle = createTitleStyle(workbook);
            CellStyle highlightStyle = createHighlightStyle(workbook, "FF10B981"); // 绿色
            CellStyle warningStyle = createHighlightStyle(workbook, "FFF59E0B"); // 橙色
            CellStyle dangerStyle = createHighlightStyle(workbook, "FFEF4444"); // 红色

            // Sheet1: 测试摘要
            Sheet sheet1 = workbook.createSheet("测试摘要");
            createSummarySheet(sheet1, result, headerStyle, valueStyle, titleStyle);

            // Sheet2: 性能指标
            Sheet sheet2 = workbook.createSheet("性能指标");
            createMetricsSheet(sheet2, result, headerStyle, valueStyle, titleStyle);

            // Sheet3: 分析结论
            Sheet sheet3 = workbook.createSheet("分析结论");
            createAnalysisSheet(sheet3, result, headerStyle, valueStyle, titleStyle, highlightStyle, warningStyle, dangerStyle);

            workbook.write(baos);

            OutputStream os = response.getOutputStream();
            baos.writeTo(os);
            os.flush();
            os.close();

        } catch (Exception e) {
            log.error("生成 Excel 报告失败", e);
            throw new IOException("生成 Excel 报告失败", e);
        }
    }

    private void createSummarySheet(Sheet sheet, PressureResult result, CellStyle headerStyle, CellStyle valueStyle, CellStyle titleStyle) {
        int rowIndex = 0;

        // 标题行
        Row titleRow = sheet.createRow(rowIndex++);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("API 压测报告 - 测试摘要");
        titleCell.setCellStyle(titleStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 1));

        rowIndex++; // 空行

        // 配置信息
        Row configHeader = sheet.createRow(rowIndex++);
        configHeader.createCell(0).setCellValue("配置项");
        configHeader.createCell(1).setCellValue("值");
        configHeader.getCell(0).setCellStyle(headerStyle);
        configHeader.getCell(1).setCellStyle(headerStyle);

        addDataRow(sheet, rowIndex++, "目标URL", result.getConfig().getUrl(), valueStyle);
        addDataRow(sheet, rowIndex++, "请求方法", result.getConfig().getMethod(), valueStyle);
        addDataRow(sheet, rowIndex++, "并发数", String.valueOf(result.getConfig().getConcurrency()), valueStyle);

        String duration = result.getConfig().getDurationSeconds() != null
                ? result.getConfig().getDurationSeconds() + "秒"
                : result.getSummary().getDurationSeconds() + "秒";
        addDataRow(sheet, rowIndex++, "测试时长", duration, valueStyle);

        if (result.getConfig().getQpsLimit() > 0) {
            addDataRow(sheet, rowIndex++, "QPS限制", String.valueOf(result.getConfig().getQpsLimit()), valueStyle);
        }
        if (result.getConfig().getDelayMs() > 0) {
            addDataRow(sheet, rowIndex++, "请求延迟", result.getConfig().getDelayMs() + "ms", valueStyle);
        }

        rowIndex++;
        addDataRow(sheet, rowIndex++, "开始时间", result.getSummary().getStartTime(), valueStyle);
        addDataRow(sheet, rowIndex++, "结束时间", result.getSummary().getEndTime(), valueStyle);
        addDataRow(sheet, rowIndex++, "完成请求数", String.valueOf(result.getSummary().getCompletedRequests()), valueStyle);

        // 设置列宽
        sheet.setColumnWidth(0, 4000);
        sheet.setColumnWidth(1, 8000);
    }

    private void createMetricsSheet(Sheet sheet, PressureResult result, CellStyle headerStyle, CellStyle valueStyle, CellStyle titleStyle) {
        int rowIndex = 0;

        // 标题行
        Row titleRow = sheet.createRow(rowIndex++);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("API 压测报告 - 性能指标");
        titleCell.setCellStyle(titleStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 1));

        rowIndex++;

        PressureResult.PerformanceMetrics m = result.getMetrics();

        // 请求统计
        Row reqHeader = sheet.createRow(rowIndex++);
        reqHeader.createCell(0).setCellValue("请求统计");
        reqHeader.getCell(0).setCellStyle(headerStyle);
        sheet.addMergedRegion(new CellRangeAddress(rowIndex-1, rowIndex-1, 0, 1));

        addDataRow(sheet, rowIndex++, "总请求数", String.valueOf(result.getSummary().getCompletedRequests()), valueStyle);
        addDataRow(sheet, rowIndex++, "成功数", String.valueOf(m.getSuccessCount()) + " (" + String.format("%.2f", m.getSuccessRate()) + "%)", valueStyle);
        addDataRow(sheet, rowIndex++, "失败数", String.valueOf(m.getFailCount()) + " (" + String.format("%.2f", m.getFailRate()) + "%)", valueStyle);

        rowIndex++;
        Row timeHeader = sheet.createRow(rowIndex++);
        timeHeader.createCell(0).setCellValue("响应时间 (ms)");
        timeHeader.getCell(0).setCellStyle(headerStyle);
        sheet.addMergedRegion(new CellRangeAddress(rowIndex-1, rowIndex-1, 0, 1));

        addDataRow(sheet, rowIndex++, "平均响应时间", String.format("%.2f ms", m.getAvgResponseTime()), valueStyle);
        addDataRow(sheet, rowIndex++, "最小响应时间", m.getMinResponseTime() + " ms", valueStyle);
        addDataRow(sheet, rowIndex++, "最大响应时间", m.getMaxResponseTime() + " ms", valueStyle);
        addDataRow(sheet, rowIndex++, "TP90", m.getTp90() + " ms", valueStyle);
        addDataRow(sheet, rowIndex++, "TP95", m.getTp95() + " ms", valueStyle);
        addDataRow(sheet, rowIndex++, "TP99", m.getTp99() + " ms", valueStyle);

        rowIndex++;
        Row qpsHeader = sheet.createRow(rowIndex++);
        qpsHeader.createCell(0).setCellValue("吞吐量");
        qpsHeader.getCell(0).setCellStyle(headerStyle);
        sheet.addMergedRegion(new CellRangeAddress(rowIndex-1, rowIndex-1, 0, 1));
        addDataRow(sheet, rowIndex++, "实际 QPS", String.format("%.2f req/s", m.getQps()), valueStyle);

        rowIndex++;
        Row distHeader = sheet.createRow(rowIndex++);
        distHeader.createCell(0).setCellValue("响应时间分布");
        distHeader.createCell(1).setCellValue("请求数");
        distHeader.getCell(0).setCellStyle(headerStyle);
        distHeader.getCell(1).setCellStyle(headerStyle);

        Map<String, Integer> distribution = m.getResponseTimeDistribution();
        if (distribution != null) {
            int total = 0;
            for (Integer count : distribution.values()) {
                total += count;
            }
            for (Map.Entry<String, Integer> entry : distribution.entrySet()) {
                int count = entry.getValue();
                double percent = total > 0 ? (double) count * 100 / total : 0;
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(entry.getKey());
                row.getCell(0).setCellStyle(valueStyle);
                row.createCell(1).setCellValue(count + " (" + String.format("%.1f", percent) + "%)");
                row.getCell(1).setCellStyle(valueStyle);
            }
        }

        sheet.setColumnWidth(0, 4000);
        sheet.setColumnWidth(1, 6000);
    }

    private void createAnalysisSheet(Sheet sheet, PressureResult result, CellStyle headerStyle, CellStyle valueStyle, 
                                     CellStyle titleStyle, CellStyle highlightStyle, CellStyle warningStyle, CellStyle dangerStyle) {
        int rowIndex = 0;

        // 标题行
        Row titleRow = sheet.createRow(rowIndex++);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("API 压测报告 - 分析结论");
        titleCell.setCellStyle(titleStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 1));

        rowIndex++;

        PressureResult.AnalysisResult analysis = result.getAnalysis();

        // 综合评级
        Row levelHeader = sheet.createRow(rowIndex++);
        levelHeader.createCell(0).setCellValue("综合评级");
        levelHeader.getCell(0).setCellStyle(headerStyle);
        sheet.addMergedRegion(new CellRangeAddress(rowIndex-1, rowIndex-1, 0, 1));

        Row levelRow = sheet.createRow(rowIndex++);
        Cell levelCell = levelRow.createCell(0);
        levelCell.setCellValue(analysis.getOverallLevel());
        
        // 根据评级设置样式
        String level = analysis.getOverallLevel();
        if ("优秀".equals(level)) {
            levelCell.setCellStyle(highlightStyle);
        } else if ("良好".equals(level)) {
            levelCell.setCellStyle(warningStyle);
        } else if ("差".equals(level)) {
            levelCell.setCellStyle(dangerStyle);
        } else {
            levelCell.setCellStyle(valueStyle);
        }
        sheet.addMergedRegion(new CellRangeAddress(rowIndex-1, rowIndex-1, 0, 1));

        rowIndex++;
        Row perfHeader = sheet.createRow(rowIndex++);
        perfHeader.createCell(0).setCellValue("性能评级");
        perfHeader.createCell(1).setCellValue(analysis.getPerformanceLevel());
        perfHeader.getCell(0).setCellStyle(headerStyle);
        perfHeader.getCell(1).setCellStyle(valueStyle);

        Row stabHeader = sheet.createRow(rowIndex++);
        stabHeader.createCell(0).setCellValue("稳定性评级");
        stabHeader.createCell(1).setCellValue(analysis.getStabilityLevel());
        stabHeader.getCell(0).setCellStyle(headerStyle);
        stabHeader.getCell(1).setCellStyle(valueStyle);

        rowIndex++;
        Row concHeader = sheet.createRow(rowIndex++);
        concHeader.createCell(0).setCellValue("分析结论");
        concHeader.getCell(0).setCellStyle(headerStyle);
        sheet.addMergedRegion(new CellRangeAddress(rowIndex-1, rowIndex-1, 0, 1));

        if (analysis.getConclusions() != null) {
            for (String conclusion : analysis.getConclusions()) {
                Row row = sheet.createRow(rowIndex++);
                Cell cell = row.createCell(0);
                cell.setCellValue(conclusion);
                cell.setCellStyle(valueStyle);
                sheet.addMergedRegion(new CellRangeAddress(rowIndex-1, rowIndex-1, 0, 1));
            }
        }

        rowIndex++;
        Row suggHeader = sheet.createRow(rowIndex++);
        suggHeader.createCell(0).setCellValue("优化建议");
        suggHeader.getCell(0).setCellStyle(headerStyle);
        sheet.addMergedRegion(new CellRangeAddress(rowIndex-1, rowIndex-1, 0, 1));

        if (analysis.getSuggestions() != null) {
            for (String suggestion : analysis.getSuggestions()) {
                Row row = sheet.createRow(rowIndex++);
                Cell cell = row.createCell(0);
                cell.setCellValue(suggestion);
                cell.setCellStyle(valueStyle);
                sheet.addMergedRegion(new CellRangeAddress(rowIndex-1, rowIndex-1, 0, 1));
            }
        }

        // 报告生成时间
        rowIndex += 2;
        Row footerRow = sheet.createRow(rowIndex);
        Cell footerCell = footerRow.createCell(0);
        footerCell.setCellValue("报告生成时间: " + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, 0, 1));

        sheet.setColumnWidth(0, 6000);
        sheet.setColumnWidth(1, 4000);
    }

    private void addDataRow(Sheet sheet, int rowIndex, String label, String value, CellStyle style) {
        Row row = sheet.createRow(rowIndex);
        row.createCell(0).setCellValue(label);
        row.createCell(1).setCellValue(value);
        row.getCell(0).setCellStyle(style);
        row.getCell(1).setCellStyle(style);
    }

    private CellStyle createHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 11);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private CellStyle createValueStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setFontHeightInPoints((short) 10);
        style.setFont(font);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private CellStyle createTitleStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 14);
        style.setFont(font);
        return style;
    }

    private CellStyle createHighlightStyle(Workbook workbook, String rgbColor) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 14);
        // 使用 HSSFColor 或直接使用 short 类型
        try {
            short colorIndex = Short.parseShort(rgbColor.replace("#", ""), 16);
            font.setColor(colorIndex);
        } catch (Exception e) {
            // 使用默认颜色
        }
        style.setFont(font);
        return style;
    }

    private String generateFilename(String extension) {
        String timestamp = java.time.LocalDateTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        try {
            return URLEncoder.encode("压测报告_" + timestamp + "." + extension, "UTF-8");
        } catch (Exception e) {
            return "pressure_test_report." + extension;
        }
    }

    /**
     * 导出 PDF 报告（简单文本版本）
     */
    public void exportPdf(PressureResult result, HttpServletResponse response) throws IOException {
        // PDF 导出暂不可用，请使用 Excel 导出
        log.warn("PDF 导出功能暂时不可用，请使用 Excel 导出");
        throw new IOException("PDF 导出功能暂时不可用，请使用 Excel 导出");
    }
}
