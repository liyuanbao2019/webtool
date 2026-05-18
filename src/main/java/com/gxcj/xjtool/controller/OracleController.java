package com.gxcj.xjtool.controller;

import com.gxcj.xjtool.dto.ExecuteSqlRequest;
import com.gxcj.xjtool.dto.OracleDataSourceDto;
import com.gxcj.xjtool.dto.SqlResultResponse;
import com.gxcj.xjtool.service.OracleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

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
}
