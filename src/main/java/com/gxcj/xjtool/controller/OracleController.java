package com.gxcj.xjtool.controller;

import com.gxcj.xjtool.dto.ExecuteSqlRequest;
import com.gxcj.xjtool.dto.OracleDataSourceDto;
import com.gxcj.xjtool.dto.SqlResultResponse;
import com.gxcj.xjtool.service.OracleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * Oracle 数据库工具 API 控制器
 */
@RestController
@RequestMapping("/api/oracle")
@RequiredArgsConstructor
public class OracleController {

    private final OracleService oracleService;

    /**
     * 获取配置的数据源列表
     */
    @GetMapping("/datasources")
    public List<OracleDataSourceDto> getDataSources() {
        return oracleService.getDataSources();
    }

    /**
     * 测试数据库连接
     */
    @PostMapping("/test-connection")
    public SqlResultResponse testConnection(@RequestParam("index") int datasourceIndex) {
        return oracleService.testConnection(datasourceIndex);
    }

    /**
     * 获取数据源类型
     */
    @GetMapping("/datasource-type")
    public Map<String, String> getDatasourceType(@RequestParam("index") int datasourceIndex) {
        String type = oracleService.getDatasourceType(datasourceIndex);
        Map<String, String> result = new HashMap<>();
        result.put("type", type);
        return result;
    }

    /**
     * 执行 SQL 语句
     */
    @PostMapping("/execute")
    public SqlResultResponse executeSql(@RequestBody ExecuteSqlRequest request,
            javax.servlet.http.HttpSession session) {
        // 获取当前登录用户（使用正确的Session key）
        String username = (String) session.getAttribute("LOGIN_USER");
        request.setUsername(username != null ? username : "unknown");
        request.setSessionId(session.getId()); // 设置会话ID用于安全验证
        return oracleService.executeSql(request);
    }

    @PostMapping("/export/{format}")
    public void exportSql(@PathVariable String format,
            @RequestBody ExecuteSqlRequest request,
            javax.servlet.http.HttpSession session,
            javax.servlet.http.HttpServletResponse servletResponse) throws IOException {
        String username = (String) session.getAttribute("LOGIN_USER");
        request.setUsername(username != null ? username : "unknown");
        request.setSessionId(session.getId());
        request.setPage(1);
        request.setPageSize(0);
        request.setExportAll(true);

        SqlResultResponse executeResult = oracleService.executeSql(request);
        SqlResultResponse exportResult = resolveExportResult(executeResult);
        if (exportResult == null || !exportResult.isSuccess() || exportResult.getColumns() == null || exportResult.getRows() == null) {
            String message = executeResult != null && executeResult.getErrorMessage() != null
                    ? executeResult.getErrorMessage()
                    : "Export query returned no data";
            servletResponse.sendError(500, message);
            return;
        }

        String normalizedFormat = format == null ? "csv" : format.toLowerCase();
        String extension = "sql".equals(normalizedFormat) ? "sql" : ("excel".equals(normalizedFormat) ? "xls" : "csv");
        String fileName = "query_result_" + System.currentTimeMillis() + "." + extension;
        servletResponse.setCharacterEncoding(StandardCharsets.UTF_8.name());
        servletResponse.setHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"; filename*=UTF-8''"
                + URLEncoder.encode(fileName, StandardCharsets.UTF_8.name()));

