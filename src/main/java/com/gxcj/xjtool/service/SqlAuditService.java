package com.gxcj.xjtool.service;

/**
 * SQL审计日志服务接口
 * 负责记录用户的SQL操作，用于安全审计和追踪溯源
 */
public interface SqlAuditService {

    /**
     * 记录SQL执行日志
     * 
     * @param username       操作用户名
     * @param sql            SQL语句
     * @param success        是否执行成功
     * @param errorMessage   错误信息（如果失败）
     * @param executionTime  执行耗时（毫秒）
     * @param affectedRows   影响的行数
     * @param datasourceName 数据源名称
     */
    void logSqlExecution(String username, String sql, boolean success,
            String errorMessage, long executionTime,
            int affectedRows, String datasourceName);
}
