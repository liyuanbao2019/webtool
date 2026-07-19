package com.gxcj.xjtool.service;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Shared Oracle recycle-bin query and purge semantics for health checks and common tools. */
@Service
public class OracleRecycleBinService {

    private static final String DBA_FROM =
            " FROM dba_recyclebin r LEFT JOIN dba_tablespaces t ON r.ts_name=t.tablespace_name ";
    private static final String USER_FROM =
            " FROM recyclebin r LEFT JOIN user_tablespaces t ON r.ts_name=t.tablespace_name ";

    public Snapshot query(JdbcTemplate jdbc, int maxRows) {
        int limit = Math.max(1, Math.min(maxRows, 5000));
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT * FROM (SELECT r.owner,r.original_name,r.object_name,r.type object_type,r.ts_name," +
                            "ROUND(r.space*NVL(t.block_size,8192)/1024/1024,2) size_mb,r.droptime" + DBA_FROM +
                            "ORDER BY r.space DESC) WHERE ROWNUM<=?", limit);
            Map<String, Object> summary = first(jdbc.queryForList(
                    "SELECT COUNT(*) object_count,ROUND(NVL(SUM(r.space*NVL(t.block_size,8192)),0)/1024/1024,2) size_mb" +
                            DBA_FROM));
            return new Snapshot(rows, intValue(summary.get("OBJECT_COUNT")),
                    doubleValue(summary.get("SIZE_MB")), Scope.DATABASE);
        } catch (DataAccessException noDbaAccess) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT * FROM (SELECT USER owner,r.original_name,r.object_name,r.type object_type,r.ts_name," +
                            "ROUND(r.space*NVL(t.block_size,8192)/1024/1024,2) size_mb,r.droptime" + USER_FROM +
                            "ORDER BY r.space DESC) WHERE ROWNUM<=?", limit);
            Map<String, Object> summary = first(jdbc.queryForList(
                    "SELECT COUNT(*) object_count,ROUND(NVL(SUM(r.space*NVL(t.block_size,8192)),0)/1024/1024,2) size_mb" +
                            USER_FROM));
            return new Snapshot(rows, intValue(summary.get("OBJECT_COUNT")),
                    doubleValue(summary.get("SIZE_MB")), Scope.CURRENT_USER);
        }
    }

    public int purgeDetectedScope(JdbcTemplate jdbc) {
        Snapshot snapshot = query(jdbc, 1);
        if (snapshot.count == 0) return 0;
        try {
            jdbc.execute(snapshot.scope == Scope.DATABASE ? "PURGE DBA_RECYCLEBIN" : "PURGE RECYCLEBIN");
            return snapshot.count;
        } catch (DataAccessException e) {
            if (snapshot.scope == Scope.DATABASE) {
                throw new IllegalStateException(
                        "已检测到全库 DBA_RECYCLEBIN 对象，但当前账号无权执行 PURGE DBA_RECYCLEBIN；"
                                + "请使用具有 SYSDBA 权限的维护账号执行全库清理。", e);
            }
            throw new IllegalStateException("当前用户回收站清理失败: " + rootMessage(e), e);
        }
    }

    private static Map<String, Object> first(List<Map<String, Object>> rows) {
        return rows == null || rows.isEmpty() ? Collections.emptyMap() : rows.get(0);
    }

    private static int intValue(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private static double doubleValue(Object value) {
        return value instanceof Number ? ((Number) value).doubleValue() : 0D;
    }

    private static String rootMessage(Throwable value) {
        Throwable current = value;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public enum Scope { DATABASE, CURRENT_USER }

    public static final class Snapshot {
        private final List<Map<String, Object>> rows;
        private final int count;
        private final double sizeMb;
        private final Scope scope;

        Snapshot(List<Map<String, Object>> rows, int count, double sizeMb, Scope scope) {
            this.rows = rows;
            this.count = count;
            this.sizeMb = sizeMb;
            this.scope = scope;
        }

        public List<Map<String, Object>> getRows() { return rows; }
        public int getCount() { return count; }
        public double getSizeMb() { return sizeMb; }
        public Scope getScope() { return scope; }
        public boolean isDatabaseScope() { return scope == Scope.DATABASE; }
    }
}
