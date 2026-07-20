package com.li.jc.webtool.controller;

import com.li.jc.webtool.dto.AuditLogEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 审计日志控制器
 * 提供SSH和SQL审计日志的读取、统计和查询接口
 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private static final Logger log = LoggerFactory.getLogger(AuditController.class);

    @Value("${audit.ssh.directory:./logs/ssh_audit}")
    private String sshAuditDir;

    @Value("${audit.log.directory:./logs/sql_audit}")
    private String sqlAuditDir;

    @Autowired
    private ConfigurableEnvironment env;

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ──────────────────────────────────────────────
    // 配置信息
    // ──────────────────────────────────────────────

    @GetMapping("/config")
    public Map<String, Object> getConfig() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("usernames", getAuthUsernames());
        return result;
    }

    private List<String> getAuthUsernames() {
        List<String> usernames = new ArrayList<>();
        try {
            String prefix = "auth.users.";
            for (org.springframework.core.env.PropertySource<?> ps : env.getPropertySources()) {
                Object source = ps.getSource();
                if (source instanceof Map) {
                    collectUsernamesFromMap(prefix, (Map<?, ?>) source, usernames);
                }
            }
            log.debug("从 auth.users 配置读取到 {} 个用户: {}", usernames.size(), usernames);
        } catch (Exception e) {
            log.warn("读取 auth.users 配置失败", e);
        }
        Collections.sort(usernames);
        return usernames;
    }

    private void collectUsernamesFromMap(String prefix, Map<?, ?> map, List<String> usernames) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (key.startsWith(prefix)) {
                String username = key.substring(prefix.length());
                if (!username.contains(".") && !username.startsWith("#") && !usernames.contains(username)) {
                    usernames.add(username);
                }
            }
            if (entry.getValue() instanceof Map) {
                collectUsernamesFromMap(prefix, (Map<?, ?>) entry.getValue(), usernames);
            }
        }
    }

    // ──────────────────────────────────────────────
    // 统计概览
    // ──────────────────────────────────────────────

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ssh", countSshStats());
        result.put("sql", countSqlStats());
        result.put("users", getActiveUsers());
        return result;
    }

    private Map<String, Object> countSshStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        try {
            List<File> files = listAuditFiles(sshAuditDir, "ssh_audit_");
            final AtomicLong connections = new AtomicLong(0);
            final AtomicLong disconnections = new AtomicLong(0);
            final Set<String> users = new HashSet<>();
            for (File f : files) {
                parseSshFile(f, new SshEntryConsumer() {
                    public void accept(String type, String username, String host, LocalDateTime ts) {
                        if ("CONNECTION".equals(type)) connections.incrementAndGet();
                        else if ("DISCONNECTION".equals(type)) disconnections.incrementAndGet();
                        if (username != null) users.add(username);
                    }
                });
                SshParseState s = sshState.get();
                if (s.pending) {
                    s.pending = false;
                }
            }
            long failed = 0;
            for (File f : files) {
                failed += countInFile(f, "连接状态: 失败");
            }
            stats.put("totalConnections", connections.get());
            stats.put("totalDisconnections", disconnections.get());
            stats.put("failedConnections", failed);
            stats.put("activeUsers", users.size());
        } catch (Exception e) {
            log.error("统计SSH审计失败", e);
        }
        return stats;
    }

    private Map<String, Object> countSqlStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        try {
            List<File> files = listAuditFiles(sqlAuditDir, "sql_audit_");
            final AtomicLong success = new AtomicLong(0);
            final AtomicLong failed = new AtomicLong(0);
            final Set<String> users = new HashSet<>();
            for (File f : files) {
                parseSqlFile(f, new SqlEntryConsumer() {
                    public void accept(String type, String u, String ds, String s, String sql, LocalDateTime ts) {
                        if ("SUCCESS".equals(s)) success.incrementAndGet();
                        else if ("FAILURE".equals(s)) failed.incrementAndGet();
                        if (u != null) users.add(u);
                    }
                });
            }
            stats.put("totalExecutions", success.get() + failed.get());
            stats.put("successfulExecutions", success.get());
            stats.put("failedExecutions", failed.get());
            stats.put("activeUsers", users.size());
        } catch (Exception e) {
            log.error("统计SQL审计失败", e);
        }
        return stats;
    }

    private List<String> getActiveUsers() {
        Set<String> users = new TreeSet<>();
        try {
            listAuditFiles(sshAuditDir, "ssh_audit_").forEach(f -> {
                String name = f.getName().replace("ssh_audit_", "").replace(".log", "");
                users.add(name);
            });
            listAuditFiles(sqlAuditDir, "sql_audit_").forEach(f -> {
                String name = f.getName().replace("sql_audit_", "").replace(".log", "");
                users.add(name);
            });
        } catch (Exception e) {
            log.error("获取活跃用户失败", e);
        }
        return new ArrayList<>(users);
    }

    // ──────────────────────────────────────────────
    // SSH 审计日志
    // ──────────────────────────────────────────────

    @GetMapping("/ssh")
    public Map<String, Object> getSshLogs(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String host,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(required = false, defaultValue = "50") Integer limit,
            @RequestParam(required = false, defaultValue = "0") Integer offset) {

        Map<String, Object> result = new LinkedHashMap<>();
        final List<AuditLogEntry> entries = new ArrayList<>();
        final LocalDateTime filterStart = parseTimestamp(startTime);
        final LocalDateTime filterEnd = parseTimestamp(endTime);

        try {
            List<File> files;
            if (username != null && username.trim().length() > 0) {
                files = Collections.singletonList(
                    new File(sshAuditDir, "ssh_audit_" + sanitizeFilename(username) + ".log"));
            } else {
                files = listAuditFiles(sshAuditDir, "ssh_audit_");
            }

            final String filterStatus = status;
            for (final File f : files) {
                parseSshFile(f, new SshEntryConsumer() {
                    public void accept(String type, String username, String host, LocalDateTime ts) {
                        if (ts != null) {
                            if (filterStart != null && ts.isBefore(filterStart)) return;
                            if (filterEnd != null && ts.isAfter(filterEnd)) return;
                        }
                        AuditLogEntry e = buildCurrentSshEntry(type);
                        if (e == null) return;
                        if (host != null && host.trim().length() > 0 && !host.equals(e.getTargetHost())) return;
                        if (filterStatus != null && filterStatus.trim().length() > 0
                                && !filterStatus.equals(e.getStatus())) return;
                        entries.add(e);
                    }
                });
                //  emit last pending entry if file didn't end with ═════
                SshParseState s = sshState.get();
                if (s.pending) {
                    if (s.timestamp != null) {
                        if (filterStart == null || !s.timestamp.isBefore(filterStart)) {
                            if (filterEnd == null || !s.timestamp.isAfter(filterEnd)) {
                                AuditLogEntry e = buildCurrentSshEntry(s.type);
                                if (e != null) entries.add(e);
                            }
                        }
                    }
                    s.pending = false;
                }
            }
            // flush pending entry from the very last file
            SshParseState last = sshState.get();
            if (last.pending) {
                boolean keep = last.timestamp != null
                    && (filterStart == null || !last.timestamp.isBefore(filterStart))
                    && (filterEnd == null || !last.timestamp.isAfter(filterEnd));
                if (keep) {
                    AuditLogEntry e = buildCurrentSshEntry(last.type);
                    if (e != null) entries.add(e);
                }
                last.pending = false;
            }
        } catch (Exception e) {
            log.error("读取SSH审计日志失败", e);
        }

        // 按时间倒序
        Collections.sort(entries, new Comparator<AuditLogEntry>() {
            public int compare(AuditLogEntry a, AuditLogEntry b) {
                if (a.getTimestamp() == null) return 1;
                if (b.getTimestamp() == null) return -1;
                return b.getTimestamp().compareTo(a.getTimestamp());
            }
        });

        // 分页
        int total = entries.size();
        int fromIndex = Math.min(offset, total);
        int toIndex = Math.min(offset + limit, total);
        List<AuditLogEntry> page = entries.subList(fromIndex, toIndex);

        result.put("total", total);
        result.put("limit", limit);
        result.put("offset", offset);
        result.put("data", page);
        return result;
    }

    // ──────────────────────────────────────────────
    // SSH 命令日志
    // ──────────────────────────────────────────────

    @GetMapping("/ssh/commands")
    public Map<String, Object> getSshCommands(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String host,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(required = false, defaultValue = "100") Integer limit,
            @RequestParam(required = false, defaultValue = "0") Integer offset) {

        Map<String, Object> result = new LinkedHashMap<>();
        final List<AuditLogEntry> entries = new ArrayList<>();
        final LocalDateTime filterStart = parseTimestamp(startTime);
        final LocalDateTime filterEnd = parseTimestamp(endTime);

        try {
            List<File> files;
            if (username != null && username.trim().length() > 0) {
                files = Collections.singletonList(
                    new File(sshAuditDir, "ssh_cmd_audit_" + sanitizeFilename(username) + ".log"));
            } else {
                files = listAuditFiles(sshAuditDir, "ssh_cmd_audit_");
            }

            for (final File f : files) {
                parseSshCmdFile(f, new SshCmdConsumer() {
                    public void accept(LocalDateTime ts, String u, String sid, String h, String cmd) {
                        if (ts != null) {
                            if (filterStart != null && ts.isBefore(filterStart)) return;
                            if (filterEnd != null && ts.isAfter(filterEnd)) return;
                        }
                        AuditLogEntry e = AuditLogEntry.builder()
                                .type("SSH_COMMAND")
                                .username(u)
                                .sessionId(sid)
                                .targetHost(h)
                                .timestamp(ts)
                                .rawLine(cmd)
                                .build();
                        if (host != null && host.trim().length() > 0 && !host.equals(e.getTargetHost())) return;
                        entries.add(e);
                    }
                });
            }
        } catch (Exception e) {
            log.error("读取SSH命令日志失败", e);
        }

        Collections.sort(entries, new Comparator<AuditLogEntry>() {
            public int compare(AuditLogEntry a, AuditLogEntry b) {
                if (a.getTimestamp() == null) return 1;
                if (b.getTimestamp() == null) return -1;
                return b.getTimestamp().compareTo(a.getTimestamp());
            }
        });

        int total = entries.size();
        int fromIndex = Math.min(offset, total);
        int toIndex = Math.min(offset + limit, total);
        List<AuditLogEntry> page = entries.subList(fromIndex, toIndex);

        result.put("total", total);
        result.put("limit", limit);
        result.put("offset", offset);
        result.put("data", page);
        return result;
    }

    // ──────────────────────────────────────────────
    // SQL 审计日志
    // ──────────────────────────────────────────────

    @GetMapping("/sql")
    public Map<String, Object> getSqlLogs(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String datasource,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(required = false, defaultValue = "50") Integer limit,
            @RequestParam(required = false, defaultValue = "0") Integer offset) {

        Map<String, Object> result = new LinkedHashMap<>();
        final List<AuditLogEntry> entries = new ArrayList<>();
        final LocalDateTime filterStart = parseTimestamp(startTime);
        final LocalDateTime filterEnd = parseTimestamp(endTime);

        try {
            List<File> files;
            if (username != null && username.trim().length() > 0) {
                files = Collections.singletonList(
                    new File(sqlAuditDir, "sql_audit_" + sanitizeFilename(username) + ".log"));
            } else {
                files = listAuditFiles(sqlAuditDir, "sql_audit_");
            }

            for (final File f : files) {
                parseSqlFile(f, new SqlEntryConsumer() {
                    public void accept(String type, String u, String ds, String s, String sql, LocalDateTime ts) {
                        if (ts != null) {
                            if (filterStart != null && ts.isBefore(filterStart)) return;
                            if (filterEnd != null && ts.isAfter(filterEnd)) return;
                        }
                        AuditLogEntry e = buildCurrentSqlEntry();
                        if (e == null) return;
                        if (datasource != null && datasource.trim().length() > 0
                                && !datasource.equals(e.getDatasourceName())) return;
                        if (s != null && s.trim().length() > 0
                                && !s.equals(e.getStatus())) return;
                        entries.add(e);
                    }
                });
                SqlParseState s = sqlState.get();
                if (s.pending) {
                    if (s.timestamp == null ||
                        (filterStart == null || !s.timestamp.isBefore(filterStart)) &&
                        (filterEnd == null || !s.timestamp.isAfter(filterEnd))) {
                        AuditLogEntry e = buildCurrentSqlEntry();
                        if (e != null) entries.add(e);
                    }
                    s.pending = false;
                }
            }
        } catch (Exception e) {
            log.error("读取SQL审计日志失败", e);
        }

        Collections.sort(entries, new Comparator<AuditLogEntry>() {
            public int compare(AuditLogEntry a, AuditLogEntry b) {
                if (a.getTimestamp() == null) return 1;
                if (b.getTimestamp() == null) return -1;
                return b.getTimestamp().compareTo(a.getTimestamp());
            }
        });

        int total = entries.size();
        int fromIndex = Math.min(offset, total);
        int toIndex = Math.min(offset + limit, total);
        List<AuditLogEntry> page = entries.subList(fromIndex, toIndex);

        result.put("total", total);
        result.put("limit", limit);
        result.put("offset", offset);
        result.put("data", page);
        return result;
    }

    // ──────────────────────────────────────────────
    // 解析器
    // ──────────────────────────────────────────────

    // Thread-local 状态，用于跨行解析SSH连接/断开块
    private static final ThreadLocal<SshParseState> sshState = new ThreadLocal<SshParseState>() {
        protected SshParseState initialValue() { return new SshParseState(); }
    };

    private void parseSshFile(File file, SshEntryConsumer consumer) {
        if (!file.exists()) return;
        sshState.get().reset();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                processSshLine(line, consumer);
            }
        } catch (IOException e) {
            log.error("解析SSH文件失败: {}", file.getName(), e);
        }
    }

    private void processSshLine(String line, SshEntryConsumer consumer) {
        SshParseState state = sshState.get();

        if (line.contains("【SSH连接】")) {
            // flush any pending DISCONNECTION entry before starting new CONNECTION block
            SshParseState st = sshState.get();
            if (st.pending && "DISCONNECTION".equals(st.type)) {
                consumer.accept(st.type, st.username, st.targetHost, st.timestamp);
            }
            st.reset();
            st.type = "CONNECTION";
            st.inBlock = true;
            return;
        }
        if (line.contains("【SSH断开】")) {
            // flush any pending CONNECTION entry before starting new DISCONNECTION block
            SshParseState st = sshState.get();
            if (st.pending && "CONNECTION".equals(st.type)) {
                consumer.accept(st.type, st.username, st.targetHost, st.timestamp);
            }
            st.reset();
            st.type = "DISCONNECTION";
            st.inBlock = true;
            return;
        }
        if (!state.inBlock) return;
        if (line.startsWith("════════════════════════")) {
            return;
        }

        if (line.startsWith("操作时间:")) {
            state.timestamp = parseTimestamp(extractAfter(line, "操作时间:"));
        } else if (line.startsWith("操作用户:")) {
            state.username = extractAfter(line, "操作用户:").trim();
        } else if (line.startsWith("会话ID:")) {
            state.sessionId = extractAfter(line, "会话ID:").replace("...", "").trim();
        } else if (line.startsWith("目标服务器:")) {
            String target = extractAfter(line, "目标服务器:").trim();
            Pattern p = Pattern.compile("^(.+?)@(.+?):(\\d+)$");
            Matcher m = p.matcher(target);
            if (m.find()) {
                state.targetUser = m.group(1);
                state.targetHost = m.group(2);
                state.targetPort = Integer.parseInt(m.group(3));
            }
        } else if (line.startsWith("连接状态:")) {
            state.status = line.contains("成功") ? "SUCCESS" : "FAILURE";
            state.pending = true;
        } else if (line.startsWith("连接时长:")) {
            state.pending = true;
        } else if (line.startsWith("错误信息:")) {
            state.errorMessage = extractAfter(line, "错误信息:").trim();
        } else if (line.startsWith("连接时长:")) {
            state.durationMs = parseDurationToMs(extractAfter(line, "连接时长:"));
        } else if (line.startsWith("════════") && state.type != null) {
            state.pending = true;
            consumer.accept(state.type, state.username, state.targetHost, state.timestamp);
            state.reset();
        }
    }

    private AuditLogEntry buildCurrentSshEntry(String type) {
        SshParseState state = sshState.get();
        if (state.username == null && state.targetHost == null) return null;
        return AuditLogEntry.builder()
                .type(type)
                .username(state.username)
                .timestamp(state.timestamp != null ? state.timestamp : LocalDateTime.now())
                .targetHost(state.targetHost)
                .targetPort(state.targetPort)
                .targetUser(state.targetUser)
                .sessionId(state.sessionId)
                .status(state.status)
                .errorMessage(state.errorMessage)
                .connectionTimeMs(state.durationMs)
                .build();
    }

    // SQL 解析器
    private static final ThreadLocal<SqlParseState> sqlState = new ThreadLocal<SqlParseState>() {
        protected SqlParseState initialValue() { return new SqlParseState(); }
    };

    private void parseSqlFile(File file, SqlEntryConsumer consumer) {
        if (!file.exists()) return;
        sqlState.get().reset();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            boolean inSql = false;
            StringBuilder sqlBuilder = new StringBuilder();
            while ((line = br.readLine()) != null) {
                if (line.contains("════════════════════════")) {
                    if (sqlState.get().type != null && !inSql) {
                        sqlBuilder.setLength(0);
                    }
                    continue;
                }
                if (line.startsWith("操作时间:")) {
                    sqlState.get().reset();
                    sqlState.get().type = "SQL_EXECUTION";
                    sqlState.get().timestamp = parseTimestamp(extractAfter(line, "操作时间:"));
                } else if (line.startsWith("操作用户:")) {
                    sqlState.get().username = extractAfter(line, "操作用户:").trim();
                } else if (line.startsWith("数据源:")) {
                    sqlState.get().datasourceName = extractAfter(line, "数据源:").trim();
                } else if (line.startsWith("执行状态:")) {
                    sqlState.get().status = line.contains("成功") ? "SUCCESS" : "FAILURE";
                } else if (line.startsWith("执行耗时:")) {
                    String msStr = extractAfter(line, "执行耗时:").replace("ms", "").trim();
                    try { sqlState.get().executionTimeMs = Long.parseLong(msStr); } catch (Exception e) {}
                } else if (line.startsWith("影响行数:")) {
                    String rows = extractAfter(line, "影响行数:").trim();
                    try { sqlState.get().affectedRows = Integer.parseInt(rows); } catch (Exception e) {}
                } else if (line.startsWith("错误信息:")) {
                    sqlState.get().errorMessage = extractAfter(line, "错误信息:").trim();
                } else if (line.equals("SQL语句:")) {
                    inSql = true;
                    sqlBuilder.setLength(0);
                } else if (line.equals("---")) {
                    if (inSql) {
                        sqlState.get().sql = sqlBuilder.toString().trim();
                        inSql = false;
                        sqlState.get().pending = true;
                        consumer.accept(sqlState.get().type, sqlState.get().username,
                                sqlState.get().datasourceName, sqlState.get().status, sqlState.get().sql,
                                sqlState.get().timestamp);
                        sqlState.get().reset();
                    }
                } else if (inSql) {
                    sqlBuilder.append(line).append("\n");
                }
            }
        } catch (Exception e) {
            log.error("解析SQL文件失败: {}", file.getName(), e);
        }
    }

    private AuditLogEntry buildCurrentSqlEntry() {
        SqlParseState state = sqlState.get();
        if (state.timestamp == null) return null;
        return AuditLogEntry.builder()
                .type("SQL_EXECUTION")
                .username(state.username)
                .timestamp(state.timestamp)
                .datasourceName(state.datasourceName)
                .status(state.status)
                .errorMessage(state.errorMessage)
                .executionTimeMs(state.executionTimeMs)
                .affectedRows(state.affectedRows)
                .sql(state.sql)
                .build();
    }

    // SSH 命令日志解析 (单行格式)
    private void parseSshCmdFile(File file, SshCmdConsumer consumer) {
        if (!file.exists()) return;
        Pattern p = Pattern.compile(
                "^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})" +
                "\\s*\\|\\s*(.+?)" +
                "\\s*\\|\\s*(.+?)" +
                "\\s*\\|\\s*(.+?)" +
                "\\s*\\|\\s*(.+)$");
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                Matcher m = p.matcher(line);
                if (m.find()) {
                    LocalDateTime ts = LocalDateTime.parse(m.group(1), DT_FMT);
                    consumer.accept(ts, m.group(2).trim(), m.group(3).trim(),
                            m.group(4).trim(), m.group(5).trim());
                }
            }
        } catch (Exception e) {
            log.error("解析SSH命令文件失败: {}", file.getName(), e);
        }
    }

    // ──────────────────────────────────────────────
    // 工具方法
    // ──────────────────────────────────────────────

    private List<File> listAuditFiles(String dir, String prefix) throws IOException {
        Path path = Paths.get(dir);
        if (!Files.exists(path)) return Collections.emptyList();
        return Files.list(path)
                .filter(p -> p.getFileName().toString().startsWith(prefix) && p.toString().endsWith(".log"))
                .map(Path::toFile)
                .sorted(new Comparator<File>() {
                    public int compare(File a, File b) {
                        return Long.compare(b.lastModified(), a.lastModified());
                    }
                })
                .collect(Collectors.toList());
    }

    private long countInFile(File file, String contains) {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            long count = 0;
            String line;
            while ((line = br.readLine()) != null) {
                if (line.contains(contains)) count++;
            }
            return count;
        } catch (IOException e) {
            return 0;
        }
    }

    private LocalDateTime parseTimestamp(String s) {
        if (s == null || s.trim().length() == 0) return null;
        try {
            return LocalDateTime.parse(s.trim(), DT_FMT);
        } catch (Exception e) {
            return null;
        }
    }

    private Long parseDurationToMs(String s) {
        if (s == null || s.trim().length() == 0) return null;
        long total = 0;
        Matcher h = Pattern.compile("(\\d+)小时").matcher(s);
        if (h.find()) total += Long.parseLong(h.group(1)) * 3600000L;
        Matcher m = Pattern.compile("(\\d+)分钟").matcher(s);
        if (m.find()) total += Long.parseLong(m.group(1)) * 60000L;
        Matcher sec = Pattern.compile("(\\d+)秒").matcher(s);
        if (sec.find()) total += Long.parseLong(sec.group(1)) * 1000L;
        return total > 0 ? total : null;
    }

    private String extractAfter(String line, String prefix) {
        return line.length() > prefix.length() ? line.substring(prefix.length()) : "";
    }

    private String sanitizeFilename(String filename) {
        if (filename == null) return "unknown";
        return filename.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    // ──────────────────────────────────────────────
    // 内部类型
    // ──────────────────────────────────────────────

    private interface SshEntryConsumer {
        void accept(String type, String username, String host, LocalDateTime ts);
    }

    private interface SqlEntryConsumer {
        void accept(String type, String username, String datasource, String status, String sql, LocalDateTime ts);
    }

    private interface SshCmdConsumer {
        void accept(LocalDateTime ts, String username, String sessionId, String host, String command);
    }

    private static class SshParseState {
        boolean inBlock = false;
        boolean pending = false;
        String type;
        LocalDateTime timestamp;
        String username;
        String targetHost;
        Integer targetPort;
        String targetUser;
        String sessionId;
        String status;
        String errorMessage;
        Long durationMs;

        void reset() {
            inBlock = false; pending = false; type = null; timestamp = null; username = null;
            targetHost = null; targetPort = null; targetUser = null;
            sessionId = null; status = null; errorMessage = null; durationMs = null;
        }
    }

    private static class SqlParseState {
        boolean pending = false;
        String type;
        LocalDateTime timestamp;
        String username;
        String datasourceName;
        String status;
        String errorMessage;
        Long executionTimeMs;
        Integer affectedRows;
        String sql;

        void reset() {
            pending = false; type = null; timestamp = null; username = null;
            datasourceName = null; status = null; errorMessage = null;
            executionTimeMs = null; affectedRows = null; sql = null;
        }
    }
}
