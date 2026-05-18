package com.gxcj.xjtool.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * SQL 执行结果响应 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SqlResultResponse {
    /**
     * 是否执行成功
     */
    private boolean success;

    /**
     * 错误信息（失败时）
     */
    private String errorMessage;

    /**
     * 列名列表
     */
    private List<String> columns;

    /**
     * 数据行（每行是一个 Map）
     */
    private List<Map<String, Object>> rows;

    /**
     * 返回的行数
     */
    private int rowCount;

    /**
     * 执行耗时（毫秒）
     */
    private long executionTime;

    /**
     * SQL 类型（SELECT/UPDATE/INSERT/DELETE 等）
     */
    private String sqlType;

    /**
     * 影响的行数（UPDATE/INSERT/DELETE 时）
     */
    private int affectedRows;

    /**
     * 是否需要二次确认（用于危险 SQL）
     */
    private boolean needConfirm;

    /**
     * 危险原因国际化 key（前端展示用）
     */
    private String reasonKey;

    /**
     * 处置建议国际化 key（前端展示用）
     */
    private String suggestionKey;

    /**
     * 处置建议文本（后端回传，供前端展示）
     */
    private String suggestion;

    /**
     * 是否是批量执行结果
     */
    private boolean isBatch;

    /**
     * 批量执行的多个结果集（供前端多标签页展示）
     */
    private List<SqlResultResponse> multiResults;

    /**
     * 对应的执行 SQL 语句（批量执行时标识是哪一条）
     */
    private String sql;

    /**
     * 分页：数据总行数（用于服务端分页时显示总页码）
     */
    @Builder.Default
    private long totalCount = -1;

    /**
     * 分页：总页数（服务端分页时由后端计算并返回）
     */
    @Builder.Default
    private int totalPages = 0;

    /**
     * 分页：当前页码
     */
    @Builder.Default
    private int currentPage = 1;

    /**
     * 分页：每页行数
     */
    @Builder.Default
    private int pageSize = 0;

    /**
     * 创建成功响应
     */
    public static SqlResultResponse success(List<String> columns, List<Map<String, Object>> rows, long executionTime) {
        return SqlResultResponse.builder()
                .success(true)
                .columns(columns)
                .rows(rows)
                .rowCount(rows != null ? rows.size() : 0)
                .executionTime(executionTime)
                .sqlType("SELECT")
                .build();
    }

    /**
     * 创建更新类型的成功响应
     */
    public static SqlResultResponse successUpdate(int affectedRows, String sqlType, long executionTime) {
        return SqlResultResponse.builder()
                .success(true)
                .affectedRows(affectedRows)
                .sqlType(sqlType)
                .executionTime(executionTime)
                .build();
    }

    /**
     * 创建失败响应
     */
    public static SqlResultResponse error(String errorMessage) {
        return SqlResultResponse.builder()
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }

    /**
     * 创建需要确认的响应
     */
    public static SqlResultResponse confirmationNeeded(String message, String reasonKey, String suggestionKey) {
        return confirmationNeeded(message, reasonKey, suggestionKey, null);
    }

    public static SqlResultResponse confirmationNeeded(String message, String reasonKey, String suggestionKey, String suggestion) {
        return SqlResultResponse.builder()
                .success(false)
                .needConfirm(true)
                .errorMessage(message)
                .reasonKey(reasonKey)
                .suggestionKey(suggestionKey)
                .suggestion(suggestion)
                .build();
    }

    public static SqlResultResponse confirmationNeeded(String message) {
        return confirmationNeeded(message, null, null, null);
    }
}
