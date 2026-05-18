package com.gxcj.xjtool.service;

import com.gxcj.xjtool.model.PressureResult;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 压测执行服务
 */
public class PressureTestService {

    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 压测任务结果回调接口
     */
    public interface ProgressCallback {
        void onProgress(int completed, int total, PressureResult.PerformanceMetrics metrics);
        void onComplete(PressureResult result);
        void onError(String message);
    }

    /**
     * 启动压测任务
     */
    public Future<PressureResult> startPressureTest(
            PressureResult.TestConfig config,
            ProgressCallback callback) {

        ExecutorService executor = Executors.newCachedThreadPool();
        CompletableFuture<PressureResult> future = CompletableFuture.supplyAsync(() -> {

            // 初始化统计变量
            AtomicInteger completedCount = new AtomicInteger(0);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failCount = new AtomicInteger(0);
            AtomicLong totalResponseTime = new AtomicLong(0);
            AtomicLong minResponseTime = new AtomicLong(Long.MAX_VALUE);
            AtomicLong maxResponseTime = new AtomicLong(0);

            // 收集响应时间用于计算百分位
            List<Long> responseTimes = Collections.synchronizedList(new ArrayList<>());
            List<String> errorMessages = Collections.synchronizedList(new ArrayList<>());

            // 预估总请求数
            final int totalRequestsFinal = config.getTotalRequests() != null ? config.getTotalRequests() : Integer.MAX_VALUE;
            long startTime = System.currentTimeMillis();

            // 计算预估时长（如果使用持续时间模式）
            final long endTimeFinal = config.getDurationSeconds() != null && config.getDurationSeconds() > 0
                    ? startTime + config.getDurationSeconds() * 1000L
                    : Long.MAX_VALUE;

            // 创建线程池执行并发请求
            final int concurrency = Math.min(config.getConcurrency(), 100);
            Semaphore semaphore = new Semaphore(concurrency);

            // QPS 限流器
            final RateLimiter rateLimiter = config.getQpsLimit() > 0
                    ? new RateLimiter(config.getQpsLimit())
                    : null;

            RestTemplate restTemplate = new RestTemplate();
            HttpHeaders defaultHeaders = new HttpHeaders();
            if (config.getHeaders() != null) {
                config.getHeaders().forEach(defaultHeaders::add);
            }

            // 异步执行压测
            for (int i = 0; i < concurrency; i++) {
                executor.submit(() -> {
                    try {
                        while (completedCount.get() < totalRequestsFinal && System.currentTimeMillis() < endTimeFinal) {
                            semaphore.acquire();

                            // QPS 限流
                            if (rateLimiter != null) {
                                rateLimiter.acquire();
                            }

                            // 延迟
                            if (config.getDelayMs() > 0) {
                                Thread.sleep(config.getDelayMs());
                            }

                            // 执行请求
                            long reqStart = System.currentTimeMillis();
                            try {
                                HttpEntity<String> entity = new HttpEntity<>(config.getBody(), defaultHeaders);
                                ResponseEntity<String> response = restTemplate.exchange(
                                        config.getUrl(),
                                        HttpMethod.valueOf(config.getMethod().toUpperCase()),
                                        entity,
                                        String.class
                                );
                                long respTime = System.currentTimeMillis() - reqStart;

                                successCount.incrementAndGet();
                                totalResponseTime.addAndGet(respTime);
                                updateMinMax(minResponseTime, maxResponseTime, respTime);
                                responseTimes.add(respTime);

                            } catch (Exception e) {
                                long respTime = System.currentTimeMillis() - reqStart;
                                failCount.incrementAndGet();
                                totalResponseTime.addAndGet(respTime);
                                updateMinMax(minResponseTime, maxResponseTime, respTime);
                                errorMessages.add(e.getMessage());
                                responseTimes.add(respTime);
                            }

                            int completed = completedCount.incrementAndGet();
                            semaphore.release();

                            // 推送进度（每10个请求或每100ms推送一次）
                            if (completed % 10 == 0 || completed <= 5) {
                                pushProgress(callback, completed, totalRequestsFinal, successCount, failCount,
                                        totalResponseTime, minResponseTime, maxResponseTime, responseTimes,
                                        startTime, config, endTimeFinal);
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            // 等待所有请求完成
            try {
                // 根据模式等待
                if (config.getDurationSeconds() != null && config.getDurationSeconds() > 0) {
                    Thread.sleep(config.getDurationSeconds() * 1000L + 500);
                } else {
                    // 轮询等待完成
                    int maxWait = 600; // 最多等待60秒
                    while (completedCount.get() < config.getTotalRequests() && maxWait > 0) {
                        Thread.sleep(100);
                        maxWait--;
                        if (Thread.currentThread().isInterrupted()) break;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            long actualEndTime = System.currentTimeMillis();

            // 计算最终指标
            return buildFinalResult(config, completedCount.get(), successCount.get(), failCount.get(),
                    totalResponseTime.get(), minResponseTime.get(), maxResponseTime.get(),
                    responseTimes, errorMessages, startTime, actualEndTime);

        }, executor);

        // 添加完成回调
        future.thenAccept(result -> {
            if (callback != null) {
                callback.onComplete(result);
            }
            executor.shutdown();
        }).exceptionally(ex -> {
            if (callback != null) {
                callback.onError(ex.getMessage());
            }
            executor.shutdown();
            return null;
        });

        return future;
    }

    /**
     * 停止压测任务
     */
    public void stopPressureTest(Future<PressureResult> future) {
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
    }

    private void pushProgress(ProgressCallback callback, int completed, int total,
                              AtomicInteger successCount, AtomicInteger failCount,
                              AtomicLong totalResponseTime, AtomicLong minResponseTime,
                              AtomicLong maxResponseTime, List<Long> responseTimes,
                              long startTime, PressureResult.TestConfig config, long endTime) {
        if (callback == null) return;

        int success = successCount.get();
        int fail = failCount.get();
        int totalReq = success + fail;

        double avgTime = totalReq > 0 ? (double) totalResponseTime.get() / totalReq : 0;
        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
        double qps = elapsed > 0 ? (double) success / elapsed : 0;

        PressureResult.PerformanceMetrics metrics = new PressureResult.PerformanceMetrics();
        metrics.setSuccessCount(success);
        metrics.setFailCount(fail);
        metrics.setSuccessRate(totalReq > 0 ? (double) success * 100 / totalReq : 0);
        metrics.setFailRate(totalReq > 0 ? (double) fail * 100 / totalReq : 0);
        metrics.setAvgResponseTime(avgTime);
        metrics.setMinResponseTime(minResponseTime.get() == Long.MAX_VALUE ? 0 : minResponseTime.get());
        metrics.setMaxResponseTime(maxResponseTime.get());
        metrics.setQps(qps);
        metrics.setResponseTimeDistribution(buildDistribution(responseTimes));

        callback.onProgress(completed, total, metrics);
    }

    private PressureResult buildFinalResult(PressureResult.TestConfig config,
                                            int completed, int success, int fail,
                                            long totalTime, long minTime, long maxTime,
                                            List<Long> responseTimes, List<String> errorMessages,
                                            long startTime, long endTime) {

        PressureResult result = new PressureResult();
        result.setConfig(config);
        result.setCompleted(true);

        // 测试摘要
        PressureResult.TestSummary summary = new PressureResult.TestSummary();
        summary.setStartTime(LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(startTime),
                java.time.ZoneId.systemDefault()).format(DF));
        summary.setEndTime(LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(endTime),
                java.time.ZoneId.systemDefault()).format(DF));
        summary.setDurationSeconds((endTime - startTime) / 1000);
        summary.setTotalRequests(config.getTotalRequests() != null ? config.getTotalRequests() : completed);
        summary.setCompletedRequests(completed);
        result.setSummary(summary);

        // 性能指标
        PressureResult.PerformanceMetrics metrics = new PressureResult.PerformanceMetrics();
        metrics.setSuccessCount(success);
        metrics.setFailCount(fail);
        metrics.setSuccessRate(completed > 0 ? (double) success * 100 / completed : 0);
        metrics.setFailRate(completed > 0 ? (double) fail * 100 / completed : 0);
        metrics.setAvgResponseTime(completed > 0 ? (double) totalTime / completed : 0);
        metrics.setMinResponseTime(minTime == Long.MAX_VALUE ? 0 : minTime);
        metrics.setMaxResponseTime(maxTime);
        metrics.setQps(summary.getDurationSeconds() > 0
                ? (double) success / summary.getDurationSeconds() : 0);
        metrics.setErrorMessages(errorMessages.size() > 10 ? errorMessages.subList(0, 10) : errorMessages);
        metrics.setResponseTimeDistribution(buildDistribution(responseTimes));

        // 计算百分位（先复制副本再排序，避免并发修改异常）
        if (!responseTimes.isEmpty()) {
            List<Long> sortedCopy = new ArrayList<>(responseTimes);
            Collections.sort(sortedCopy);
            metrics.setTp90(getPercentile(sortedCopy, 90));
            metrics.setTp95(getPercentile(sortedCopy, 95));
            metrics.setTp99(getPercentile(sortedCopy, 99));
        } else {
            metrics.setTp90(0);
            metrics.setTp95(0);
            metrics.setTp99(0);
        }

        result.setMetrics(metrics);

        // 分析结论
        result.setAnalysis(analyzeResult(result));

        return result;
    }

    private PressureResult.AnalysisResult analyzeResult(PressureResult result) {
        PressureResult.AnalysisResult analysis = new PressureResult.AnalysisResult();
        List<String> conclusions = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();

        PressureResult.PerformanceMetrics m = result.getMetrics();

        // 成功率分析
        String stabilityLevel;
        if (m.getSuccessRate() >= 99) {
            stabilityLevel = "优秀";
            conclusions.add(String.format("✓ 成功率 %.2f%%，系统稳定性表现优秀", m.getSuccessRate()));
        } else if (m.getSuccessRate() >= 95) {
            stabilityLevel = "良好";
            conclusions.add(String.format("✓ 成功率 %.2f%%，系统稳定性良好", m.getSuccessRate()));
            suggestions.add("建议关注失败请求，分析错误日志定位问题原因");
        } else if (m.getSuccessRate() >= 80) {
            stabilityLevel = "一般";
            conclusions.add(String.format("⚠ 成功率 %.2f%%，存在一定失败率", m.getSuccessRate()));
            suggestions.add("建议检查服务端稳定性，观察错误日志定位问题原因");
        } else {
            stabilityLevel = "差";
            conclusions.add(String.format("✗ 成功率 %.2f%%，系统稳定性较差", m.getSuccessRate()));
            suggestions.add("【紧急】成功率过低，建议立即检查服务端状态和日志");
        }
        analysis.setStabilityLevel(stabilityLevel);

        // 响应时间分析
        String performanceLevel;
        if (m.getAvgResponseTime() < 200) {
            performanceLevel = "优秀";
            conclusions.add(String.format("✓ 平均响应时间 %.2fms，性能表现优秀", m.getAvgResponseTime()));
        } else if (m.getAvgResponseTime() < 500) {
            performanceLevel = "良好";
            conclusions.add(String.format("✓ 平均响应时间 %.2fms，性能表现良好", m.getAvgResponseTime()));
        } else if (m.getAvgResponseTime() < 1000) {
            performanceLevel = "一般";
            conclusions.add(String.format("⚠ 平均响应时间 %.2fms，响应时间偏高", m.getAvgResponseTime()));
            suggestions.add("响应时间偏高，建议进行性能分析和优化");
        } else {
            performanceLevel = "差";
            conclusions.add(String.format("✗ 平均响应时间 %.2fms，性能较差", m.getAvgResponseTime()));
            suggestions.add("【严重】响应时间过长，建议优化接口性能或增加缓存");
        }
        analysis.setPerformanceLevel(performanceLevel);

        // TP99 分析
        if (m.getTp99() > 0) {
            if (m.getTp99() < 500) {
                conclusions.add(String.format("✓ TP99 为 %dms，满足高并发场景需求", m.getTp99()));
            } else if (m.getTp99() < 1000) {
                conclusions.add(String.format("⚠ TP99 为 %dms，存在一定延迟波动", m.getTp99()));
            } else {
                conclusions.add(String.format("✗ TP99 为 %dms，高并发下延迟严重", m.getTp99()));
                suggestions.add("TP99 延迟较高，建议优化慢查询或增加异步处理");
            }
        }

        // QPS 分析
        conclusions.add(String.format("✓ 实际 QPS 约 %.1f req/s", m.getQps()));

        // 综合评级（成功率低于80%直接判定为差，无论性能多好）
        if ("差".equals(stabilityLevel)) {
            analysis.setOverallLevel("差");
            suggestions.add(0, "【紧急】系统性能不达标，建议优化后再投入使用");
        } else {
            int score = 0;
            if ("优秀".equals(stabilityLevel)) score += 3;
            else if ("良好".equals(stabilityLevel)) score += 2;
            else if ("一般".equals(stabilityLevel)) score += 1;

            if ("优秀".equals(performanceLevel)) score += 3;
            else if ("良好".equals(performanceLevel)) score += 2;
            else if ("一般".equals(performanceLevel)) score += 1;

            String overallLevel;
            if (score >= 5) {
                overallLevel = "优秀";
                suggestions.add(0, "当前接口性能良好，可直接投入使用");
            } else if (score >= 3) {
                overallLevel = "良好";
            } else if (score >= 2) {
                overallLevel = "一般";
            } else {
                overallLevel = "差";
                suggestions.add(0, "【紧急】系统性能不达标，建议优化后再投入使用");
            }
            analysis.setOverallLevel(overallLevel);
        }

        // 默认建议
        if (suggestions.isEmpty()) {
            suggestions.add("建议持续监控接口响应时间和成功率变化");
        }

        analysis.setConclusions(conclusions);
        analysis.setSuggestions(suggestions);

        return analysis;
    }

    private long getPercentile(List<Long> sortedValues, int percentile) {
        if (sortedValues.isEmpty()) return 0;
        int index = (int) Math.ceil(percentile / 100.0 * sortedValues.size()) - 1;
        index = Math.max(0, Math.min(index, sortedValues.size() - 1));
        return sortedValues.get(index);
    }

    private void updateMinMax(AtomicLong min, AtomicLong max, long value) {
        long currentMin, currentMax;
        do {
            currentMin = min.get();
            if (value >= currentMin) break;
        } while (!min.compareAndSet(currentMin, value));

        do {
            currentMax = max.get();
            if (value <= currentMax) break;
        } while (!max.compareAndSet(currentMax, value));
    }

    private Map<String, Integer> buildDistribution(List<Long> responseTimes) {
        Map<String, Integer> dist = new LinkedHashMap<>();
        dist.put("<100ms", 0);
        dist.put("100-300ms", 0);
        dist.put("300-500ms", 0);
        dist.put("500ms-1s", 0);
        dist.put("1s-3s", 0);
        dist.put(">3s", 0);

        // 复制副本再遍历，避免并发修改异常
        List<Long> copy = new ArrayList<>(responseTimes);
        for (Long time : copy) {
            if (time < 100) dist.put("<100ms", dist.get("<100ms") + 1);
            else if (time < 300) dist.put("100-300ms", dist.get("100-300ms") + 1);
            else if (time < 500) dist.put("300-500ms", dist.get("300-500ms") + 1);
            else if (time < 1000) dist.put("500ms-1s", dist.get("500ms-1s") + 1);
            else if (time < 3000) dist.put("1s-3s", dist.get("1s-3s") + 1);
            else dist.put(">3s", dist.get(">3s") + 1);
        }
        return dist;
    }

    /**
     * 简单的令牌桶限流器
     */
    private static class RateLimiter {
        private final long intervalNanos;
        private volatile long nextMicros;

        public RateLimiter(int qps) {
            this.intervalNanos = 1_000_000_000L / qps;
            this.nextMicros = System.nanoTime();
        }

        public void acquire() {
            long now = System.nanoTime();
            synchronized (this) {
                while (now < nextMicros) {
                    try {
                        wait((nextMicros - now + 999_999) / 1_000_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    now = System.nanoTime();
                }
                nextMicros += intervalNanos;
            }
        }
    }
}
