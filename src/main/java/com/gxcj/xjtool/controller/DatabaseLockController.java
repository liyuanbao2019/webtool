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
            @RequestParam("database") String database) {
        try {
            List<Map<String, Object>> rows = oracleService.getMysqlWsrepProcesses(datasourceIndex, database);
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
                request.getIds(),
                username);
    }

    public static class MysqlProcessKillRequest {
        private int datasourceIndex;
        private String database;
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

        public List<Long> getIds() {
            return ids;
        }

        public void setIds(List<Long> ids) {
            this.ids = ids;
        }
    }
}
