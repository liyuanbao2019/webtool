package com.li.jc.webtool.service.impl;

import com.li.jc.webtool.service.SqlAuditService;
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

/**
 * SQL审计日志服务实现类
 * 为每个用户创建独立的日志文件，记录所有SQL操作
 */
@Service
public class SqlAuditServiceImpl implements SqlAuditService {

    private static final Logger log = LoggerFactory.getLogger(SqlAuditServiceImpl.class);

    // 日志目录
    @Value("${audit.log.directory:./logs/sql_audit}")
    private String auditLogDirectory;

    // 日期时间格式化器
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 初始化：创建审计日志目录
     */
    @PostConstruct
    public void init() {
        try {
            Path logPath = Paths.get(auditLogDirectory);
            if (!Files.exists(logPath)) {
                Files.createDirectories(logPath);
                log.info("创建SQL审计日志目录: {}", auditLogDirectory);
            }
        } catch (IOException e) {
            log.error("创建审计日志目录失败: {}", auditLogDirectory, e);
        }
    }

    @Override
    public void logSqlExecution(String username, String sql, boolean success,
            String errorMessage, long executionTime,
            int affectedRows, String datasourceName) {
        // 异步写入日志，避免影响主业务性能
        new Thread(() -> {
            try {
                writeAuditLog(username, sql, success, errorMessage,
                        executionTime, affectedRows, datasourceName);
            } catch (Exception e) {
                log.error("写入审计日志失败", e);
            }
        }).start();
    }

    /**
     * 写入审计日志到文件
     */
    private void writeAuditLog(String username, String sql, boolean success,
            String errorMessage, long executionTime,
            int affectedRows, String datasourceName) {
        // 文件名：sql_audit_{username}.log
        String logFileName = String.format("sql_audit_%s.log", sanitizeFilename(username));
        File logFile = new File(auditLogDirectory, logFileName);

        try (FileOutputStream fos = new FileOutputStream(logFile, true);
                OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
                PrintWriter printWriter = new PrintWriter(osw)) {

            // 格式化日志内容
            StringBuilder logEntry = new StringBuilder();
            logEntry.append("\n")
                    .append("═══════════════════════════════════════════════════════════════\n")
                    .append("操作时间: ").append(LocalDateTime.now().format(DATE_TIME_FORMATTER)).append("\n")
                    .append("操作用户: ").append(username).append("\n")
                    .append("数据源: ").append(datasourceName != null ? datasourceName : "未知").append("\n")
                    .append("执行状态: ").append(success ? "成功 ✓" : "失败 ✗").append("\n")
                    .append("执行耗时: ").append(executionTime).append("ms\n");

            if (success) {
                logEntry.append("影响行数: ").append(affectedRows).append("\n");
            } else {
                logEntry.append("错误信息: ").append(errorMessage != null ? errorMessage : "未知错误").append("\n");
            }

            logEntry.append("SQL语句:\n")
                    .append("---\n")
                    .append(formatSql(sql)).append("\n")
                    .append("---\n");

            printWriter.write(logEntry.toString());
            printWriter.flush();

        } catch (IOException e) {
            log.error("写入审计日志文件失败: {}", logFile.getAbsolutePath(), e);
        }
    }

    /**
     * 清理文件名中的非法字符
     */
    private String sanitizeFilename(String filename) {
        if (filename == null) {
            return "unknown";
        }
        // 只保留字母、数字、下划线和连字符
        return filename.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    /**
     * 格式化SQL语句（简单的缩进处理）
     */
    private String formatSql(String sql) {
        if (sql == null) {
            return "";
        }
        // 简单格式化：在关键字前添加换行
        return sql.replaceAll(
                "(?i)\\b(SELECT|FROM|WHERE|AND|OR|ORDER BY|GROUP BY|HAVING|UNION|INSERT|UPDATE|DELETE|SET|VALUES)\\b",
                "\n$1");
    }
}