        if ("excel".equals(normalizedFormat)) {
            servletResponse.setContentType("application/vnd.ms-excel;charset=UTF-8");
            writeExcelHtml(servletResponse.getWriter(), exportResult);
        } else if ("sql".equals(normalizedFormat)) {
            servletResponse.setContentType("text/plain;charset=UTF-8");
            writeInsertSql(servletResponse.getWriter(), exportResult, inferExportTableName(request.getSql()));
        } else {
            servletResponse.setContentType("text/csv;charset=UTF-8");
            writeCsv(servletResponse.getWriter(), exportResult);
        }
    }

    /**
     * 获取 SQL 执行计划
     */
    @PostMapping("/explain")
    public SqlResultResponse explainSql(@RequestBody ExecuteSqlRequest request,
            javax.servlet.http.HttpSession session) {
        // 获取当前登录用户
        String username = (String) session.getAttribute("LOGIN_USER");
        request.setUsername(username != null ? username : "unknown");
        return oracleService.explainSql(request);
    }

    /**
     * 获取数据库对象列表
     * 
     * @param type            对象类型：tables, views, indexes, procedures, sequences
     * @param datasourceIndex 数据源索引
     * @return 对象名称列表
     */
    @GetMapping("/objects/{type}")
    public List<Map<String, String>> getDatabaseObjects(
            @PathVariable String type,
            @RequestParam("datasourceIndex") int datasourceIndex) {
        return oracleService.getDatabaseObjects(type, datasourceIndex);
    }

    /**
     * 获取数据库对象的DDL语句
     * 
     * @param type            对象类型
     * @param name            对象名称
     * @param datasourceIndex 数据源索引
     * @return DDL语句
     */
    @GetMapping("/ddl/{type}/{name}")
    public Map<String, String> getObjectDDL(
            @PathVariable String type,
            @PathVariable String name,
            @RequestParam("datasourceIndex") int datasourceIndex) {
        String ddl = oracleService.getObjectDDL(type, name, datasourceIndex);
        Map<String, String> result = new HashMap<>();
        result.put("ddl", ddl);
        return result;
    }

    /**
     * 清除对象列表缓存（缓存已禁用，此接口保留但无实际作用）
     * 
     * @param datasourceIndex 数据源索引
     */
    @PostMapping("/cache/clear")
    public Map<String, String> clearCache(@RequestParam("datasourceIndex") int datasourceIndex) {
        oracleService.clearObjectsCache(datasourceIndex);
        Map<String, String> result = new HashMap<>();
        result.put("message", "缓存已禁用，无需清除");
        return result;
    }

    /**
     * 获取表结构信息
     * 
     * @param tableName       表名
     * @param datasourceIndex 数据源索引
     * @return 表结构列表
     */
    @GetMapping("/table-structure/{tableName}")
    public List<Map<String, Object>> getTableStructure(
            @PathVariable String tableName,
            @RequestParam("datasourceIndex") int datasourceIndex) {
        return oracleService.getTableStructure(tableName, datasourceIndex);
    }

    /**
     * 获取表索引信息
     * 
     * @param tableName       表名
     * @param datasourceIndex 数据源索引
     * @return 索引列表
     */
    @GetMapping("/table-indexes/{tableName}")
    public List<Map<String, Object>> getTableIndexes(
            @PathVariable String tableName,
            @RequestParam("datasourceIndex") int datasourceIndex) {
        return oracleService.getTableIndexes(tableName, datasourceIndex);
    }

    /**
     * 生成建表SQL语句
     * 
     * @param tableName       表名
     * @param datasourceIndex 数据源索引
     * @return 包含SQL语句的Map
     */
    @GetMapping("/create-table-sql/{tableName}")
    public Map<String, String> getCreateTableSQL(
            @PathVariable String tableName,
            @RequestParam("datasourceIndex") int datasourceIndex) {
        String sql = oracleService.getCreateTableSQL(tableName, datasourceIndex);
        Map<String, String> result = new HashMap<>();
        result.put("sql", sql);
        return result;
    }

    /**
     * 获取表分区信息
     * 
     * @param tableName       表名
     * @param datasourceIndex 数据源索引
     * @return 分区信息列表
     */
    @GetMapping("/table-partitions/{tableName}")
    public List<Map<String, Object>> getTablePartitions(
            @PathVariable String tableName,
            @RequestParam("datasourceIndex") int datasourceIndex) {
        return oracleService.getTablePartitions(tableName, datasourceIndex);
    }

    /**
     * 获取表触发器信息
     * 
     * @param tableName       表名
     * @param datasourceIndex 数据源索引
     * @return 触发器信息列表
     */
    @GetMapping("/table-triggers/{tableName}")
    public List<Map<String, Object>> getTableTriggers(
            @PathVariable String tableName,
            @RequestParam("datasourceIndex") int datasourceIndex) {
        return oracleService.getTableTriggers(tableName, datasourceIndex);
    }

    private SqlResultResponse resolveExportResult(SqlResultResponse result) {
        if (result == null) {
            return null;
        }
        if (result.getRows() != null) {
            return result;
        }
        if (result.getMultiResults() == null) {
            return result;
        }
        for (SqlResultResponse item : result.getMultiResults()) {
            if (item != null && item.getRows() != null) {
                return item;
            }
        }
        return result;
    }

    private void writeCsv(PrintWriter writer, SqlResultResponse result) {
        writer.write('\uFEFF');
        writer.println(joinCsvLine(result.getColumns(), null));
        for (Map<String, Object> row : result.getRows()) {
            writer.println(joinCsvLine(result.getColumns(), row));
        }
    }

    private String joinCsvLine(List<String> columns, Map<String, Object> row) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            Object value = row == null ? columns.get(i) : row.get(columns.get(i));
            sb.append('"').append(csvEscape(value)).append('"');
        }
        return sb.toString();
    }

    private String csvEscape(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value).replace("\"", "\"\"");
    }

    private void writeExcelHtml(PrintWriter writer, SqlResultResponse result) {
        writer.write('\uFEFF');
        writer.println("<html><head><meta charset=\"UTF-8\"></head><body><table border=\"1\"><thead><tr>");
        for (String column : result.getColumns()) {
            writer.print("<th>");
            writer.print(htmlEscape(column));
            writer.print("</th>");
        }
        writer.println("</tr></thead><tbody>");
        for (Map<String, Object> row : result.getRows()) {
            writer.println("<tr>");
            for (String column : result.getColumns()) {
                writer.print("<td>");
                Object value = row.get(column);
                writer.print(htmlEscape(value == null ? "" : String.valueOf(value)));
                writer.print("</td>");
            }
            writer.println("</tr>");
        }
        writer.println("</tbody></table></body></html>");
    }

    private String htmlEscape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private void writeInsertSql(PrintWriter writer, SqlResultResponse result, String tableName) {
        String columns = String.join(", ", result.getColumns());
        for (Map<String, Object> row : result.getRows()) {
            writer.print("INSERT INTO ");
            writer.print(tableName);
            writer.print(" (");
            writer.print(columns);
            writer.print(") VALUES (");
            for (int i = 0; i < result.getColumns().size(); i++) {
                if (i > 0) {
                    writer.print(", ");
                }
                writer.print(sqlLiteral(row.get(result.getColumns().get(i))));
            }
            writer.println(");");
        }
    }

    private String sqlLiteral(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return "'" + String.valueOf(value).replace("'", "''") + "'";
    }

    private String inferExportTableName(String sql) {
        if (sql == null) {
            return "export_table";
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?i)\\bfrom\\s+([`\"\\[]?[a-zA-Z0-9_.$]+[`\"\\]]?)")
                .matcher(sql);
        if (!matcher.find()) {
            return "export_table";
        }
        return matcher.group(1).replaceAll("^[`\"\\[]|[`\"\\]]$", "");
    }
}
