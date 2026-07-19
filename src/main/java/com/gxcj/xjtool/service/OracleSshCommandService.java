package com.gxcj.xjtool.service;

import com.gxcj.xjtool.config.ExcelServerConfigLoader;
import com.gxcj.xjtool.config.OracleConfig;
import com.gxcj.xjtool.model.ServerInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Executes the Oracle maintenance commands against SSH hosts already managed by xjtool. */
@Slf4j
@Service
@RequiredArgsConstructor
public class OracleSshCommandService {

    private static final Pattern ORACLE_JDBC_HOST = Pattern.compile(
            "jdbc:oracle:thin:@(?:\\(.*?HOST\\s*=\\s*)?/?/?([^:/)]+)", Pattern.CASE_INSENSITIVE);

    private final OracleConfig oracleConfig;
    private final ExcelServerConfigLoader serverConfigLoader;

    public CommandResult cleanupArchiveLogs(int datasourceIndex, int retentionDays) {
        OracleConfig.OracleDataSource datasource = oracleDatasource(datasourceIndex);
        String databaseHost = extractOracleHost(datasource.getUrl());
        ServerInfo server = serverConfigLoader.findServerByHost(databaseHost);
        if (server == null) {
            throw new IllegalStateException("未在 servers.xlsx 中找到数据库主机 " + databaseHost + " 的 SSH 配置");
        }
        if (!"oracle".equalsIgnoreCase(server.getUsername())) {
            throw new IllegalStateException("数据库主机 " + databaseHost
                    + " 的 SSH 用户不是 oracle，请在 servers.xlsx 中配置 oracle 账号后重试");
        }

        int days = Math.max(1, Math.min(retentionDays, 3650));
        String rmanScript = "crosscheck archivelog all;\n"
                + "DELETE NOPROMPT ARCHIVELOG ALL COMPLETED BEFORE \"SYSDATE-" + days + "\";\n"
                + "exit;\n";
        String encoded = Base64.getEncoder().encodeToString(rmanScript.getBytes(StandardCharsets.UTF_8));
        String command = "bash -lc 'source ~/.bash_profile >/dev/null 2>&1 || true; "
                + "source ~/.bashrc >/dev/null 2>&1 || true; "
                + "printf %s " + encoded + " | base64 -d | rman target /'";
        CommandResult result = execute(server, command, Duration.ofMinutes(10));
        String output = result.combinedOutput();
        if (result.getExitCode() != 0 || output.contains("RMAN-00569") || output.contains("RMAN-03002")
                || output.contains("ORA-01017") || output.contains("command not found")) {
            throw new IllegalStateException("RMAN 归档清理失败: " + abbreviate(output, 2000));
        }
        log.warn("Oracle RMAN archive cleanup executed host={} retentionDays={} exitCode={}",
                databaseHost, days, result.getExitCode());
        return result;
    }

    public List<ServerInfo> databaseServers(int datasourceIndex) {
        OracleConfig.OracleDataSource datasource = oracleDatasource(datasourceIndex);
        Set<String> hosts = new LinkedHashSet<>();
        hosts.add(extractOracleHost(datasource.getUrl()));
        if (datasource.getSlave() != null) {
            for (String value : datasource.getSlave().split(",")) {
                String host = value.trim();
                int colon = host.indexOf(':');
                if (colon > 0) host = host.substring(0, colon);
                if (!host.isEmpty()) hosts.add(host);
            }
        }
        List<ServerInfo> servers = new ArrayList<>();
        for (String host : hosts) {
            ServerInfo server = serverConfigLoader.findServerByHost(host);
            if (server != null) servers.add(server);
        }
        return servers;
    }

    public CommandResult execute(ServerInfo server, String command, Duration timeout) {
        if (server == null) throw new IllegalArgumentException("SSH 服务器不能为空");
        SshClient client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        client.start();
        try (ClientSession session = client.connect(server.getUsername(), server.getHost(), server.getPort())
                .verify(Duration.ofSeconds(30)).getSession()) {
            session.addPasswordIdentity(server.getPassword());
            session.auth().verify(Duration.ofSeconds(30));
            try (ClientChannel channel = session.createExecChannel(command);
                 ByteArrayOutputStream stdout = new ByteArrayOutputStream();
                 ByteArrayOutputStream stderr = new ByteArrayOutputStream()) {
                channel.setOut(stdout);
                channel.setErr(stderr);
                channel.open().verify(Duration.ofSeconds(30));
                Set<ClientChannelEvent> events = channel.waitFor(
                        EnumSet.of(ClientChannelEvent.CLOSED), timeout);
                if (!events.contains(ClientChannelEvent.CLOSED)) {
                    channel.close(true);
                    throw new IllegalStateException("SSH 命令执行超时: " + server.getHost());
                }
                Integer exitStatus = channel.getExitStatus();
                return new CommandResult(exitStatus == null ? -1 : exitStatus,
                        new String(stdout.toByteArray(), StandardCharsets.UTF_8),
                        new String(stderr.toByteArray(), StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            if (e instanceof IllegalStateException) throw (IllegalStateException) e;
            throw new IllegalStateException("SSH 执行失败 [" + server.getHost() + "]: " + e.getMessage(), e);
        } finally {
            client.stop();
        }
    }

    private OracleConfig.OracleDataSource oracleDatasource(int datasourceIndex) {
        List<OracleConfig.OracleDataSource> datasources = oracleConfig.getDatasources();
        if (datasources == null || datasourceIndex < 0 || datasourceIndex >= datasources.size()) {
            throw new IllegalArgumentException("数据源不存在: " + datasourceIndex);
        }
        OracleConfig.OracleDataSource datasource = datasources.get(datasourceIndex);
        if (!"ORACLE".equals((datasource.getType() == null ? "ORACLE" : datasource.getType()).toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("SSH/RMAN 清理仅支持 Oracle 数据源");
        }
        return datasource;
    }

    static String extractOracleHost(String jdbcUrl) {
        Matcher matcher = ORACLE_JDBC_HOST.matcher(jdbcUrl == null ? "" : jdbcUrl);
        if (!matcher.find()) throw new IllegalArgumentException("无法从 Oracle JDBC URL 提取主机地址");
        return matcher.group(1).trim();
    }

    private static String abbreviate(String value, int maxLength) {
        if (value == null) return "";
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }

    public static final class CommandResult {
        private final int exitCode;
        private final String stdout;
        private final String stderr;

        CommandResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        public int getExitCode() { return exitCode; }
        public String getStdout() { return stdout; }
        public String getStderr() { return stderr; }
        public String combinedOutput() {
            return (stdout == null ? "" : stdout) + (stderr == null || stderr.isEmpty() ? "" : "\n" + stderr);
        }
    }
}
