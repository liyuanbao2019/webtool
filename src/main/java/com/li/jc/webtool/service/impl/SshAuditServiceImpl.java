package com.li.jc.webtool.service.impl;

import com.li.jc.webtool.service.SshAuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSH审计日志服务实现类
 * 记录SSH连接和操作的详细日志，便于安全审计和问题追踪
 */
@Service
public class SshAuditServiceImpl implements SshAuditService {

    private static final Logger log = LoggerFactory.getLogger(SshAuditServiceImpl.class);

    // SSH审计日志目录
    @Value("${audit.ssh.directory:./logs/ssh_audit}")
    private String auditLogDirectory;

    // 是否记录命令详情
    @Value("${audit.ssh.log-commands:true}")
    private boolean logCommands;

    // 是否启用SSH审计
    @Value("${audit.ssh.enabled:true}")
    private boolean enabled;

    // 日期时间格式化器
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss");

    // 记录每个会话的连接开始时间
    private final Map<String, Long> sessionStartTimes = new ConcurrentHashMap<>();

    // 记录每个会话的连接信息（用于断开时记录）
    private final Map<String, String> sessionInfoMap = new ConcurrentHashMap<>();

    // 记录每个会话的目标服务器IP（用于命令审计）
    private final Map<String, String> sessionHostMap = new ConcurrentHashMap<>();

    /**
     * 初始化：创建SSH审计日志目录
     */
    @PostConstruct
    public void init() {
        if (!enabled) {
            log.info("SSH审计功能已禁用");
            return;
        }

        try {
            Path logPath = Paths.get(auditLogDirectory);
            if (!Files.exists(logPath)) {
                Files.createDirectories(logPath);
                log.info("创建SSH审计日志目录: {}", auditLogDirectory);
            }
            log.info("SSH审计功能已启用，日志目录: {}, 命令记录: {}", auditLogDirectory, logCommands);
        } catch (IOException e) {
            log.error("创建SSH审计日志目录失败: {}", auditLogDirectory, e);
        }
    }

    @Override
    public void logConnection(String username, String sessionId, String targetHost,
            int targetPort, String targetUser, boolean success, String errorMessage) {
        if (!enabled) {
            return;
        }

        // 记录会话开始时间
        sessionStartTimes.put(sessionId, System.currentTimeMillis());

        // 保存会话信息，用于断开时记录
        String sessionInfo = String.format("%s@%s:%d", targetUser, targetHost, targetPort);
        sessionInfoMap.put(sessionId, sessionInfo);

        // 保存目标服务器IP，用于命令审计
        sessionHostMap.put(sessionId, targetHost);

        // 异步写入日志
        new Thread(() -> {
            try {
                writeConnectionLog(username, sessionId, targetHost, targetPort,
                        targetUser, success, errorMessage);
            } catch (Exception e) {
                log.error("写入SSH连接审计日志失败", e);
            }
        }).start();
    }

    @Override
    public void logDisconnection(String username, String sessionId, long connectionTime) {
        if (!enabled) {
            return;
        }

        // 计算连接时长
        Long startTime = sessionStartTimes.remove(sessionId);
        long duration = startTime != null ? (System.currentTimeMillis() - startTime) : connectionTime;

        // 获取会话信息
        String sessionInfo = sessionInfoMap.remove(sessionId);

        // 清理目标服务器IP映射
        sessionHostMap.remove(sessionId);

        // 异步写入日志
        new Thread(() -> {
            try {
                writeDisconnectionLog(username, sessionId, sessionInfo, duration);
            } catch (Exception e) {
                log.error("写入SSH断开审计日志失败", e);
            }
        }).start();
    }

    @Override
    public void logCommand(String username, String sessionId, String targetHost, String command) {
        if (!enabled || !logCommands) {
            return;
        }

        // 过滤掉纯控制字符和空命令
        if (command == null || command.trim().isEmpty() || command.length() < 2) {
            return;
        }

        // 异步写入日志
        new Thread(() -> {
            try {
                writeCommandLog(username, sessionId, targetHost, command);
            } catch (Exception e) {
                log.error("写入SSH命令审计日志失败", e);
            }
        }).start();
    }

