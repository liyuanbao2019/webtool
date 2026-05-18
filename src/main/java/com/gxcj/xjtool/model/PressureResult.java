package com.gxcj.xjtool.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 压测结果数据模型
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PressureResult {

    /** 测试配置信息 */
    private TestConfig config;

    /** 测试摘要 */
    private TestSummary summary;

    /** 性能指标 */
    private PerformanceMetrics metrics;

    /** 分析结论 */
    private AnalysisResult analysis;

    /** 测试是否完成 */
    private boolean completed;

    /** 错误信息 */
    private String errorMessage;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TestConfig {
        /** 目标URL */
        private String url;
        /** 请求方法 */
        private String method;
        /** 并发数 */
        private int concurrency;
        /** 总请求数（请求数模式） */
        private Integer totalRequests;
        /** 持续时间秒数（时长模式） */
        private Integer durationSeconds;
        /** QPS限制（0表示不限制） */
        private int qpsLimit;
        /** 请求延迟（毫秒） */
        private int delayMs;
        /** 请求头 */
        private Map<String, String> headers;
        /** 请求体 */
        private String body;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TestSummary {
        /** 开始时间 */
        private String startTime;
        /** 结束时间 */
        private String endTime;
        /** 测试时长（秒） */
        private long durationSeconds;
        /** 总请求数 */
        private int totalRequests;
        /** 实际完成请求数 */
        private int completedRequests;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PerformanceMetrics {
        /** 成功数 */
        private int successCount;
        /** 失败数 */
        private int failCount;
        /** 成功率 (%) */
        private double successRate;
        /** 失败率 (%) */
        private double failRate;
        /** 平均响应时间 (ms) */
        private double avgResponseTime;
        /** 最小响应时间 (ms) */
        private long minResponseTime;
        /** 最大响应时间 (ms) */
        private long maxResponseTime;
        /** TP90 响应时间 (ms) */
        private long tp90;
        /** TP95 响应时间 (ms) */
        private long tp95;
        /** TP99 响应时间 (ms) */
        private long tp99;
        /** 每秒请求数 (QPS) */
        private double qps;
        /** 错误信息列表 */
        private List<String> errorMessages;
        /** 响应时间分布 (用于图表) */
        private Map<String, Integer> responseTimeDistribution;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AnalysisResult {
        /** 总体评级：优秀/良好/一般/差 */
        private String overallLevel;
        /** 性能评级 */
        private String performanceLevel;
        /** 稳定性评级 */
        private String stabilityLevel;
        /** 综合评级 */
        private String concurrencyLevel;
        /** 分析结论列表 */
        private List<String> conclusions;
        /** 优化建议列表 */
        private List<String> suggestions;
    }

    /**
     * 创建空的压测结果
     */
    public static PressureResult empty() {
        PressureResult result = new PressureResult();
        result.setCompleted(false);
        return result;
    }

    /**
     * 创建错误结果
     */
    public static PressureResult error(String message) {
        PressureResult result = new PressureResult();
        result.setCompleted(true);
        result.setErrorMessage(message);
        return result;
    }
}
