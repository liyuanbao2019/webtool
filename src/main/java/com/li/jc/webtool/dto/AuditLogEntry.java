package com.li.jc.webtool.dto;

import java.time.LocalDateTime;

/**
 * 审计日志条目DTO
 * 用于API返回统一的审计日志结构
 */
public class AuditLogEntry {

    /** 日志类型: SSH_CONNECTION / SSH_DISCONNECTION / SSH_COMMAND / SQL_EXECUTION */
    private String type;

    /** 操作用户名 */
    private String username;

    /** 操作时间 */
    private LocalDateTime timestamp;

    /** 目标主机 (SSH) */
    private String targetHost;

    /** 目标端口 (SSH) */
    private Integer targetPort;

    /** 目标用户 (SSH) */
    private String targetUser;

    /** 会话ID (SSH) */
    private String sessionId;

    /** 连接时长，毫秒 (SSH断开) */
    private Long connectionTimeMs;

    /** 连接状态: SUCCESS / FAILURE (SSH连接) */
    private String status;

    /** 错误信息 (失败时) */
    private String errorMessage;

    /** SQL语句 (SQL) */
    private String sql;

    /** 数据源名称 (SQL) */
    private String datasourceName;

    /** 执行耗时，毫秒 (SQL) */
    private Long executionTimeMs;

    /** 影响行数 (SQL) */
    private Integer affectedRows;

    /** 原始日志行内容 */
    private String rawLine;

    // Builder
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final AuditLogEntry entry = new AuditLogEntry();

        public Builder type(String type) { entry.type = type; return this; }
        public Builder username(String username) { entry.username = username; return this; }
        public Builder timestamp(LocalDateTime timestamp) { entry.timestamp = timestamp; return this; }
        public Builder targetHost(String targetHost) { entry.targetHost = targetHost; return this; }
        public Builder targetPort(Integer targetPort) { entry.targetPort = targetPort; return this; }
        public Builder targetUser(String targetUser) { entry.targetUser = targetUser; return this; }
        public Builder sessionId(String sessionId) { entry.sessionId = sessionId; return this; }
        public Builder connectionTimeMs(Long connectionTimeMs) { entry.connectionTimeMs = connectionTimeMs; return this; }
        public Builder status(String status) { entry.status = status; return this; }
        public Builder errorMessage(String errorMessage) { entry.errorMessage = errorMessage; return this; }
        public Builder sql(String sql) { entry.sql = sql; return this; }
        public Builder datasourceName(String datasourceName) { entry.datasourceName = datasourceName; return this; }
        public Builder executionTimeMs(Long executionTimeMs) { entry.executionTimeMs = executionTimeMs; return this; }
        public Builder affectedRows(Integer affectedRows) { entry.affectedRows = affectedRows; return this; }
        public Builder rawLine(String rawLine) { entry.rawLine = rawLine; return this; }

        public AuditLogEntry build() {
            return entry;
        }
    }

    // Getters & Setters
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
    public String getTargetHost() { return targetHost; }
    public void setTargetHost(String targetHost) { this.targetHost = targetHost; }
    public Integer getTargetPort() { return targetPort; }
    public void setTargetPort(Integer targetPort) { this.targetPort = targetPort; }
    public String getTargetUser() { return targetUser; }
    public void setTargetUser(String targetUser) { this.targetUser = targetUser; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public Long getConnectionTimeMs() { return connectionTimeMs; }
    public void setConnectionTimeMs(Long connectionTimeMs) { this.connectionTimeMs = connectionTimeMs; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getSql() { return sql; }
    public void setSql(String sql) { this.sql = sql; }
    public String getDatasourceName() { return datasourceName; }
    public void setDatasourceName(String datasourceName) { this.datasourceName = datasourceName; }
    public Long getExecutionTimeMs() { return executionTimeMs; }
    public void setExecutionTimeMs(Long executionTimeMs) { this.executionTimeMs = executionTimeMs; }
    public Integer getAffectedRows() { return affectedRows; }
    public void setAffectedRows(Integer affectedRows) { this.affectedRows = affectedRows; }
    public String getRawLine() { return rawLine; }
    public void setRawLine(String rawLine) { this.rawLine = rawLine; }
}
