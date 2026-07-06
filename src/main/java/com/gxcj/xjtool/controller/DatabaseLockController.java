package com.gxcj.xjtool.controller;

import com.gxcj.xjtool.service.OracleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.*;

/**
 * 数据库锁管理控制器
 * 提供锁表查询与会话解锁 API
 */
@Slf4j
@RestController
@RequestMapping("/api/database/lock")
@RequiredArgsConstructor
public class DatabaseLockController {

    private final OracleService oracleService;

    /**
     * 查询当前锁表信息
     *
     * @param datasourceIndex 数据源索引
     * @return 锁表记录列表
     */
    @GetMapping("/locked-objects")
    public Map<String, Object> getLockedObjects(@RequestParam("datasourceIndex") int datasourceIndex) {
        try {
            List<Map<String, Object>> rows = oracleService.getLockedObjects(datasourceIndex);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("data", rows);
            result.put("count", rows.size());
            return result;
        } catch (Exception e) {
            log.error("查询锁表失败 datasourceIndex={}", datasourceIndex, e);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", false);
            result.put("message", "查询锁表信息失败: " + e.getMessage());
            result.put("data", Collections.emptyList());
            result.put("count", 0);
            return result;
        }
    }

    /**
     * 解锁指定会话
     *
     * @param sid             会话 SID
     * @param serial          会话 Serial#
     * @param datasourceIndex 数据源索引
     * @return 操作结果
     */
    @PostMapping("/unlock-session")
    public Map<String, Object> unlockSession(
            @RequestParam("sid") String sid,
            @RequestParam("serial") String serial,
            @RequestParam("datasourceIndex") int datasourceIndex) {
        log.info("解锁请求: SID={}, Serial={}, datasource={}", sid, serial, datasourceIndex);
        return oracleService.unlockSession(sid, serial, datasourceIndex);
    }

    /**
     * Query MySQL wsrep process list for process killing.
     */
    @GetMapping("/mysql-processes")
    public Map<String, Object> getMysqlProcesses(
            @RequestParam("datasourceIndex") int datasourceIndex,
            @RequestParam("database") String database,
            @RequestParam(value = "command", required = false) String command,
            @RequestParam(value = "eventType", required = false, defaultValue = "cluster_wait") String eventType) {
        try {
            List<Map<String, Object>> rows = oracleService.getMysqlWsrepProcesses(datasourceIndex, database, command, eventType);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("data", rows);
            result.put("count", rows.size());
            return result;
        } catch (Exception e) {
            log.error("Query MySQL process list failed datasourceIndex={}, database={}", datasourceIndex, database, e);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", false);
            result.put("message", "Query MySQL process list failed: " + e.getMessage());
            result.put("data", Collections.emptyList());
            result.put("count", 0);
            return result;
        }
    }

    @GetMapping("/mysql-slow-sql")
    public Map<String, Object> getMysqlSlowSql(
            @RequestParam("datasourceIndex") int datasourceIndex,
            @RequestParam("database") String database,
            @RequestParam(value = "minSeconds", required = false, defaultValue = "5") int minSeconds) {
        try {
            List<Map<String, Object>> rows = oracleService.getMysqlCurrentSlowSql(datasourceIndex, database, minSeconds);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("data", rows);
            result.put("count", rows.size());
            return result;
        } catch (Exception e) {
            log.error("Query MySQL current slow SQL failed datasourceIndex={}, database={}", datasourceIndex, database, e);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", false);
            result.put("message", "Query MySQL current slow SQL failed: " + e.getMessage());
            result.put("data", Collections.emptyList());
            result.put("count", 0);
            return result;
        }
    }

    @GetMapping("/mysql-transaction-diagnostics")
    public Map<String, Object> getMysqlTransactionDiagnostics(
            @RequestParam("datasourceIndex") int datasourceIndex,
            @RequestParam("database") String database,
            @RequestParam(value = "minSeconds", required = false, defaultValue = "30") int minSeconds) {
        try {
            Map<String, Object> diagnostics = oracleService.getMysqlTransactionDiagnostics(datasourceIndex, database, minSeconds);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.putAll(diagnostics);
            return result;
        } catch (Exception e) {
            log.error("Query MySQL transaction diagnostics failed datasourceIndex={}, database={}", datasourceIndex, database, e);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", false);
            result.put("message", "Query MySQL transaction diagnostics failed: " + e.getMessage());
            result.put("longTransactions", Collections.emptyList());
            result.put("lockWaits", Collections.emptyList());
            return result;
        }
    }

    /**
     * Batch kill selected MySQL processes.
     */
    @PostMapping("/mysql-processes/kill")
    public Map<String, Object> killMysqlProcesses(@RequestBody MysqlProcessKillRequest request, HttpSession session) {
        String username = (String) session.getAttribute("LOGIN_USER");
        if (username == null || username.trim().isEmpty()) {
            username = "unknown";
        }
        log.info("MySQL kill process request datasource={}, database={}, ids={}, user={}",
                request.getDatasourceIndex(), request.getDatabase(), request.getIds(), username);
        return oracleService.killMysqlProcesses(
                request.getDatasourceIndex(),
                request.getDatabase(),
                request.getCommand(),
                request.getEventType(),
                request.getIds(),
                username);
    }

    @PostMapping("/mysql-online-ddl/plan")
    public Map<String, Object> buildMysqlOnlineDdlPlan(@RequestBody MysqlOnlineDdlPlanRequest request, HttpSession session) {
        String username = (String) session.getAttribute("LOGIN_USER");
        if (username == null || username.trim().isEmpty()) {
            username = "unknown";
        }
        log.info("MySQL online DDL plan request datasource={}, database={}, user={}",
                request.getDatasourceIndex(), request.getDatabase(), username);
        try {
            return oracleService.buildMysqlOnlineDdlPlan(
                    request.getDatasourceIndex(),
                    request.getDatabase(),
                    request.getDdl(),
                    username);
        } catch (Exception e) {
            log.error("Build MySQL online DDL plan failed datasource={}, database={}",
                    request.getDatasourceIndex(), request.getDatabase(), e);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", false);
            result.put("message", "Build MySQL online DDL plan failed: " + e.getMessage());
            return result;
        }
    }

    public static class MysqlProcessKillRequest {
        private int datasourceIndex;
        private String database;
        private String command;
        private String eventType;
        private List<Long> ids;

        public int getDatasourceIndex() {
            return datasourceIndex;
        }

        public void setDatasourceIndex(int datasourceIndex) {
            this.datasourceIndex = datasourceIndex;
        }

        public String getDatabase() {
            return database;
        }

        public void setDatabase(String database) {
            this.database = database;
        }

        public String getCommand() {
            return command;
        }

        public void setCommand(String command) {
            this.command = command;
        }

        public String getEventType() {
            return eventType;
        }

        public void setEventType(String eventType) {
            this.eventType = eventType;
        }

        public List<Long> getIds() {
            return ids;
        }

        public void setIds(List<Long> ids) {
            this.ids = ids;
        }
    }

    public static class MysqlOnlineDdlPlanRequest {
        private int datasourceIndex;
        private String database;
        private String ddl;

        public int getDatasourceIndex() {
            return datasourceIndex;
        }

        public void setDatasourceIndex(int datasourceIndex) {
            this.datasourceIndex = datasourceIndex;
        }

        public String getDatabase() {
            return database;
        }

        public void setDatabase(String database) {
            this.database = database;
        }

        public String getDdl() {
            return ddl;
        }

        public void setDdl(String ddl) {
            this.ddl = ddl;
        }
    }
}
