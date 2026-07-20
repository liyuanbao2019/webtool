package com.li.jc.webtool.service;

import com.li.jc.webtool.config.DatabaseConfig;
import com.li.jc.webtool.model.ServerInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Function;

/**
 * Oracle RAC health inspection migrated from xjwlcsMonitor.
 * The task list, SQL, thresholds and report layout intentionally follow the original 29-item report.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OracleHealthCheckService {

    private final DatabaseConfig databaseConfig;
    private final DatabaseService databaseService;
    private final OracleSshCommandService sshCommandService;
    private final OracleRecycleBinService recycleBinService;
    private final OracleHealthReportExporter reportExporter;

    private static final List<TaskMeta> TASKS = Collections.unmodifiableList(Arrays.asList(
            task("instance", "RAC 实例状态检查", "Cluster"),
            task("syncDelay", "集群主备同步时延检查", "Cluster"),
            task("osResource", "OS 基础负载指标探针", "OS"),
            task("memoryHit", "SGA/PGA 内存命中率诊断", "Performance"),
            task("sessionLoad", "活跃会话数与连接数监控", "Performance"),
            task("undoUsage", "Undo 表空间使用与回滚段监控", "Storage"),
            task("rmanBackup", "RMAN 备份状态检查", "Storage"),
            task("tempUsage", "临时表空间使用监控", "Storage"),
            task("staleStats", "表/索引统计信息过期检查", "Performance"),
            task("ioHotspot", "数据文件 I/O 热点分析", "Performance"),
            task("seqExhaust", "序列耗尽预警", "Database"),
            task("recyclebin", "回收站对象清理建议", "Storage"),
            task("failedLoginAudit", "失败登录尝试审计", "Security"),
            task("hwmBloat", "高水位线(HWM)膨胀表检查", "Database"),
            task("tablespace", "表空间使用率检查", "Storage"),
            task("asm", "ASM 磁盘组容量分析", "Storage"),
            task("waitEvent", "全局 Top 10 异常等待事件", "Performance"),
            task("redoSwitch", "Redo 日志切换频率", "Performance"),
            task("archive", "FRA 归档日志空间检测", "Storage"),
            task("topSql", "系统高耗时高频 SQL 榜单", "Performance"),
            task("ash", "当前阻塞会话诊断", "Performance"),
            task("longTx", "长事务感知 (>10分钟)", "Performance"),
            task("gc", "集群一致性(私网延迟估测)", "Cluster"),
            task("lock", "数据库全局死锁检测 (TM/TX)", "Performance"),
            task("invalidObjects", "业务失效对象 (INVALID) 拨测", "Database"),
            task("unusableIndexes", "索引失效情况 (UNUSABLE) 分析", "Database"),
            task("user", "Oracle 账号密码时效检测", "Security"),
            task("addm", "智能 ADDM 性能系统报告解析", "Performance"),
            task("serverLog", "底层操作系统及数据库警报日志探查", "Server")
    ));

    private static final List<String> SYSTEM_MESSAGE_IGNORED_MARKERS = Collections.unmodifiableList(Arrays.asList(
            "FAILED SU (to root",
            "sd_journal_get_cursor",
            "org.bluez",
            "dbus-daemon",
            "10.235.107.225",
            "download https://extensions.gnome.org/",
            "and filing a bug with the additional information"
    ));

    public Map<String, Object> run(int datasourceIndex) {
        DatabaseConfig.DataSourceConfig datasource = oracleDatasource(datasourceIndex);
        List<Map<String, Object>> checks = new ArrayList<>();
        List<Map<String, Object>> racNodes = Collections.emptyList();
        try (RacNodeCluster cluster = openRacNodes(datasource)) {
            racNodes = cluster.overview();
            JdbcTemplate jdbc = cluster.firstReachableJdbc();
            if (jdbc == null) throw new IllegalStateException("所有配置的 RAC 节点均无法连接");
            for (TaskMeta task : TASKS) {
                Map<String, Object> check;
                try {
                    check = executeTask(task.id, jdbc, datasourceIndex, cluster);
                } catch (Exception e) {
                    log.warn("Oracle health task failed datasource={} task={}: {}",
                            datasource.getName(), task.id, e.getMessage());
                    check = result(task.id, task.name, task.category, "SKIPPED",
                            "该项检查执行失败或权限不足: " + e.getMessage(), Collections.emptyList());
                }
                check.put("id", task.id);
                check.put("name", task.name);
                check.put("category", task.category);
                check.put("status", normalizeStatus(check.get("status")));
                if (!check.containsKey("detail")) check.put("detail", check.get("data"));
                checks.add(check);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Oracle 智能体检连接失败: " + e.getMessage(), e);
        }
        return reportEnvelope(datasource, checks, racNodes);
    }

    public DatabaseToolsService.ReportFile exportExcel(int datasourceIndex) {
        return reportExporter.buildExcel(run(datasourceIndex));
    }

    public DatabaseToolsService.ReportFile exportBundle(int datasourceIndex) {
        return reportExporter.buildBundle(run(datasourceIndex));
    }

    DatabaseToolsService.ReportFile buildSummaryPdf(Map<String, Object> report) {
        return reportExporter.buildSummaryPdf(report);
    }

    DatabaseToolsService.ReportFile buildExcel(Map<String, Object> report) {
        return reportExporter.buildExcel(report);
    }

    private Map<String, Object> executeTask(String taskId, JdbcTemplate jdbc, int datasourceIndex,
                                            RacNodeCluster cluster) {
        switch (taskId) {
            case "instance": return checkInstance(cluster);
            case "syncDelay": return checkSyncDelay(jdbc);
            case "osResource": return checkOsResource(jdbc);
            case "memoryHit": return checkAcrossRacNodes(cluster, this::checkMemoryHit, "内存命中率");
            case "sessionLoad": return checkSessionLoad(jdbc);
            case "undoUsage": return checkUndoUsage(jdbc);
            case "rmanBackup": return checkRmanBackup(jdbc);
            case "tempUsage": return checkTempUsage(jdbc);
            case "staleStats": return checkStaleStats(jdbc);
            case "ioHotspot": return checkAcrossRacNodes(cluster, this::checkIoHotspot, "数据文件 I/O");
            case "seqExhaust": return checkSeqExhaust(jdbc);
            case "recyclebin": return checkRecyclebin(jdbc);
            case "failedLoginAudit": return checkFailedLoginAudit(jdbc);
            case "hwmBloat": return checkHwmBloat(jdbc);
            case "tablespace": return checkTablespace(jdbc);
            case "asm": return checkAsm(jdbc);
            case "waitEvent": return checkWaitEvent(jdbc);
            case "redoSwitch": return checkRedoSwitch(jdbc);
            case "archive": return checkArchive(jdbc);
            case "topSql": return checkAcrossRacNodes(cluster, this::checkTopSql, "高耗时 SQL");
            case "ash": return checkAcrossRacNodes(cluster, this::checkAsh, "实时阻塞会话");
            case "longTx": return checkLongTx(jdbc);
            case "gc": return checkGc(jdbc);
            case "lock": return checkLock(datasourceIndex);
            case "invalidObjects": return checkInvalidObjects(jdbc);
            case "unusableIndexes": return checkUnusableIndexes(jdbc);
            case "user": return checkUser(jdbc);
            case "addm": return checkAddm(jdbc);
            case "serverLog": return checkServerLog(datasourceIndex);
            default: throw new IllegalArgumentException("未知体检任务: " + taskId);
        }
    }

    private Map<String, Object> checkInstance(RacNodeCluster cluster) {
        List<Map<String, Object>> rows = cluster.overview();
        long connected = rows.stream().filter(row -> "CONNECTED".equals(text(row.get("CONNECTION_STATUS")))).count();
        boolean allOpen = connected == rows.size() && rows.stream()
                .allMatch(row -> "OPEN".equalsIgnoreCase(text(row.get("INSTANCE_STATUS"))));
        long uniqueInstances = rows.stream()
                .filter(row -> "CONNECTED".equals(text(row.get("CONNECTION_STATUS"))))
                .map(row -> text(row.get("INSTANCE_NAME"))).filter(value -> !value.isEmpty()).distinct().count();
        if (connected < rows.size()) {
            return check("CRITICAL", "已逐个直连 RAC 节点，" + connected + "/" + rows.size()
                    + " 个节点连接成功；存在不可达或登录失败节点，请检查 slave 配置、监听和网络。", rows);
        }
        if (!allOpen) {
            return check("CRITICAL", "所有 RAC 节点均可连接，但存在未处于 OPEN 状态的实例。", rows);
        }
        if (uniqueInstances < connected) {
            return check("WARNING", "已连接全部 " + connected
                    + " 个配置节点，但多个节点地址被重定向到同一实例，请核对监听或 service 的负载均衡配置。", rows);
        }
        return check("NORMAL", "已逐个直连并验证全部 " + connected + " 个 RAC 节点，实例均处于 OPEN 状态。", rows);
    }

    private Map<String, Object> checkSyncDelay(JdbcTemplate jdbc) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT d.DB_UNIQUE_NAME, d.DATABASE_ROLE, d.OPEN_MODE, s.NAME, s.VALUE, s.TIME_COMPUTED " +
                        "FROM V$DATABASE d LEFT JOIN V$DATAGUARD_STATS s ON s.NAME IN ('transport lag','apply lag')");
        long maxLagSeconds = 0;
        for (Map<String, Object> row : rows) maxLagSeconds = Math.max(maxLagSeconds, parseLagSeconds(row.get("VALUE")));
        long minutes = maxLagSeconds / 60;
        for (Map<String, Object> row : rows) row.put("MINUTES_DIFFERENCE", minutes);
        String role = rows.isEmpty() ? "" : text(rows.get(0).get("DATABASE_ROLE")).toUpperCase(Locale.ROOT);
        if (!role.contains("STANDBY")) {
            return check("NORMAL", "当前连接为主库；未发现可由本连接读取的 Data Guard 应用时延异常。", rows);
        }
        if (minutes >= 15) return check("CRITICAL", "主备同步时延已达到15分钟，请立即检查传输链路与应用进程。", rows);
        if (minutes >= 5) return check("WARNING", "主备同步时延超过5分钟，建议尽快排查并持续观察。", rows);
        return check("NORMAL", "主备同步时延处于正常范围。", rows);
    }

    private Map<String, Object> checkOsResource(JdbcTemplate jdbc) {
        String sql = "SELECT c.inst_id AS INST_ID, c.cpu_util_pct AS CPU_UTIL_PCT, c.os_load AS OS_LOAD, " +
                "m.total_mem_gb AS TOTAL_MEM_GB, m.mem_used_pct AS MEM_USED_PCT FROM (" +
                "SELECT inst_id, MAX(CASE WHEN metric_name='Host CPU Utilization (%)' THEN ROUND(value,2) END) cpu_util_pct, " +
                "MAX(CASE WHEN metric_name='Current OS Load' THEN ROUND(value,2) END) os_load FROM gv$sysmetric " +
                "WHERE metric_name IN ('Host CPU Utilization (%)','Current OS Load') AND intsize_csec>0 AND intsize_csec<=6000 GROUP BY inst_id) c JOIN (" +
                "SELECT inst_id, ROUND(MAX(CASE WHEN stat_name='PHYSICAL_MEMORY_BYTES' THEN value END)/POWER(1024,3),2) total_mem_gb, " +
                "ROUND((MAX(CASE WHEN stat_name='PHYSICAL_MEMORY_BYTES' THEN value END)-NVL(MAX(CASE WHEN stat_name='FREE_MEMORY_BYTES' THEN value END),0)) / " +
                "DECODE(MAX(CASE WHEN stat_name='PHYSICAL_MEMORY_BYTES' THEN value END),0,1,MAX(CASE WHEN stat_name='PHYSICAL_MEMORY_BYTES' THEN value END))*100,2) mem_used_pct " +
                "FROM gv$osstat GROUP BY inst_id) m ON c.inst_id=m.inst_id ORDER BY c.inst_id";
        List<Map<String, Object>> rows = jdbc.queryForList(sql);
        boolean highCpu = rows.stream().anyMatch(r -> number(r.get("CPU_UTIL_PCT")) > 80D);
        boolean highMem = rows.stream().anyMatch(r -> number(r.get("MEM_USED_PCT")) > 95D);
        if (highCpu || highMem) return check("WARNING", "主机存在系统资源瓶颈："
                + (highCpu ? "CPU负载超过80%；" : "") + (highMem ? "物理内存使用率超过95%。" : ""), rows);
        return check("NORMAL", "当前集群运行主机 CPU 计算与内存容量均处于健康范畴，无 Swap 置换风险。", rows);
    }

    private Map<String, Object> checkMemoryHit(JdbcTemplate jdbc) {
        Double bufferHit = jdbc.queryForObject(
                "SELECT ROUND((1-(phy.value/(cur.value+con.value+phy.value)))*100,2) FROM v$sysstat phy,v$sysstat cur,v$sysstat con " +
                        "WHERE phy.name='physical reads' AND cur.name='db block gets' AND con.name='consistent gets'", Double.class);
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> buffer = new LinkedHashMap<>();
        buffer.put("LIBRARY_NAMESPACE", "BUFFER_CACHE");
        buffer.put("BUFFER_CACHE_HIT_PCT", bufferHit == null ? 0D : bufferHit);
        rows.add(buffer);
        List<Map<String, Object>> library = jdbc.queryForList(
                "SELECT namespace AS LIBRARY_NAMESPACE, ROUND(gethitratio*100,2) AS LIBRARY_CACHE_HIT_PCT, " +
                        "pins AS LIBRARY_PINS,pinhits AS LIBRARY_PINHITS,reloads AS LIBRARY_RELOADS,invalidations AS LIBRARY_INVALIDATIONS " +
                        "FROM v$librarycache WHERE namespace IN ('SQL AREA','TABLE/PROCEDURE','BODY') ORDER BY LIBRARY_CACHE_HIT_PCT");
        rows.addAll(library);
        double hit = bufferHit == null ? 0D : bufferHit;
        boolean lowLibrary = library.stream().anyMatch(r -> number(r.get("LIBRARY_CACHE_HIT_PCT")) < 90D);
        if (hit < 90D) return check("CRITICAL", "Buffer Cache 命中率低于90%，物理I/O压力显著偏高，建议优先评估SGA参数与热点SQL优化。", rows);
        if (hit < 95D || lowLibrary) return check("WARNING", "内存命中率存在风险项，建议重点关注Buffer Cache与Library Cache命中情况并优化硬解析。", rows);
        return check("NORMAL", "SGA/PGA相关命中率整体健康，暂未发现明显内存命中瓶颈。", rows);
    }

    private Map<String, Object> checkSessionLoad(JdbcTemplate jdbc) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT s.inst_id,MAX(TO_NUMBER(p.value)) max_processes,COUNT(*) current_sessions," +
                        "SUM(CASE WHEN s.status='ACTIVE' THEN 1 ELSE 0 END) active_sessions," +
                        "ROUND(COUNT(*)/DECODE(MAX(TO_NUMBER(p.value)),0,1,MAX(TO_NUMBER(p.value)))*100,2) session_usage_ratio_pct " +
                        "FROM gv$session s JOIN gv$parameter p ON s.inst_id=p.inst_id AND p.name='processes' GROUP BY s.inst_id ORDER BY s.inst_id");
        double max = rows.stream().mapToDouble(r -> number(r.get("SESSION_USAGE_RATIO_PCT"))).max().orElse(0D);
        if (max > 90D) return check("CRITICAL", "会话占用率已超过90%，存在触发 ORA-00020 的高风险，请立即扩容或限流。", rows);
        if (max > 80D) return check("WARNING", "会话占用率超过80%，建议尽快评估连接池配置与峰值流量策略。", rows);
        return check("NORMAL", "当前会话连接负载平稳，距离 processes 上限仍有安全余量。", rows);
    }

    private Map<String, Object> checkUndoUsage(JdbcTemplate jdbc) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT tablespace_name,ROUND(SUM(CASE WHEN status='UNEXPIRED' THEN bytes ELSE 0 END)/1024/1024,2) unexpired_mb," +
                        "ROUND(SUM(CASE WHEN status='ACTIVE' THEN bytes ELSE 0 END)/1024/1024,2) active_mb," +
                        "ROUND(SUM(CASE WHEN status='EXPIRED' THEN bytes ELSE 0 END)/1024/1024,2) expired_mb," +
                        "ROUND(SUM(bytes)/1024/1024,2) undo_total_mb," +
                        "ROUND(SUM(CASE WHEN status IN ('ACTIVE','UNEXPIRED') THEN bytes ELSE 0 END)/DECODE(SUM(bytes),0,1,SUM(bytes))*100,2) undo_effective_used_pct " +
                        "FROM dba_undo_extents GROUP BY tablespace_name ORDER BY undo_effective_used_pct DESC");
        double max = rows.stream().mapToDouble(r -> number(r.get("UNDO_EFFECTIVE_USED_PCT"))).max().orElse(0D);
        if (max > 90D) return check("CRITICAL", "Undo 空间使用率超过90%，高风险触发 ORA-30036，请立即扩容或排查长事务。", rows);
        if (max > 85D) return check("WARNING", "Undo 空间使用率超过85%，建议尽快处理长事务并预留更多回滚段容量。", rows);
        return check("NORMAL", "Undo 空间使用情况正常，当前回滚段容量可满足业务负载。", rows);
    }

    private Map<String, Object> checkRmanBackup(JdbcTemplate jdbc) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM (SELECT input_type,status backup_status,TO_CHAR(start_time,'YYYY-MM-DD HH24:MI') start_time," +
                        "TO_CHAR(end_time,'YYYY-MM-DD HH24:MI') end_time,ROUND(elapsed_seconds/60,1) elapsed_min " +
                        "FROM v$rman_backup_job_details WHERE start_time>SYSDATE-3 ORDER BY start_time DESC) WHERE ROWNUM<=30");
        boolean success = rows.stream().anyMatch(r -> text(r.get("BACKUP_STATUS")).toUpperCase(Locale.ROOT).contains("COMPLETED"));
        boolean failed = rows.stream().anyMatch(r -> text(r.get("BACKUP_STATUS")).toUpperCase(Locale.ROOT).contains("FAILED"));
        if (!success) return check("CRITICAL", "最近3天未检测到成功备份记录，请立即确认RMAN任务链路与备份介质可用性。", rows);
        if (failed) return check("WARNING", "最近备份任务存在失败记录，建议尽快排查失败原因并补做可恢复备份。", rows);
        return check("NORMAL", "最近3天RMAN备份任务运行正常，未发现失败风险。", rows);
    }

    private Map<String, Object> checkTempUsage(JdbcTemplate jdbc) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT tablespace_name,ROUND(tablespace_size*(SELECT block_size FROM dba_tablespaces WHERE tablespace_name=t.tablespace_name)/1024/1024,2) temp_total_mb," +
                        "ROUND(allocated_space/1024/1024,2) temp_allocated_mb,ROUND(free_space/1024/1024,2) temp_free_mb," +
                        "ROUND((allocated_space-free_space)/DECODE(allocated_space,0,1,allocated_space)*100,2) temp_used_pct " +
                        "FROM dba_temp_free_space t ORDER BY temp_used_pct DESC");
        double max = rows.stream().mapToDouble(r -> number(r.get("TEMP_USED_PCT"))).max().orElse(0D);
        if (max > 95D) return check("CRITICAL", "临时表空间使用率超过95%，高风险触发 ORA-01652，建议立即扩容并排查大排序SQL。", rows);
        if (max > 85D) return check("WARNING", "临时表空间使用率超过85%，建议关注排序与Hash Join负载并提前扩容。", rows);
        return check("NORMAL", "临时表空间容量健康，当前未见明显耗尽风险。", rows);
    }

    private Map<String, Object> checkStaleStats(JdbcTemplate jdbc) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT owner,table_name,num_rows,last_analyzed,ROUND(SYSDATE-last_analyzed) days_since_analyze FROM dba_tables " +
                        "WHERE owner NOT IN ('SYS','SYSTEM','XDB','WMSYS') AND (last_analyzed IS NULL OR last_analyzed<SYSDATE-30) " +
                        "AND num_rows>10000 ORDER BY num_rows DESC NULLS FIRST FETCH FIRST 20 ROWS ONLY");
        return check(rows.isEmpty() ? "NORMAL" : "WARNING", rows.isEmpty()
                ? "未发现大表统计信息长期未更新问题。" : "检测到大表统计信息超过30天未更新，建议尽快收集统计信息以避免执行计划劣化。", rows);
    }

    private Map<String, Object> checkIoHotspot(JdbcTemplate jdbc) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM (SELECT df.name file_name,fs.phyrds,fs.phywrts,ROUND(fs.readtim/DECODE(fs.phyrds,0,1,fs.phyrds)*10,2) avg_read_ms," +
                        "ROUND(fs.writetim/DECODE(fs.phywrts,0,1,fs.phywrts)*10,2) avg_write_ms FROM v$filestat fs " +
                        "JOIN v$datafile df ON fs.file#=df.file# ORDER BY (fs.phyrds+fs.phywrts) DESC) WHERE ROWNUM<=10");
        boolean high = rows.stream().anyMatch(r -> number(r.get("AVG_READ_MS")) > 20D || number(r.get("AVG_WRITE_MS")) > 20D);
        return check(high ? "WARNING" : "NORMAL", high
                ? "检测到I/O热点文件且平均读写时延偏高，建议结合ASM与业务热点进行数据分布优化。"
                : "数据文件I/O整体平稳，未发现明显热点时延异常。", rows);
    }

    private Map<String, Object> checkSeqExhaust(JdbcTemplate jdbc) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT sequence_owner,sequence_name,last_number,max_value,increment_by," +
                        "ROUND((max_value-last_number)/DECODE(increment_by,0,1,increment_by)) remaining_values FROM dba_sequences " +
                        "WHERE sequence_owner NOT IN ('SYS','SYSTEM') AND max_value!=9999999999999999999999999999 " +
                        "AND (max_value-last_number)/DECODE(increment_by,0,1,increment_by)<100000 ORDER BY remaining_values");
        double min = rows.stream().mapToDouble(r -> number(r.get("REMAINING_VALUES"))).min().orElse(Double.MAX_VALUE);
        if (rows.isEmpty()) return check("NORMAL", "未发现接近耗尽的业务序列。", rows);
        if (min < 10000D) return check("CRITICAL", "存在序列剩余值低于10000，高风险触发 ORA-08004，请立即扩容或重建序列策略。", rows);
        return check("WARNING", "检测到序列剩余值偏低，请提前规划序列扩展以避免主键分配中断。", rows);
    }

    private Map<String, Object> checkRecyclebin(JdbcTemplate jdbc) {
        OracleRecycleBinService.Snapshot snapshot = recycleBinService.query(jdbc, 30);
        List<Map<String, Object>> rows = snapshot.getRows();
        if (rows.isEmpty()) return check("NORMAL", "回收站为空，无需执行额外清理。", rows);
        return check("WARNING", "检测到" + (snapshot.isDatabaseScope() ? "全库" : "当前用户")
                + "回收站对象 " + snapshot.getCount() + " 个，占用约 "
                + String.format(Locale.ROOT, "%.2f", snapshot.getSizeMb())
                + " MB，建议评估后执行对应范围的 PURGE 释放空间。", rows);
    }

    private Map<String, Object> checkFailedLoginAudit(JdbcTemplate jdbc) {
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList("SELECT * FROM (SELECT dbusername username,userhost,return_code," +
                    "TO_CHAR(event_timestamp,'YYYY-MM-DD HH24:MI:SS') event_time FROM unified_audit_trail " +
                    "WHERE action_name='LOGON' AND return_code<>0 AND event_timestamp>SYSTIMESTAMP-INTERVAL '7' DAY " +
                    "ORDER BY event_timestamp DESC) WHERE ROWNUM<=50");
        } catch (Exception ignored) {
            rows = jdbc.queryForList("SELECT * FROM (SELECT username,userhost,returncode return_code," +
                    "TO_CHAR(timestamp,'YYYY-MM-DD HH24:MI:SS') event_time FROM dba_audit_session " +
                    "WHERE returncode<>0 AND timestamp>SYSDATE-7 ORDER BY timestamp DESC) WHERE ROWNUM<=50");
        }
        if (rows.isEmpty()) return check("NORMAL", "近7天未发现失败登录记录。", rows);
        if (rows.size() >= 20) return check("CRITICAL", "近7天失败登录记录较多（" + rows.size() + "条），请立即排查账号安全与来源主机。", rows);
        return check("WARNING", "检测到失败登录记录（" + rows.size() + "条），建议核查账号口令策略与访问来源。", rows);
    }

    private Map<String, Object> checkHwmBloat(JdbcTemplate jdbc) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM (SELECT s.owner,s.segment_name table_name,ROUND(s.bytes/1024/1024,2) allocated_mb," +
                        "ROUND(NVL(t.num_rows,0)*NVL(t.avg_row_len,0)/1024/1024,2) estimated_data_mb," +
                        "ROUND((s.bytes-NVL(t.num_rows,0)*NVL(t.avg_row_len,0))/1024/1024,2) bloated_mb," +
                        "ROUND((s.bytes-NVL(t.num_rows,0)*NVL(t.avg_row_len,0))/DECODE(s.bytes,0,1,s.bytes)*100,2) bloat_ratio_pct,t.last_analyzed " +
                        "FROM dba_segments s JOIN dba_tables t ON s.owner=t.owner AND s.segment_name=t.table_name " +
                        "WHERE s.segment_type='TABLE' AND s.owner NOT IN ('SYS','SYSTEM','XDB','WMSYS') AND s.bytes>500*1024*1024 " +
                        "ORDER BY bloated_mb DESC) WHERE bloated_mb>200 AND bloat_ratio_pct>50 AND ROWNUM<=20");
        if (rows.isEmpty()) return check("NORMAL", "未发现明显HWM膨胀的大表。", rows);
        if (rows.size() >= 10) return check("CRITICAL", "检测到较多疑似HWM膨胀大表，建议尽快评估分区重组/在线移动/表重建方案。", rows);
        return check("WARNING", "发现疑似HWM膨胀表，建议针对高膨胀对象进行空间整理。", rows);
    }

    private Map<String, Object> checkTablespace(JdbcTemplate jdbc) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT a.tablespace_name,ROUND(a.tablespace_size*b.block_size/1024/1024,2) total_space_mb," +
                        "ROUND((a.tablespace_size-a.used_space)*b.block_size/1024/1024,2) free_space_mb,ROUND(a.used_percent,2) used_percent " +
                        "FROM dba_tablespace_usage_metrics a JOIN dba_tablespaces b ON a.tablespace_name=b.tablespace_name");
        boolean high = rows.stream().anyMatch(r -> number(r.get("USED_PERCENT")) > 90D);
        return check(high ? "WARNING" : "NORMAL", high
                ? "存在使用率超过 90% 的表空间，建议及时扩容或清理数据。" : "所有表空间容量充足。", rows);
    }

    private Map<String, Object> checkAsm(JdbcTemplate jdbc) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT name,total_mb,free_mb,ROUND((total_mb-free_mb)/total_mb*100,2) used_percent FROM v$asm_diskgroup");
        double max = rows.stream().mapToDouble(r -> number(r.get("USED_PERCENT"))).max().orElse(0D);
        if (max > 90D) return check("CRITICAL", "ASM 磁盘组使用率已超过 90%，请立即加盘扩容。", rows);
        if (max > 85D) return check("WARNING", "ASM 磁盘组使用率已超过 85%，空间步入紧张期，建议开始规划磁盘容量清理或者申请存储设备。", rows);
        return check("NORMAL", "ASM 磁盘组空间充足，容灾可用性良好。", rows);
    }

    private Map<String, Object> checkWaitEvent(JdbcTemplate jdbc) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM (SELECT s.event,COUNT(*) waiting_session_count," +
                        "COUNT(DISTINCT CASE WHEN s.row_wait_obj#>0 THEN s.row_wait_obj# END) waiting_object_count," +
                        "LISTAGG(DISTINCT CASE WHEN s.row_wait_obj#>0 THEN o.owner||'.'||o.object_name END,' | ') WITHIN GROUP (ORDER BY o.owner,o.object_name) waiting_objects " +
                        "FROM gv$session s LEFT JOIN dba_objects o ON s.row_wait_obj#=o.object_id " +
                        "WHERE s.wait_class!='Idle' AND s.status='ACTIVE' AND s.event IS NOT NULL GROUP BY s.event " +
                        "ORDER BY waiting_session_count DESC) WHERE ROWNUM<=10");
        if (rows.isEmpty()) return check("NORMAL", "当前活跃会话未观察到显著的非空闲等待事件。", rows);
        long sessions = Math.round(rows.stream().mapToDouble(r -> number(r.get("WAITING_SESSION_COUNT"))).sum());
        long objects = Math.round(rows.stream().mapToDouble(r -> number(r.get("WAITING_OBJECT_COUNT"))).sum());
        return check("WARNING", "当前Top等待事件涉及等待会话总数 " + sessions + "，关联等待对象总数 " + objects
                + "。若等待会话数持续偏高，通常说明系统存在性能瓶颈或并发争用。", rows);
    }

    private Map<String, Object> checkRedoSwitch(JdbcTemplate jdbc) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM (SELECT thread#,TO_CHAR(first_time,'YYYY-MM-DD HH24') switch_hour,COUNT(*) switches " +
                        "FROM v$log_history WHERE first_time>=SYSDATE-1 GROUP BY thread#,TO_CHAR(first_time,'YYYY-MM-DD HH24') " +
                        "ORDER BY switches DESC) WHERE ROWNUM<=5");
        boolean frequent = rows.stream().anyMatch(r -> number(firstValue(r, "SWITCHES")) > 30D);
        return check(frequent ? "WARNING" : "NORMAL", frequent
                ? "发现在同一小时内 Redo Log 日志切换超过 30 次，建议排查批量写入并扩大 Redo 文件组。"
                : "过去 24 小时内归档 Redo 切换稳定可控，无激烈写入风暴。", rows);
    }

    private Map<String, Object> checkArchive(JdbcTemplate jdbc) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT name,space_limit,space_used,ROUND(space_used/space_limit*100,2) used_percent " +
                        "FROM v$recovery_file_dest WHERE space_limit>0");
        boolean high = rows.stream().anyMatch(r -> number(r.get("USED_PERCENT")) > 80D);
        return check(high ? "WARNING" : "NORMAL", high
                ? "FRA 归档空间使用率超过 80%，请及时清理归档以防止数据库挂起。" : "归档空间正常。", rows);
    }

    private Map<String, Object> checkTopSql(JdbcTemplate jdbc) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT sql_id,plan_hash_value,executions,ROUND(elapsed_time/1000000,2) total_elapsed_sec," +
                        "ROUND((elapsed_time/DECODE(executions,0,1,executions))/1000000,3) avg_elapsed_sec," +
                        "DBMS_LOB.SUBSTR(sql_fulltext,2000,1) sql_text_excerpt FROM " +
                        "(SELECT * FROM v$sql WHERE executions>0 AND elapsed_time>0 ORDER BY elapsed_time DESC) WHERE ROWNUM<=10");
        return check("WARNING", "上表列出了当前系统最吃 CPU/内存 的大 SQL，附带完整 SQL_ID 与执行计划哈希值，可据此提取源 SQL 进行优化。", rows);
    }

    private Map<String, Object> checkAsh(JdbcTemplate jdbc) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT s.sid session_id,s.serial# serial_no,s.username,s.blocking_session,s.wait_class,s.event,s.seconds_in_wait,s.sql_id," +
                        "bs.sql_id blocker_sql_id,o.owner||'.'||o.object_name waiting_object FROM v$session s " +
                        "LEFT JOIN v$session bs ON s.blocking_session=bs.sid LEFT JOIN dba_objects o ON s.row_wait_obj#=o.object_id " +
                        "WHERE s.blocking_session IS NOT NULL AND s.status='ACTIVE' ORDER BY s.seconds_in_wait DESC FETCH FIRST 20 ROWS ONLY");
        return check(rows.isEmpty() ? "NORMAL" : "WARNING", rows.isEmpty()
                ? "当前未发现实时阻塞会话。" : "检测到当前实时阻塞会话，请优先处理阻塞源会话并结合等待对象定位热点。", rows);
    }

    private Map<String, Object> checkLongTx(JdbcTemplate jdbc) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT s.sid,s.serial#,s.username,s.machine,s.program,t.start_time," +
                        "ROUND((SYSDATE-TO_DATE(t.start_time,'MM/DD/YY HH24:MI:SS'))*24*60) duration_mins FROM gv$transaction t " +
                        "JOIN gv$session s ON t.addr=s.taddr AND t.inst_id=s.inst_id " +
                        "WHERE ROUND((SYSDATE-TO_DATE(t.start_time,'MM/DD/YY HH24:MI:SS'))*24*60)>10");
        return check(rows.isEmpty() ? "NORMAL" : "WARNING", rows.isEmpty()
                ? "未发现活跃超过 10 分钟的长事务。" : "存在运行时间超过 10 分钟的长事务，可能占用 Undo 并导致锁阻塞。", rows);
    }

    private Map<String, Object> checkGc(JdbcTemplate jdbc) {
        List<Map<String, Object>> source = jdbc.queryForList(
                "SELECT inst_id,SUM(CASE name WHEN 'gc cr blocks received' THEN value ELSE 0 END) cracks_rcvd," +
                        "SUM(CASE name WHEN 'gc cr block receive time' THEN value ELSE 0 END) cr_time FROM gv$sysstat " +
                        "WHERE name IN ('gc cr blocks received','gc cr block receive time') GROUP BY inst_id");
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean high = false;
        for (Map<String, Object> row : source) {
            double blocks = number(row.get("CRACKS_RCVD"));
            double avg = blocks <= 0 ? 0 : number(row.get("CR_TIME")) / blocks * 10D;
            high |= avg > 10D;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("AVG_RECEIVE_TIME_MS", String.format(Locale.ROOT, "%.2f", avg));
            item.put("INST_ID", row.get("INST_ID"));
            rows.add(item);
        }
        return check(high ? "WARNING" : "NORMAL", high
                ? "私网块传输平均时间大于 10ms，可能存在集群网络争用。"
                : "私网传输延迟处于正常范围内（一般建议小于 5ms，最高不超过 10ms）。", rows);
    }

    private Map<String, Object> checkLock(int datasourceIndex) {
        List<Map<String, Object>> rows = databaseService.getLockedObjects(datasourceIndex);
        return check(rows.isEmpty() ? "NORMAL" : "WARNING", rows.isEmpty()
                ? "当前系统中无导致阻塞的长时间锁表事件。" : "发现 " + rows.size() + " 个超过60秒的锁等待，请尽快处理阻塞源会话。", rows);
    }

    private Map<String, Object> checkInvalidObjects(JdbcTemplate jdbc) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT owner,object_type,object_name,status FROM dba_objects WHERE status='INVALID' " +
                        "AND owner NOT IN ('SYS','SYSTEM','XDB','WMSYS','OJVMSYS','ORDSYS','MDSYS','CTXSYS','OLAPSYS') " +
                        "ORDER BY owner,object_type FETCH FIRST 50 ROWS ONLY");
        return check(rows.isEmpty() ? "NORMAL" : "WARNING", rows.isEmpty()
                ? "业务 Schema 对象检查全量正常通过验证。"
                : "检测到业务数据库内部存在部分状态为 INVALID 的对象，可能导致 ORA-04068，请通过 DBMS_UTILITY 编译。", rows);
    }

    private Map<String, Object> checkUnusableIndexes(JdbcTemplate jdbc) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT owner,index_name,table_name,status FROM dba_indexes WHERE status='UNUSABLE' " +
                        "AND owner NOT IN ('SYS','SYSTEM') ORDER BY owner");
        return check(rows.isEmpty() ? "NORMAL" : "CRITICAL", rows.isEmpty()
                ? "生产全域索引 B 树节点结构有效并未脱节毁损。" : "检测到 UNUSABLE 索引，必须尽快在线重建。", rows);
    }

    private Map<String, Object> checkUser(JdbcTemplate jdbc) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT username,account_status,expiry_date,profile,ROUND(expiry_date-SYSDATE) days_to_expire FROM dba_users " +
                        "WHERE default_tablespace!='SYSTEM' AND account_status='OPEN' AND expiry_date IS NOT NULL " +
                        "AND expiry_date>=SYSDATE AND expiry_date<=SYSDATE+30 ORDER BY expiry_date");
        return check(rows.isEmpty() ? "NORMAL" : "WARNING", rows.isEmpty()
                ? "未来30天内未发现即将过期的业务账号。" : "已展示未来30天内可能失效的账号，请提前完成密码轮换。", rows);
    }

    private Map<String, Object> checkAddm(JdbcTemplate jdbc) {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Map<String, Object>> tasks = jdbc.queryForList(
                "SELECT task_name,owner FROM (SELECT task_name,owner FROM dba_advisor_tasks WHERE advisor_name='ADDM' ORDER BY execution_end DESC) WHERE ROWNUM=1");
        if (tasks.isEmpty()) {
            return check("NORMAL", "AWR 后台作业暂未生成最新的 ADDM Task 诊断实例。", rows);
        }
        Map<String, Object> task = tasks.get(0);
        String taskName = text(task.get("TASK_NAME"));
        String owner = text(task.get("OWNER"));
        String report = jdbc.queryForObject("SELECT DBMS_ADVISOR.GET_TASK_REPORT(?, 'TEXT', 'TYPICAL', 'ALL', ?) FROM dual",
                String.class, taskName, owner);
        if (report == null || report.isEmpty()) return check("WARNING", "已找到 ADDM 任务，但报告内容为空。", rows);
        if (report.length() > 20000) report = report.substring(0, 20000) + "\n... (长报告已截断)";
        for (int start = 0, index = 1; start < report.length(); start += 400, index++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ADDM_REPORT_SEGMENT", report.substring(start, Math.min(start + 400, report.length())));
            row.put("OWNER", owner);
            row.put("TASK_NAME", taskName);
            row.put("REPORT_SEGMENT_NO", index);
            rows.add(row);
        }
        return check("NORMAL", "已将 ADDM 报告按固定长度分段导出到明细表格，便于分段阅读。", rows);
    }

    private Map<String, Object> checkServerLog(int datasourceIndex) {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<ServerInfo> servers = sshCommandService.databaseServers(datasourceIndex);
        if (servers.isEmpty()) return check("SKIPPED", "servers.xlsx 中未找到数据库节点的 SSH 配置，无法检查系统及告警日志。", rows);
        int successCount = 0;
        for (ServerInfo server : servers) {
            try {
                String messageOutput = actionableSystemMessageOutput(sshCommandService.execute(server,
                        "bash -lc \"tail -n 10 /var/log/messages 2>/dev/null | egrep -i 'error|fail'\"",
                        Duration.ofSeconds(30)).combinedOutput());
                if (!messageOutput.isEmpty()) rows.add(logRow(server.getHost(), "/var/log/messages", messageOutput));
                String alertOutput = sshCommandService.execute(server,
                        "bash -lc \"find ${ORACLE_BASE:-/u01/app/oracle}/diag/rdbms -name 'alert_*.log' -type f 2>/dev/null | " +
                                "xargs -r tail -n 50 2>/dev/null | egrep -i 'error|fail|ora-|Errors' | tail -n 30\"",
                        Duration.ofSeconds(45)).combinedOutput().trim();
                if (!alertOutput.isEmpty()) rows.add(logRow(server.getHost(), "Oracle Alert Log", alertOutput));
                successCount++;
            } catch (Exception e) {
                log.warn("Health log probe failed host={}: {}", server.getHost(), e.getMessage());
            }
        }
        if (!rows.isEmpty()) return check("WARNING", "基于服务器主机的日志轮询中匹配到 fail/error 关键字，请注意是否有软硬件故障告警。", rows);
        if (successCount == 0) return check("SKIPPED", "数据库节点 SSH 日志检查均未成功，请核对账号权限和网络。", rows);
        return check("NORMAL", "近期集群主机系统与数据库告警日志无突发故障记录。", rows);
    }

    static String actionableSystemMessageOutput(String output) {
        String value = output == null ? "" : output.trim();
        if (value.isEmpty()) return "";
        for (String ignoredMarker : SYSTEM_MESSAGE_IGNORED_MARKERS) {
            if (value.contains(ignoredMarker)) return "";
        }
        return value;
    }

    private RacNodeCluster openRacNodes(DatabaseConfig.DataSourceConfig datasource) {
        List<RacNodeContext> nodes = new ArrayList<>();
        List<NodeTarget> targets = racNodeTargets(datasource);
        ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, Math.min(targets.size(), 8)));
        List<Future<RacNodeContext>> futures = new ArrayList<>();
        try {
            for (NodeTarget target : targets) {
                futures.add(executor.submit(() -> openRacNode(datasource, target)));
            }
            for (int i = 0; i < futures.size(); i++) {
                try {
                    nodes.add(futures.get(i).get());
                } catch (Exception e) {
                    nodes.add(new RacNodeContext(targets.get(i), null, false, null,
                            Collections.emptyMap(), conciseError(e)));
                }
            }
        } finally {
            executor.shutdownNow();
        }
        return new RacNodeCluster(nodes);
    }

    private RacNodeContext openRacNode(DatabaseConfig.DataSourceConfig datasource, NodeTarget target) {
        Connection connection = null;
        try {
            connection = DriverManager.getConnection(target.jdbcUrl, connectionProperties(datasource, 5000));
            JdbcTemplate jdbc = jdbcTemplate(connection);
            Map<String, Object> identity = jdbc.queryForMap(
                    "SELECT i.INSTANCE_NUMBER AS INST_ID,i.INSTANCE_NAME,i.HOST_NAME AS INSTANCE_HOST," +
                            "i.STATUS AS INSTANCE_STATUS,i.VERSION,d.DATABASE_ROLE,d.OPEN_MODE " +
                            "FROM v$instance i CROSS JOIN v$database d");
            return new RacNodeContext(target, connection, true, jdbc, identity, null);
        } catch (Exception e) {
            if (connection != null) closeQuietly(connection);
            log.warn("Oracle RAC node probe failed datasource={} node={}: {}",
                    datasource.getName(), target.host, e.getMessage());
            return new RacNodeContext(target, null, false, null, Collections.emptyMap(),
                    conciseError(e));
        }
    }

    private static Properties connectionProperties(DatabaseConfig.DataSourceConfig datasource,
                                                   int connectTimeoutMillis) {
        Properties properties = new Properties();
        properties.setProperty("user", datasource.getUsername());
        properties.setProperty("password", datasource.getPassword());
        properties.setProperty("oracle.net.CONNECT_TIMEOUT", String.valueOf(connectTimeoutMillis));
        properties.setProperty("oracle.jdbc.ReadTimeout", "60000");
        return properties;
    }

    private static JdbcTemplate jdbcTemplate(Connection connection) {
        SingleConnectionDataSource single = new SingleConnectionDataSource(connection, true);
        JdbcTemplate jdbc = new JdbcTemplate(single);
        jdbc.setQueryTimeout(30);
        jdbc.setMaxRows(500);
        return jdbc;
    }

    private static List<NodeTarget> racNodeTargets(DatabaseConfig.DataSourceConfig datasource) {
        List<NodeTarget> targets = new ArrayList<>();
        for (String jdbcUrl : buildRacNodeJdbcUrls(datasource.getUrl(), datasource.getSlave())) {
            targets.add(new NodeTarget(OracleSshCommandService.extractOracleHost(jdbcUrl), jdbcUrl));
        }
        return targets;
    }

    static List<String> buildRacNodeJdbcUrls(String primaryUrl, String slave) {
        LinkedHashMap<String, String> urls = new LinkedHashMap<>();
        String primaryHost = OracleSshCommandService.extractOracleHost(primaryUrl);
        urls.put(primaryHost.toLowerCase(Locale.ROOT), primaryUrl);
        if (slave != null) {
            for (String value : slave.split(",")) {
                String node = value.trim();
                if (node.isEmpty()) continue;
                String jdbcUrl = buildNodeJdbcUrl(primaryUrl, node);
                String host = OracleSshCommandService.extractOracleHost(jdbcUrl);
                urls.putIfAbsent(host.toLowerCase(Locale.ROOT), jdbcUrl);
            }
        }
        return new ArrayList<>(urls.values());
    }

    static String buildNodeJdbcUrl(String primaryUrl, String nodeValue) {
        String node = nodeValue == null ? "" : nodeValue.trim();
        if (node.toLowerCase(Locale.ROOT).startsWith("jdbc:oracle:")) return node;
        if (node.isEmpty()) throw new IllegalArgumentException("RAC 节点地址不能为空");

        String host = node;
        String explicitPort = null;
        int colon = node.lastIndexOf(':');
        if (colon > 0 && node.indexOf(':') == colon && node.substring(colon + 1).matches("\\d+")) {
            host = node.substring(0, colon).trim();
            explicitPort = node.substring(colon + 1);
        }
        String primaryHost = OracleSshCommandService.extractOracleHost(primaryUrl);
        int searchFrom = Math.max(0, primaryUrl.indexOf('@'));
        int hostIndex = indexOfIgnoreCase(primaryUrl, primaryHost, searchFrom);
        if (hostIndex < 0) throw new IllegalArgumentException("无法替换 Oracle JDBC URL 中的节点地址");
        String url = primaryUrl.substring(0, hostIndex) + host
                + primaryUrl.substring(hostIndex + primaryHost.length());
        if (explicitPort != null) {
            int afterHost = hostIndex + host.length();
            if (afterHost < url.length() && url.charAt(afterHost) == ':') {
                int portEnd = afterHost + 1;
                while (portEnd < url.length() && Character.isDigit(url.charAt(portEnd))) portEnd++;
                url = url.substring(0, afterHost + 1) + explicitPort + url.substring(portEnd);
            } else {
                Matcher portMatcher = Pattern.compile("(?i)(PORT\\s*=\\s*)\\d+").matcher(url);
                if (portMatcher.find()) url = portMatcher.replaceFirst("$1" + explicitPort);
            }
        }
        return url;
    }

    private static int indexOfIgnoreCase(String source, String target, int fromIndex) {
        return source.toLowerCase(Locale.ROOT).indexOf(target.toLowerCase(Locale.ROOT), fromIndex);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> checkAcrossRacNodes(RacNodeCluster cluster,
                                                     Function<JdbcTemplate, Map<String, Object>> checker,
                                                     String checkName) {
        List<Map<String, Object>> rows = new ArrayList<>();
        int normal = 0, warning = 0, critical = 0, failed = 0;
        for (RacNodeContext node : cluster.nodes) {
            if (node.jdbc == null) {
                failed++;
                rows.add(nodeResultRow(node, "ERROR", "节点数据库连接失败: " + node.error));
                continue;
            }
            try {
                Map<String, Object> nodeCheck = checker.apply(node.jdbc);
                String nodeStatus = normalizeStatus(nodeCheck.get("status"));
                if ("CRITICAL".equals(nodeStatus)) critical++;
                else if ("WARNING".equals(nodeStatus)) warning++;
                else if ("NORMAL".equals(nodeStatus)) normal++;
                else failed++;
                List<Map<String, Object>> detail = nodeCheck.get("detail") instanceof List
                        ? (List<Map<String, Object>>) nodeCheck.get("detail") : Collections.emptyList();
                if (detail.isEmpty()) {
                    rows.add(nodeResultRow(node, nodeStatus, text(nodeCheck.get("suggestion"))));
                } else {
                    for (Map<String, Object> value : detail) {
                        Map<String, Object> row = nodeResultRow(node, nodeStatus,
                                text(nodeCheck.get("suggestion")));
                        row.putAll(value);
                        rows.add(row);
                    }
                }
            } catch (Exception e) {
                failed++;
                rows.add(nodeResultRow(node, "ERROR", checkName + "检查失败: " + conciseError(e)));
            }
        }
        int success = normal + warning + critical;
        String status = success == 0 ? "SKIPPED"
                : critical > 0 ? "CRITICAL" : (warning > 0 || failed > 0 ? "WARNING" : "NORMAL");
        String suggestion = "已逐节点检查 RAC " + checkName + "：配置 " + cluster.nodes.size()
                + " 个节点，正常 " + normal + "，警告 " + warning + "，严重 " + critical
                + "，连接或查询失败 " + failed + "。";
        return check(status, suggestion, rows);
    }

    private static Map<String, Object> nodeResultRow(RacNodeContext node, String status,
                                                      String suggestion) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("RAC_NODE", node.target.host);
        row.put("INSTANCE_NAME", text(node.identity.get("INSTANCE_NAME")));
        row.put("NODE_STATUS", status);
        row.put("NODE_SUGGESTION", suggestion);
        return row;
    }

    private static String conciseError(Exception e) {
        Throwable value = e;
        while (value.getCause() != null && value.getCause() != value) value = value.getCause();
        String message = text(value.getMessage());
        if (message.isEmpty()) message = value.getClass().getSimpleName();
        return message.length() > 800 ? message.substring(0, 800) : message;
    }

    private static void closeQuietly(Connection connection) {
        try { connection.close(); }
        catch (Exception ignored) { }
    }

    private Map<String, Object> reportEnvelope(DatabaseConfig.DataSourceConfig datasource,
                                                List<Map<String, Object>> checks,
                                                List<Map<String, Object>> racNodes) {
        int warning = 0, critical = 0, skipped = 0;
        for (Map<String, Object> check : checks) {
            String status = text(check.get("status"));
            if ("WARNING".equals(status)) warning++;
            else if ("CRITICAL".equals(status)) critical++;
            else if ("SKIPPED".equals(status) || "ERROR".equals(status)) skipped++;
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("success", true);
        report.put("databaseType", "ORACLE");
        report.put("datasourceName", datasource.getName());
        report.put("checks", checks);
        report.put("total", checks.size());
        report.put("warningCount", warning);
        report.put("criticalCount", critical);
        report.put("skippedCount", skipped);
        report.put("racNodeCount", racNodes.size());
        report.put("racReachableNodeCount", racNodes.stream()
                .filter(row -> "CONNECTED".equals(text(row.get("CONNECTION_STATUS")))).count());
        report.put("racNodes", racNodes);
        report.put("generatedAt", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        return report;
    }

    private DatabaseConfig.DataSourceConfig oracleDatasource(int datasourceIndex) {
        List<DatabaseConfig.DataSourceConfig> datasources = databaseConfig.getDatasources();
        if (datasources == null || datasourceIndex < 0 || datasourceIndex >= datasources.size()) {
            throw new IllegalArgumentException("数据源不存在: " + datasourceIndex);
        }
        DatabaseConfig.DataSourceConfig datasource = datasources.get(datasourceIndex);
        String type = datasource.getType() == null ? "ORACLE" : datasource.getType().toUpperCase(Locale.ROOT);
        if (!"ORACLE".equals(type)) throw new IllegalArgumentException("完整智能体检报告仅适用于 Oracle 数据源");
        return datasource;
    }

    private static Map<String, Object> check(String status, String suggestion, List<Map<String, Object>> detail) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("suggestion", suggestion);
        result.put("detail", detail == null ? Collections.emptyList() : detail);
        return result;
    }

    private static Map<String, Object> result(String id, String name, String category, String status,
                                              String suggestion, List<Map<String, Object>> detail) {
        Map<String, Object> result = check(status, suggestion, detail);
        result.put("id", id);
        result.put("name", name);
        result.put("category", category);
        return result;
    }

    private static Map<String, Object> logRow(String host, String type, String output) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("NODE_IP", host);
        row.put("LOG_TYPE", type);
        row.put("ERROR_HINT", output.length() > 4000 ? output.substring(0, 4000) : output);
        return row;
    }

    private static Object firstValue(Map<String, Object> row, String key) {
        if (row.containsKey(key)) return row.get(key);
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (key.equalsIgnoreCase(entry.getKey()) || normalizeKey(key).equals(normalizeKey(entry.getKey()))) return entry.getValue();
        }
        return null;
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

    private static String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private static double number(Object value) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        try { return value == null ? 0D : Double.parseDouble(String.valueOf(value)); }
        catch (Exception ignored) { return 0D; }
    }

    private static long parseLagSeconds(Object value) {
        String text = text(value);
        if (text.isEmpty()) return 0;
        Matcher matcher = Pattern.compile("([+-])?(\\d+)\\s+(\\d+):(\\d+):(\\d+)").matcher(text);
        if (!matcher.find()) return 0;
        return Long.parseLong(matcher.group(2)) * 86400L + Long.parseLong(matcher.group(3)) * 3600L
                + Long.parseLong(matcher.group(4)) * 60L + Long.parseLong(matcher.group(5));
    }

    private static TaskMeta task(String id, String name, String category) { return new TaskMeta(id, name, category); }
    private static final class TaskMeta {
        private final String id;
        private final String name;
        private final String category;
        private TaskMeta(String id, String name, String category) { this.id = id; this.name = name; this.category = category; }
    }

    private static final class NodeTarget {
        private final String host;
        private final String jdbcUrl;

        private NodeTarget(String host, String jdbcUrl) {
            this.host = host;
            this.jdbcUrl = jdbcUrl;
        }
    }

    private static final class RacNodeContext {
        private final NodeTarget target;
        private final Connection connection;
        private final boolean closeConnection;
        private final JdbcTemplate jdbc;
        private final Map<String, Object> identity;
        private final String error;

        private RacNodeContext(NodeTarget target, Connection connection, boolean closeConnection,
                               JdbcTemplate jdbc, Map<String, Object> identity, String error) {
            this.target = target;
            this.connection = connection;
            this.closeConnection = closeConnection;
            this.jdbc = jdbc;
            this.identity = identity;
            this.error = error;
        }
    }

    private static final class RacNodeCluster implements AutoCloseable {
        private final List<RacNodeContext> nodes;

        private RacNodeCluster(List<RacNodeContext> nodes) {
            this.nodes = nodes;
        }

        private JdbcTemplate firstReachableJdbc() {
            for (RacNodeContext node : nodes) {
                if (node.jdbc != null) return node.jdbc;
            }
            return null;
        }

        private List<Map<String, Object>> overview() {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (RacNodeContext node : nodes) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("RAC_NODE", node.target.host);
                row.put("CONNECTION_STATUS", node.jdbc == null ? "ERROR" : "CONNECTED");
                row.put("INST_ID", node.identity.get("INST_ID"));
                row.put("INSTANCE_NAME", node.identity.get("INSTANCE_NAME"));
                row.put("INSTANCE_HOST", node.identity.get("INSTANCE_HOST"));
                row.put("INSTANCE_STATUS", node.identity.get("INSTANCE_STATUS"));
                row.put("DATABASE_ROLE", node.identity.get("DATABASE_ROLE"));
                row.put("OPEN_MODE", node.identity.get("OPEN_MODE"));
                row.put("VERSION", node.identity.get("VERSION"));
                row.put("ERROR_MESSAGE", node.error == null ? "" : node.error);
                rows.add(row);
            }
            return rows;
        }

        @Override
        public void close() {
            for (RacNodeContext node : nodes) {
                if (node.closeConnection && node.connection != null) closeQuietly(node.connection);
            }
        }
    }

}