    /**
     * 写入SSH连接日志
     */
    private void writeConnectionLog(String username, String sessionId, String targetHost,
            int targetPort, String targetUser, boolean success, String errorMessage) {
        String logFileName = String.format("ssh_audit_%s.log", sanitizeFilename(username));
        File logFile = new File(auditLogDirectory, logFileName);

        try (FileOutputStream fos = new FileOutputStream(logFile, true);
                OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
                PrintWriter printWriter = new PrintWriter(osw)) {

            StringBuilder logEntry = new StringBuilder();
            logEntry.append("\n")
                    .append("═══════════════════════════════════════════════════════════════\n")
                    .append("【SSH连接】\n")
                    .append("操作时间: ").append(LocalDateTime.now().format(DATE_TIME_FORMATTER)).append("\n")
                    .append("操作用户: ").append(username).append("\n")
                    .append("会话ID: ").append(sessionId.substring(0, Math.min(8, sessionId.length()))).append("...\n")
                    .append("目标服务器: ").append(targetUser).append("@").append(targetHost).append(":").append(targetPort)
                    .append("\n")
                    .append("连接状态: ").append(success ? "成功 ✓" : "失败 ✗").append("\n");

            if (!success && errorMessage != null) {
                logEntry.append("错误信息: ").append(errorMessage).append("\n");
            }

            printWriter.write(logEntry.toString());
            printWriter.flush();

        } catch (IOException e) {
            log.error("写入SSH连接审计日志文件失败: {}", logFile.getAbsolutePath(), e);
        }
    }

    /**
     * 写入SSH断开日志
     */
    private void writeDisconnectionLog(String username, String sessionId, String sessionInfo, long duration) {
        String logFileName = String.format("ssh_audit_%s.log", sanitizeFilename(username));
        File logFile = new File(auditLogDirectory, logFileName);

        try (FileOutputStream fos = new FileOutputStream(logFile, true);
                OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
                PrintWriter printWriter = new PrintWriter(osw)) {

            String durationStr = formatDuration(duration);

            StringBuilder logEntry = new StringBuilder();
            logEntry.append("【SSH断开】\n")
                    .append("操作时间: ").append(LocalDateTime.now().format(DATE_TIME_FORMATTER)).append("\n")
                    .append("操作用户: ").append(username).append("\n")
                    .append("会话ID: ").append(sessionId.substring(0, Math.min(8, sessionId.length()))).append("...\n");

            if (sessionInfo != null) {
                logEntry.append("目标服务器: ").append(sessionInfo).append("\n");
            }

            logEntry.append("连接时长: ").append(durationStr).append("\n");

            printWriter.write(logEntry.toString());
            printWriter.flush();

        } catch (IOException e) {
            log.error("写入SSH断开审计日志文件失败: {}", logFile.getAbsolutePath(), e);
        }
    }

    /**
     * 写入SSH命令日志
     */
    private void writeCommandLog(String username, String sessionId, String targetHost, String command) {
        String logFileName = String.format("ssh_cmd_audit_%s.log", sanitizeFilename(username));
        File logFile = new File(auditLogDirectory, logFileName);

        try (FileOutputStream fos = new FileOutputStream(logFile, true);
                OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
                PrintWriter printWriter = new PrintWriter(osw)) {

            StringBuilder logEntry = new StringBuilder();
            logEntry.append(LocalDateTime.now().format(DATE_TIME_FORMATTER))
                    .append(" | ").append(username)
                    .append(" | ").append(sessionId.substring(0, Math.min(8, sessionId.length())))
                    .append(" | ").append(targetHost != null ? targetHost : "unknown")
                    .append(" | ").append(sanitizeCommand(command))
                    .append("\n");

            printWriter.write(logEntry.toString());
            printWriter.flush();

        } catch (IOException e) {
            log.error("写入SSH命令审计日志文件失败: {}", logFile.getAbsolutePath(), e);
        }
    }

    /**
     * 清理文件名中的非法字符
     */
    private String sanitizeFilename(String filename) {
        if (filename == null) {
            return "unknown";
        }
        return filename.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    /**
     * 清理命令中的特殊字符（用于日志记录）
     */
    private String sanitizeCommand(String command) {
        if (command == null) {
            return "";
        }
        // 移除控制字符，保留可打印字符
        return command.replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "")
                .replaceAll("\n", "\\\\n")
                .replaceAll("\r", "")
                .trim();
    }

    /**
     * 格式化时长
     */
    private String formatDuration(long duration) {
        long seconds = duration / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;

        if (hours > 0) {
            return String.format("%d小时%d分钟%d秒", hours, minutes % 60, seconds % 60);
        } else if (minutes > 0) {
            return String.format("%d分钟%d秒", minutes, seconds % 60);
        } else {
            return String.format("%d秒", seconds);
        }
    }
}
