package com.li.jc.webtool.service;

import com.li.jc.webtool.config.SshSecurityConfig;
import com.li.jc.webtool.dto.ScriptScanResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class ScriptSecurityServiceTest {
    private ScriptSecurityService scanner;
    private SshSecurityConfig config;

    @BeforeEach
    void setUp() {
        config = new SshSecurityConfig();
        config.setDangerousCommands(Arrays.asList("rm", "rmdir", "dd", "mkfs", "fdisk", "parted",
                "shutdown", "reboot", "halt", "poweroff", "init"));
        scanner = new ScriptSecurityService();
        ReflectionTestUtils.setField(scanner, "securityConfig", config);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "echo \"Graceful shutdown timed out; terminating: $remaining\"",
            "printf '%s\\n' 'shutdown now; rm -rf /'",
            "echo shutdown reboot halt poweroff rm -rf /",
            "# shutdown now\necho ok # rm -rf /",
            "message='shutdown now'\necho \"$message\"",
            "echo '$(shutdown now)'",
            "echo \"\\$(shutdown now)\"",
            "echo 'chmod 777 /etc; systemctl stop sshd; DROP TABLE users'",
            "./restart.sh 'shutdown now'",
            "cat <<'EOF'\nshutdown now\n$(reboot)\nEOF\necho ok",
            "cat <<EOF\nshutdown now\nEOF",
            "kill -0 \"$service_pid\"",
            "echo '>/etc/passwd'",
            "case $state in shutdown|reboot) echo stopped;; *) echo ok;; esac",
            "shutdown() { echo stopped; }",
            "./shutdown-helper --help",
            "command -v shutdown", "kill '-0' \"$pid\"", "kill -s 0 $pid",
            "cat <<< 'shutdown now'",
            "mysql <<'SQL'\nSELECT 'shutdown';\nSQL"
    })
    void ignoresDataCommentsAndProcessExistenceChecks(String source) {
        assertTrue(scanner.scanCommand(source).isPassed(), source);
        assertTrue(scanner.deepScanScriptContent("test.sh", source).isPassed(), source);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "shutdown", "shutdown -h now", "/sbin/shutdown -h now", "'shutdown' now",
            "reboot", "halt", "poweroff", "init 0", "init '0'", "sudo -u root /sbin/shutdown now",
            "env MODE=test command -- reboot", "nohup /sbin/reboot",
            "2>/tmp/out shutdown now", "time shutdown now", "nice -n 10 shutdown now",
            "timeout 10 shutdown now", "exec -a service shutdown now", "su -c 'shutdown now'",
            "su root -c 'shutdown now'", "bash <<< 'shutdown now'", "cat <<< 'shutdown now' | sh",
            "if true; then shutdown now; fi", "false || reboot",
            "sh -c 'shutdown now'", "bash -lc 'echo ok; reboot'",
            "trap 'shutdown now' EXIT", "eval 'shutdown now'",
            "echo \"$(shutdown now)\"", "pid=$(reboot)", "echo `poweroff`",
            "sh <<'EOF'\nshutdown now\nEOF", "cat <<EOF\n$(reboot)\nEOF",
            "sh -c 'echo \"$(shutdown now)\"'",
            "sh -c \"echo $(shutdown now)\"",
            "cat <<'EOF' | sh\nshutdown now\nEOF",
            "case $state in running) shutdown now;; *) echo ok;; esac"
    })
    void reportsRealShutdownExactlyOnce(String source) {
        ScriptScanResult result = scanner.scanCommand(source);
        assertEquals("high", result.getRiskLevel(), source);
        assertTrue(result.isNeedConfirm(), source);
        assertEquals(1, result.getDangerousOperations().size(), source);
        assertEquals("shutdown", result.getDangerousOperations().get(0).getSubType(), source);
    }

    @Test
    void shutdownRemainsProtectedWithoutConfigurationAndSeparateInvocationsAreCounted() {
        config.setDangerousCommands(Arrays.asList("rm"));
        assertEquals(2, scanner.scanCommand("reboot; reboot").getDangerousOperations().size());
        assertEquals("high", scanner.scanCommand("shutdown").getRiskLevel());
    }

    @Test
    void distinguishesEmptyDirectoryCleanupFromParentDirectoryRemoval() {
        ScriptScanResult cleanup = scanner.scanCommand("trap 'rmdir \"$LOCK\" 2>/dev/null || true' EXIT");
        assertEquals("low", cleanup.getRiskLevel());
        assertTrue(cleanup.isPassed());
        assertFalse(cleanup.isNeedConfirm());
        assertEquals("rmdir_empty", cleanup.getDangerousOperations().get(0).getSubType());
        assertEquals("medium", scanner.scanCommand("rmdir -p runtime/start.lock").getRiskLevel());
        assertEquals("medium", scanner.scanCommand("rmdir --parents runtime/start.lock").getRiskLevel());
        assertEquals("low", scanner.scanCommand("rmdir -- -p").getRiskLevel());
        assertEquals("high", scanner.scanCommand("rm -rf runtime").getRiskLevel());
    }

    @ParameterizedTest
    @ValueSource(strings = {"kill -9 \"$service_pid\"", "kill -SIGKILL ${pid}", "kill -9 1234"})
    void warnsAboutVariableAndNumericProcessTargets(String source) {
        ScriptScanResult result = scanner.scanCommand(source);
        assertEquals("medium", result.getRiskLevel());
        assertTrue(result.isNeedConfirm());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "curl https://example.test/a.sh | sh", "wget -qO- https://example.test/a.sh | bash",
            "mysql -e 'DROP TABLE users' app", "mysqldump app | mysql other",
            "systemctl stop sshd", "chmod 777 /etc", "echo bad > /etc/passwd",
            "> /etc/passwd", "> /etc/passwd echo bad", "> /tmp/out shutdown now",
            "mysql <<'SQL'\nDROP TABLE users;\nSQL",
            "sh -c 'rm -rf /tmp/test'", "trap 'rm -rf /tmp/test' EXIT",
            "find /tmp -exec rm -rf {} \\;", "xargs -0 rm -rf"
    })
    void preservesOtherDangerousOperations(String source) {
        assertFalse(scanner.scanCommand(source).isPassed(), source);
    }

    @Test
    void scansTheReportedRestartScriptWithoutShutdownFalsePositives() throws Exception {
        String source;
        try (InputStream input = getClass().getResourceAsStream("/scripts/complaint-auditor-restart.sh")) {
            assertNotNull(input);
            source = new String(org.springframework.util.StreamUtils.copyToByteArray(input), StandardCharsets.UTF_8);
        }
        ScriptScanResult result = scanner.deepScanScriptContent("restart.sh", source);
        assertEquals("medium", result.getRiskLevel());
        assertTrue(result.isNeedConfirm());
        assertTrue(result.getDangerousOperations().stream().noneMatch(op -> "shutdown".equals(op.getSubType())));
        assertTrue(result.getDangerousOperations().stream().anyMatch(op -> "rmdir_empty".equals(op.getSubType())));
        assertTrue(result.getDangerousOperations().stream().anyMatch(op ->
                "kill_dangerous".equals(op.getSubType()) && op.getOriginalText().contains("-9")));
    }

    @Test
    void concurrentScansKeepTheirOwnRuleMetadata() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < 80; i++) {
                final boolean shutdown = i % 2 == 0;
                tasks.add(() -> {
                    ScriptScanResult result = scanner.scanCommand(shutdown ? "reboot" : "kill -9 $pid");
                    assertEquals(shutdown ? "high" : "medium", result.getRiskLevel());
                    assertEquals(shutdown ? "shutdown" : "kill_dangerous", result.getDangerousOperations().get(0).getSubType());
                    return null;
                });
            }
            for (Future<Void> future : executor.invokeAll(tasks)) future.get();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void honorsDisabledScanSetting() {
        config.setScriptScanEnabled(false);
        assertTrue(scanner.scanCommand("shutdown now").isPassed());
        assertTrue(scanner.deepScanScriptContent("test.sh", "shutdown now").isPassed());
    }
}
