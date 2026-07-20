package com.li.jc.webtool.service;

import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Generates the Excel, PDF and ZIP artifacts for an Oracle health report. */
@Component
public class OracleHealthReportExporter {

    public DatabaseToolsService.ReportFile buildBundle(Map<String, Object> report) {
        DatabaseToolsService.ReportFile excel = buildExcel(report);
        DatabaseToolsService.ReportFile pdf = buildSummaryPdf(report);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry(pdf.getFileName()));
            zip.write(pdf.getContent());
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(excel.getFileName()));
            zip.write(excel.getContent());
            zip.closeEntry();
            zip.finish();
            String fileName = "家客数据库智能体检报告_"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + ".zip";
            return new DatabaseToolsService.ReportFile(fileName, output.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("打包智能体检报告失败: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public DatabaseToolsService.ReportFile buildSummaryPdf(Map<String, Object> report) {
        List<Map<String, Object>> checks = (List<Map<String, Object>>) report.get("checks");
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            com.itextpdf.text.Document document = new com.itextpdf.text.Document(
                    com.itextpdf.text.PageSize.A4, 42, 42, 40, 40);
            com.itextpdf.text.pdf.PdfWriter.getInstance(document, output);
            com.itextpdf.text.pdf.BaseFont baseFont = loadChinesePdfFont();
            com.itextpdf.text.Font titleFont = new com.itextpdf.text.Font(baseFont, 18,
                    com.itextpdf.text.Font.BOLD, new com.itextpdf.text.BaseColor(34, 34, 34));
            com.itextpdf.text.Font bodyFont = new com.itextpdf.text.Font(baseFont, 10,
                    com.itextpdf.text.Font.NORMAL, new com.itextpdf.text.BaseColor(55, 55, 55));
            com.itextpdf.text.Font taskFont = new com.itextpdf.text.Font(baseFont, 11,
                    com.itextpdf.text.Font.BOLD, new com.itextpdf.text.BaseColor(30, 30, 30));
            document.open();
            com.itextpdf.text.Paragraph title = new com.itextpdf.text.Paragraph("家客装维数据库智能体检摘要报告", titleFont);
            title.setAlignment(com.itextpdf.text.Element.ALIGN_CENTER);
            title.setSpacingAfter(14);
            document.add(title);
            document.add(new com.itextpdf.text.Paragraph("生成时间：" + text(report.get("generatedAt")), bodyFont));
            document.add(new com.itextpdf.text.Paragraph("RAC节点："
                    + Math.round(number(report.get("racReachableNodeCount"))) + "/"
                    + Math.round(number(report.get("racNodeCount"))) + " 可连接", bodyFont));
            com.itextpdf.text.Paragraph stats = new com.itextpdf.text.Paragraph(
                    "共 " + report.get("total") + " 项，警告 " + report.get("warningCount")
                            + " 项，严重 " + report.get("criticalCount") + " 项，跳过 " + report.get("skippedCount") + " 项",
                    new com.itextpdf.text.Font(baseFont, 11, com.itextpdf.text.Font.BOLD));
            stats.setSpacingBefore(5);
            stats.setSpacingAfter(12);
            document.add(stats);
            for (int i = 0; i < checks.size(); i++) {
                Map<String, Object> check = checks.get(i);
                com.itextpdf.text.pdf.PdfPTable card = new com.itextpdf.text.pdf.PdfPTable(1);
                card.setWidthPercentage(100);
                card.setKeepTogether(true);
                com.itextpdf.text.pdf.PdfPCell heading = new com.itextpdf.text.pdf.PdfPCell(
                        new com.itextpdf.text.Phrase((i + 1) + ". " + text(check.get("name"))
                                + " (" + text(check.get("category")) + ")", taskFont));
                heading.setBackgroundColor(new com.itextpdf.text.BaseColor(247, 249, 252));
                heading.setBorderColor(new com.itextpdf.text.BaseColor(220, 225, 232));
                heading.setPadding(7);
                card.addCell(heading);
                String status = normalizeStatus(check.get("status"));
                com.itextpdf.text.BaseColor color = "CRITICAL".equals(status) || "ERROR".equals(status)
                        ? new com.itextpdf.text.BaseColor(198, 40, 40)
                        : ("WARNING".equals(status) ? new com.itextpdf.text.BaseColor(216, 126, 0)
                        : ("NORMAL".equals(status) ? new com.itextpdf.text.BaseColor(20, 130, 70)
                        : new com.itextpdf.text.BaseColor(100, 100, 100)));
                com.itextpdf.text.Paragraph content = new com.itextpdf.text.Paragraph();
                content.add(new com.itextpdf.text.Chunk("状态：" + displayStatus(status) + "\n",
                        new com.itextpdf.text.Font(baseFont, 10, com.itextpdf.text.Font.BOLD, color)));
                content.add(new com.itextpdf.text.Chunk("建议：" + text(check.get("suggestion")), bodyFont));
                com.itextpdf.text.pdf.PdfPCell body = new com.itextpdf.text.pdf.PdfPCell(content);
                body.setBorderColor(new com.itextpdf.text.BaseColor(220, 225, 232));
                body.setPadding(7);
                card.addCell(body);
                card.setSpacingAfter(8);
                document.add(card);
            }
            document.close();
            String fileName = "家客数据库智能体检报告摘要_"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + ".pdf";
            return new DatabaseToolsService.ReportFile(fileName, output.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("生成智能体检 PDF 失败: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public DatabaseToolsService.ReportFile buildExcel(Map<String, Object> report) {
        List<Map<String, Object>> checks = (List<Map<String, Object>>) report.get("checks");
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Map<String, String> detailSheets = new LinkedHashMap<>();
            for (Map<String, Object> check : checks) {
                String sheetName = uniqueSheetName(workbook, safeSheetName(String.valueOf(check.get("name"))));
                detailSheets.put(String.valueOf(check.get("id")), sheetName);
                buildDetailSheet(workbook, sheetName, check);
            }
            buildSummarySheet(workbook, report, checks, detailSheets);
            workbook.setSheetOrder("诊断汇总", 0);
            workbook.setActiveSheet(0);
            workbook.write(output);
            String fileName = "家客数据库智能体检报告明细_"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".xlsx";
            return new DatabaseToolsService.ReportFile(fileName, output.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("导出智能体检 Excel 失败: " + e.getMessage(), e);
        }
    }

    private static com.itextpdf.text.pdf.BaseFont loadChinesePdfFont() throws Exception {
        String[] candidates = {
                "C:/Windows/Fonts/msyh.ttc,0",
                "C:/Windows/Fonts/simsun.ttc,0",
                "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc,0",
                "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc,0"
        };
        for (String candidate : candidates) {
            String path = candidate.endsWith(",0") ? candidate.substring(0, candidate.length() - 2) : candidate;
            if (new File(path).isFile()) {
                return com.itextpdf.text.pdf.BaseFont.createFont(candidate,
                        com.itextpdf.text.pdf.BaseFont.IDENTITY_H, com.itextpdf.text.pdf.BaseFont.EMBEDDED);
            }
        }
        throw new IllegalStateException("未找到可用于生成中文 PDF 的字体（Microsoft YaHei/Noto Sans CJK）");
    }


    private void buildSummarySheet(Workbook workbook, Map<String, Object> report,
                                   List<Map<String, Object>> checks, Map<String, String> detailSheets) {
        Sheet sheet = workbook.createSheet("诊断汇总");
        Styles styles = new Styles(workbook);
        Row title = sheet.createRow(0);
        title.createCell(0).setCellValue("家客数据库智能体检报告汇总");
        title.getCell(0).setCellStyle(styles.title);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 5));
        title.setHeightInPoints(30);
        Row generated = sheet.createRow(1);
        generated.createCell(0).setCellValue("生成时间(Generate Time)");
        generated.getCell(0).setCellStyle(styles.header);
        generated.createCell(1).setCellValue(text(report.get("generatedAt")));
        Row statistics = sheet.createRow(2);
        statistics.createCell(0).setCellValue("统计(Statistics)");
        statistics.getCell(0).setCellStyle(styles.header);
        statistics.createCell(1).setCellValue("RAC: " + Math.round(number(report.get("racReachableNodeCount"))) + "/"
                + Math.round(number(report.get("racNodeCount"))) + "，Warning: " + report.get("warningCount") + "，Critical: "
                + report.get("criticalCount") + "，Error/Skip: " + report.get("skippedCount"));
        String[] headers = {"任务ID(Task ID)", "任务名称(Task Name)", "分类(Category)", "状态(Status)",
                "建议(Suggestion)", "详情跳转(Details Link)"};
        Row header = sheet.createRow(4);
        for (int i = 0; i < headers.length; i++) {
            header.createCell(i).setCellValue(headers[i]);
            header.getCell(i).setCellStyle(styles.header);
        }
        CreationHelper helper = workbook.getCreationHelper();
        for (int i = 0; i < checks.size(); i++) {
            Map<String, Object> check = checks.get(i);
            Row row = sheet.createRow(i + 5);
            row.createCell(0).setCellValue(text(check.get("id")));
            row.createCell(1).setCellValue(text(check.get("name")));
            row.createCell(2).setCellValue(text(check.get("category")));
            Cell status = row.createCell(3);
            status.setCellValue(displayStatus(check.get("status")));
            status.setCellStyle(styles.status(text(check.get("status"))));
            Cell suggestion = row.createCell(4);
            suggestion.setCellValue(text(check.get("suggestion")));
            suggestion.setCellStyle(styles.wrap);
            Cell link = row.createCell(5);
            link.setCellValue("打开详情");
            Hyperlink hyperlink = helper.createHyperlink(HyperlinkType.DOCUMENT);
            hyperlink.setAddress("'" + detailSheets.get(text(check.get("id"))) + "'!A1");
            link.setHyperlink(hyperlink);
            link.setCellStyle(styles.link);
            row.setHeightInPoints(44);
        }
        int[] widths = {22, 38, 18, 14, 100, 20};
        for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);
        sheet.createFreezePane(0, 5);
        sheet.setAutoFilter(new CellRangeAddress(4, 4 + checks.size(), 0, 5));
    }

    @SuppressWarnings("unchecked")
    private void buildDetailSheet(Workbook workbook, String sheetName, Map<String, Object> check) {
        Sheet sheet = workbook.createSheet(sheetName);
        Styles styles = new Styles(workbook);
        CreationHelper helper = workbook.getCreationHelper();
        Row back = sheet.createRow(0);
        Cell backCell = back.createCell(0);
        backCell.setCellValue("⬅ 返回诊断汇总(点击跳转)");
        Hyperlink hyperlink = helper.createHyperlink(HyperlinkType.DOCUMENT);
        hyperlink.setAddress("'诊断汇总'!A1");
        backCell.setHyperlink(hyperlink);
        backCell.setCellStyle(styles.link);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 3));
        Row taskHeader = sheet.createRow(1);
        taskHeader.createCell(0).setCellValue("任务信息(Task Info)");
        taskHeader.getCell(0).setCellStyle(styles.title);
        sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, 3));
        addInfoRow(sheet, 2, "任务名称(Task Name)", text(check.get("name")), styles);
        addInfoRow(sheet, 3, "状态(Status)", displayStatus(check.get("status")), styles);
        addInfoRow(sheet, 4, "建议(Suggestion)", text(check.get("suggestion")), styles);
        List<Map<String, Object>> rows = check.get("detail") instanceof List
                ? (List<Map<String, Object>>) check.get("detail") : Collections.emptyList();
        if (rows.isEmpty()) {
            sheet.createRow(6).createCell(0).setCellValue("无明细数据");
            sheet.setColumnWidth(0, 28 * 256);
            return;
        }
        LinkedHashSet<String> allColumns = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) allColumns.addAll(row.keySet());
        List<String> columns = new ArrayList<>(allColumns);
        Row header = sheet.createRow(6);
        for (int i = 0; i < columns.size(); i++) {
            header.createCell(i).setCellValue(fieldLabel(columns.get(i)));
            header.getCell(i).setCellStyle(styles.header);
        }
        for (int r = 0; r < rows.size(); r++) {
            Row excelRow = sheet.createRow(r + 7);
            for (int c = 0; c < columns.size(); c++) {
                Object value = rows.get(r).get(columns.get(c));
                Cell cell = excelRow.createCell(c);
                if (value instanceof Number) cell.setCellValue(((Number) value).doubleValue());
                else cell.setCellValue(value == null ? "" : String.valueOf(value));
                cell.setCellStyle(styles.wrap);
            }
        }
        for (int i = 0; i < columns.size(); i++) {
            sheet.autoSizeColumn(i);
            sheet.setColumnWidth(i, Math.min(Math.max(sheet.getColumnWidth(i) + 512, 14 * 256), 60 * 256));
        }
        sheet.createFreezePane(0, 7);
        sheet.setAutoFilter(new CellRangeAddress(6, 6 + rows.size(), 0, columns.size() - 1));
    }

    private static void addInfoRow(Sheet sheet, int rowIndex, String label, String value, Styles styles) {
        Row row = sheet.createRow(rowIndex);
        row.createCell(0).setCellValue(label);
        row.getCell(0).setCellStyle(styles.header);
        row.createCell(1).setCellValue(value);
        row.getCell(1).setCellStyle(styles.wrap);
        sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, 1, 3));
    }


    private static String safeSheetName(String value) {
        String result = value == null ? "任务" : value.replaceAll("[\\\\/:*?\\[\\]]", "_");
        return result.length() > 31 ? result.substring(0, 31) : result;
    }

    private static String uniqueSheetName(Workbook workbook, String base) {
        String candidate = base;
        for (int i = 2; workbook.getSheet(candidate) != null; i++) {
            String suffix = "_" + i;
            candidate = base.substring(0, Math.min(base.length(), 31 - suffix.length())) + suffix;
        }
        return candidate;
    }

    private static String fieldLabel(String key) {
        String normalized = normalizeKey(key);
        String label = FIELD_LABELS.get(normalized);
        return label == null ? key : label;
    }

    private static final Map<String, String> FIELD_LABELS = createFieldLabels();
    private static Map<String, String> createFieldLabels() {
        Map<String, String> map = new HashMap<>();
        map.put("RAC_NODE", "RAC节点地址(RAC_NODE)");
        map.put("CONNECTION_STATUS", "节点连接状态(CONNECTION_STATUS)");
        map.put("INSTANCE_HOST", "实例主机名(INSTANCE_HOST)");
        map.put("INSTANCE_STATUS", "实例状态(INSTANCE_STATUS)");
        map.put("NODE_STATUS", "节点检查状态(NODE_STATUS)");
        map.put("NODE_SUGGESTION", "节点检查建议(NODE_SUGGESTION)");
        map.put("ERROR_MESSAGE", "错误信息(ERROR_MESSAGE)");
        map.put("INST_ID", "实例号(INST_ID)"); map.put("INSTANCE_NAME", "实例名(INSTANCE_NAME)");
        map.put("HOST_NAME", "主机名(HOST_NAME)"); map.put("STATUS", "状态(STATUS)"); map.put("VERSION", "版本(VERSION)");
        map.put("TABLESPACE_NAME", "表空间名(TABLESPACE_NAME)"); map.put("TOTAL_SPACE_MB", "总空间MB(TOTAL_SPACE_MB)");
        map.put("FREE_SPACE_MB", "可用空间MB(FREE_SPACE_MB)"); map.put("USED_PERCENT", "使用率%(USED_PERCENT)");
        map.put("NAME", "名称(NAME)"); map.put("TOTAL_MB", "总容量MB(TOTAL_MB)"); map.put("FREE_MB", "剩余容量MB(FREE_MB)");
        map.put("EVENT", "等待事件(EVENT)"); map.put("WAITING_SESSION_COUNT", "等待会话数(WAITING_SESSION_COUNT)");
        map.put("WAITING_OBJECT_COUNT", "等待对象数(WAITING_OBJECT_COUNT)"); map.put("WAITING_OBJECTS", "等待对象列表(WAITING_OBJECTS)");
        map.put("THREAD_", "线程号(THREAD#)"); map.put("SWITCH_HOUR", "切换小时(SWITCH_HOUR)"); map.put("SWITCHES", "切换次数(SWITCHES)");
        map.put("SQL_ID", "SQL标识(SQL_ID)"); map.put("PLAN_HASH_VALUE", "执行计划哈希(PLAN_HASH_VALUE)");
        map.put("EXECUTIONS", "执行次数(EXECUTIONS)"); map.put("TOTAL_ELAPSED_SEC", "总耗时秒(TOTAL_ELAPSED_SEC)");
        map.put("AVG_ELAPSED_SEC", "平均耗时秒(AVG_ELAPSED_SEC)"); map.put("SQL_TEXT_EXCERPT", "SQL文本片段(SQL_TEXT_EXCERPT)");
        map.put("SESSION_ID", "会话ID(SESSION_ID)"); map.put("SERIAL_NO", "会话序列号(SERIAL_NO)");
        map.put("USERNAME", "用户名(USERNAME)"); map.put("BLOCKING_SESSION", "阻塞会话(BLOCKING_SESSION)");
        map.put("WAIT_CLASS", "等待类别(WAIT_CLASS)"); map.put("SECONDS_IN_WAIT", "等待秒数(SECONDS_IN_WAIT)");
        map.put("BLOCKER_SQL_ID", "阻塞SQL标识(BLOCKER_SQL_ID)"); map.put("WAITING_OBJECT", "等待对象(WAITING_OBJECT)");
        map.put("OWNER", "对象所有者(OWNER)"); map.put("OBJECT_TYPE", "对象类型(OBJECT_TYPE)");
        map.put("OBJECT_NAME", "对象名称(OBJECT_NAME)"); map.put("INDEX_NAME", "索引名称(INDEX_NAME)"); map.put("TABLE_NAME", "表名(TABLE_NAME)");
        map.put("ACCOUNT_STATUS", "账号状态(ACCOUNT_STATUS)"); map.put("EXPIRY_DATE", "过期时间(EXPIRY_DATE)");
        map.put("PROFILE", "配置文件(PROFILE)"); map.put("DAYS_TO_EXPIRE", "距过期天数(DAYS_TO_EXPIRE)");
        map.put("MINUTES_DIFFERENCE", "主备同步时延分钟(MINUTES_DIFFERENCE)"); map.put("DB_UNIQUE_NAME", "数据库唯一名(DB_UNIQUE_NAME)");
        map.put("DATABASE_ROLE", "数据库角色(DATABASE_ROLE)"); map.put("OPEN_MODE", "打开模式(OPEN_MODE)");
        map.put("NODE_IP", "节点IP(NODE_IP)"); map.put("LOG_TYPE", "日志类型(LOG_TYPE)"); map.put("ERROR_HINT", "告警内容(ERROR_HINT)");
        map.put("SPACE_LIMIT", "空间上限(SPACE_LIMIT)"); map.put("SPACE_USED", "已用空间(SPACE_USED)");
        map.put("CPU_UTIL_PCT", "CPU使用率%(CPU_UTIL_PCT)"); map.put("OS_LOAD", "系统负载(OS_LOAD)");
        map.put("TOTAL_MEM_GB", "总内存GB(TOTAL_MEM_GB)"); map.put("MEM_USED_PCT", "内存使用率%(MEM_USED_PCT)");
        map.put("AVG_RECEIVE_TIME_MS", "平均接收时延毫秒(AVG_RECEIVE_TIME_MS)"); map.put("TASK_NAME", "任务名(TASK_NAME)");
        map.put("REPORT_SEGMENT_NO", "报文分段序号(REPORT_SEGMENT_NO)"); map.put("ADDM_REPORT_SEGMENT", "ADDM报文分段内容(ADDM_REPORT_SEGMENT)");
        map.put("BUFFER_CACHE_HIT_PCT", "Buffer Cache命中率%(BUFFER_CACHE_HIT_PCT)");
        map.put("LIBRARY_NAMESPACE", "Library Cache命名空间(LIBRARY_NAMESPACE)");
        map.put("LIBRARY_CACHE_HIT_PCT", "Library Cache命中率%(LIBRARY_CACHE_HIT_PCT)");
        map.put("LIBRARY_PINS", "Library解析请求数(LIBRARY_PINS)"); map.put("LIBRARY_PINHITS", "Library解析命中数(LIBRARY_PINHITS)");
        map.put("LIBRARY_RELOADS", "Library重载次数(LIBRARY_RELOADS)"); map.put("LIBRARY_INVALIDATIONS", "Library失效次数(LIBRARY_INVALIDATIONS)");
        map.put("MAX_PROCESSES", "进程上限(MAX_PROCESSES)"); map.put("CURRENT_SESSIONS", "当前会话数(CURRENT_SESSIONS)");
        map.put("ACTIVE_SESSIONS", "活跃会话数(ACTIVE_SESSIONS)"); map.put("SESSION_USAGE_RATIO_PCT", "会话占用率%(SESSION_USAGE_RATIO_PCT)");
        map.put("UNEXPIRED_MB", "未过期Undo空间MB(UNEXPIRED_MB)"); map.put("ACTIVE_MB", "活跃Undo空间MB(ACTIVE_MB)");
        map.put("EXPIRED_MB", "已过期Undo空间MB(EXPIRED_MB)"); map.put("UNDO_TOTAL_MB", "Undo总空间MB(UNDO_TOTAL_MB)");
        map.put("UNDO_EFFECTIVE_USED_PCT", "Undo有效占用率%(UNDO_EFFECTIVE_USED_PCT)"); map.put("INPUT_TYPE", "备份类型(INPUT_TYPE)");
        map.put("BACKUP_STATUS", "备份状态(BACKUP_STATUS)"); map.put("START_TIME", "开始时间(START_TIME)");
        map.put("END_TIME", "结束时间(END_TIME)"); map.put("ELAPSED_MIN", "耗时分钟(ELAPSED_MIN)");
        map.put("TEMP_TOTAL_MB", "临时表空间总容量MB(TEMP_TOTAL_MB)"); map.put("TEMP_ALLOCATED_MB", "临时表空间已分配MB(TEMP_ALLOCATED_MB)");
        map.put("TEMP_FREE_MB", "临时表空间空闲MB(TEMP_FREE_MB)"); map.put("TEMP_USED_PCT", "临时表空间使用率%(TEMP_USED_PCT)");
        map.put("NUM_ROWS", "估算行数(NUM_ROWS)"); map.put("LAST_ANALYZED", "最近统计时间(LAST_ANALYZED)");
        map.put("DAYS_SINCE_ANALYZE", "距今未统计天数(DAYS_SINCE_ANALYZE)"); map.put("FILE_NAME", "数据文件名(FILE_NAME)");
        map.put("PHYRDS", "物理读次数(PHYRDS)"); map.put("PHYWRTS", "物理写次数(PHYWRTS)");
        map.put("AVG_READ_MS", "平均读耗时毫秒(AVG_READ_MS)"); map.put("AVG_WRITE_MS", "平均写耗时毫秒(AVG_WRITE_MS)");
        map.put("SEQUENCE_OWNER", "序列所属用户(SEQUENCE_OWNER)"); map.put("SEQUENCE_NAME", "序列名称(SEQUENCE_NAME)");
        map.put("LAST_NUMBER", "当前序列值(LAST_NUMBER)"); map.put("MAX_VALUE", "序列最大值(MAX_VALUE)");
        map.put("INCREMENT_BY", "步长(INCREMENT_BY)"); map.put("REMAINING_VALUES", "剩余可用值(REMAINING_VALUES)");
        map.put("ORIGINAL_NAME", "原始对象名(ORIGINAL_NAME)"); map.put("TS_NAME", "表空间名(TS_NAME)");
        map.put("SIZE_MB", "空间大小MB(SIZE_MB)"); map.put("DROPTIME", "删除时间(DROPTIME)");
        map.put("USERHOST", "客户端主机(USERHOST)"); map.put("RETURN_CODE", "返回码(RETURN_CODE)"); map.put("EVENT_TIME", "发生时间(EVENT_TIME)");
        map.put("ALLOCATED_MB", "已分配空间MB(ALLOCATED_MB)"); map.put("ESTIMATED_DATA_MB", "估算数据量MB(ESTIMATED_DATA_MB)");
        map.put("BLOATED_MB", "疑似膨胀空间MB(BLOATED_MB)"); map.put("BLOAT_RATIO_PCT", "膨胀占比%(BLOAT_RATIO_PCT)");
        return map;
    }

    private static final class Styles {
        private final CellStyle header;
        private final CellStyle title;
        private final CellStyle wrap;
        private final CellStyle link;
        private final CellStyle normal;
        private final CellStyle warning;
        private final CellStyle critical;
        private final CellStyle skipped;

        private Styles(Workbook workbook) {
            header = workbook.createCellStyle();
            header.setFillForegroundColor(IndexedColors.ROYAL_BLUE.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setAlignment(HorizontalAlignment.CENTER);
            header.setVerticalAlignment(VerticalAlignment.CENTER);
            Font headerFont = workbook.createFont(); headerFont.setBold(true); headerFont.setColor(IndexedColors.WHITE.getIndex());
            header.setFont(headerFont);
            title = workbook.createCellStyle(); title.cloneStyleFrom(header);
            Font titleFont = workbook.createFont(); titleFont.setBold(true); titleFont.setFontHeightInPoints((short) 16); titleFont.setColor(IndexedColors.WHITE.getIndex());
            title.setFont(titleFont);
            wrap = workbook.createCellStyle(); wrap.setWrapText(true); wrap.setVerticalAlignment(VerticalAlignment.TOP);
            link = workbook.createCellStyle();
            Font linkFont = workbook.createFont(); linkFont.setUnderline(Font.U_SINGLE); linkFont.setColor(IndexedColors.BLUE.getIndex());
            link.setFont(linkFont);
            normal = statusStyle(workbook, IndexedColors.LIGHT_GREEN);
            warning = statusStyle(workbook, IndexedColors.LIGHT_ORANGE);
            critical = statusStyle(workbook, IndexedColors.ROSE);
            skipped = statusStyle(workbook, IndexedColors.GREY_25_PERCENT);
        }

        private CellStyle status(String status) {
            if ("NORMAL".equals(status)) return normal;
            if ("WARNING".equals(status)) return warning;
            if ("CRITICAL".equals(status)) return critical;
            return skipped;
        }

        private static CellStyle statusStyle(Workbook workbook, IndexedColors color) {
            CellStyle style = workbook.createCellStyle();
            style.setFillForegroundColor(color.getIndex()); style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            style.setAlignment(HorizontalAlignment.CENTER);
            Font font = workbook.createFont(); font.setBold(true); style.setFont(font);
            return style;
        }
    }
    private static String normalizeKey(String key) {
        return key == null ? "" : key.replaceAll("[^A-Za-z0-9]", "_").toUpperCase(Locale.ROOT);
    }

    private static String normalizeStatus(Object value) {
        String status = text(value).toUpperCase(Locale.ROOT);
        if ("NORMAL".equals(status) || "WARNING".equals(status) || "CRITICAL".equals(status)) return status;
        return "ERROR".equals(status) ? "ERROR" : "SKIPPED";
    }

    private static String displayStatus(Object value) {
        String status = normalizeStatus(value);
        if ("NORMAL".equals(status)) return "Normal";
        if ("WARNING".equals(status)) return "Warning";
        if ("CRITICAL".equals(status)) return "Critical";
        return "Skip";
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static double number(Object value) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        try { return value == null ? 0D : Double.parseDouble(String.valueOf(value)); }
        catch (Exception ignored) { return 0D; }
    }

}

