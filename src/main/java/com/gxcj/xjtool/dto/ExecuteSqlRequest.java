package com.gxcj.xjtool.dto;

import lombok.Data;

/**
 * 执行 SQL 请求 DTO
 */
@Data
public class ExecuteSqlRequest {
    /**
     * 数据源索引
     */
    private Integer datasourceIndex;

    /**
     * 要执行的 SQL 语句
     */
    private String sql;

    /**
     * 最大返回行数（防止返回过多数据, 0表示无限制）
     */
    private Integer maxRows = 0;

    /**
     * 操作用户名（用于审计日志）
     */
    private String username;

    /**
     * 危险命令确认 Token
     */
    private String dangerousCommandToken;

    /**
     * 会话ID（用于Token验证）
     */
    private String sessionId;

    /**
     * 分页：当前页码（从1开始，0或null表示不分页）
     */
    private Integer page = 1;

    /**
     * 分页：每页行数（0或null表示不分页/取全量）
     */
    private Integer pageSize = 0;

    /**
     * Export should fetch all rows from the SQL result, without the normal default row cap.
     */
    private Boolean exportAll = false;

    /**
     * Optional client-generated execution id used by the opt-in query-cancel
     * feature. Legacy requests leave this value empty and use the original path.
     */
    private String queryId;
}

