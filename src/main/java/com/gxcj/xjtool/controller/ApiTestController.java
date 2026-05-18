package com.gxcj.xjtool.controller;

import com.gxcj.xjtool.model.PressureResult;
import com.gxcj.xjtool.service.PressureReportService;
import com.gxcj.xjtool.service.PressureTestService;
import com.gxcj.xjtool.websocket.PressureWebSocketHandler;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

/**
 * API 调测工具控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/test")
public class ApiTestController {

    @Autowired
    private PressureReportService pressureReportService;

    @Autowired
    private PressureWebSocketHandler pressureWebSocketHandler;

    // 存储正在运行的压测任务
    private final Map<String, Future<PressureResult>> runningTasks = new ConcurrentHashMap<>();

    @Data
    public static class ApiRequest {
        private String method; // GET, POST, PUT, DELETE
        private String url;
        private Map<String, String> headers;
        private String body;
    }

    @Data
    public static class ApiResponse {
        private int statusCode;
        private String statusText;
        private Map<String, String> headers;
        private String body;
        private long responseTime;
    }

    /**
     * 压测请求参数
     */
    @Data
    public static class PressureRequest {
        private String method;
        private String url;
        private Map<String, String> headers;
        private String body;
        private Integer totalRequests;      // 总请求数（请求数模式）
        private Integer durationSeconds;    // 持续时间秒数（时长模式）
        private int concurrency = 10;       // 并发数，默认10
        private int qpsLimit = 0;         // QPS限制，0表示不限制
        private int delayMs = 0;          // 请求延迟毫秒
        private String sessionId;          // WebSocket sessionId
    }

    /**
     * 代理 API 请求
     */
    @PostMapping("/proxy")
    public ResponseEntity<?> proxyRequest(@RequestBody ApiRequest request, HttpServletRequest httpRequest) {
        long startTime = System.currentTimeMillis();

        log.info("API Test Request: {} {}", request.getMethod(), request.getUrl());

        try {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(30000);
            factory.setReadTimeout(30000);
            RestTemplate restTemplate = new RestTemplate(factory);

            HttpHeaders headers = new HttpHeaders();
            if (request.getHeaders() != null) {
                request.getHeaders().forEach(headers::add);
            }

            String cookieHeader = httpRequest.getHeader("Cookie");
            if (cookieHeader != null && !cookieHeader.isEmpty()) {
                headers.add("Cookie", cookieHeader);
                log.debug("Forwarding Cookie: {}", cookieHeader);
            }

            HttpEntity<String> entity = new HttpEntity<>(request.getBody(), headers);
            HttpMethod httpMethod = HttpMethod.valueOf(request.getMethod().toUpperCase());
            ResponseEntity<String> response;

            try {
                response = restTemplate.exchange(request.getUrl(), httpMethod, entity, String.class);
            } catch (HttpStatusCodeException e) {
                long endTime = System.currentTimeMillis();

                ApiResponse apiResponse = new ApiResponse();
                apiResponse.setStatusCode(e.getRawStatusCode());
                apiResponse.setStatusText(e.getStatusText());
                apiResponse.setBody(e.getResponseBodyAsString());
                apiResponse.setResponseTime(endTime - startTime);

                Map<String, String> responseHeaders = new HashMap<>();
                if (e.getResponseHeaders() != null) {
                    e.getResponseHeaders().forEach((key, value) -> {
                        responseHeaders.put(key, String.join(", ", value));
                    });
                }
                apiResponse.setHeaders(responseHeaders);

                log.info("API Test Response: {} {} ({}ms)", e.getRawStatusCode(), e.getStatusText(), apiResponse.getResponseTime());
                return ResponseEntity.ok(apiResponse);
            }

            long endTime = System.currentTimeMillis();

            ApiResponse apiResponse = new ApiResponse();
            apiResponse.setStatusCode(response.getStatusCodeValue());
            apiResponse.setStatusText(response.getStatusCode().getReasonPhrase());
            apiResponse.setBody(response.getBody());
            apiResponse.setResponseTime(endTime - startTime);

            Map<String, String> responseHeaders = new HashMap<>();
            response.getHeaders().forEach((key, value) -> {
                responseHeaders.put(key, String.join(", ", value));
            });
            apiResponse.setHeaders(responseHeaders);

            log.info("API Test Response: {} {} ({}ms)", apiResponse.getStatusCode(), apiResponse.getStatusText(), apiResponse.getResponseTime());
            return ResponseEntity.ok(apiResponse);

        } catch (Exception e) {
            long endTime = System.currentTimeMillis();

            log.error("API Test Error: {}", e.getMessage(), e);

            ApiResponse errorResponse = new ApiResponse();
            errorResponse.setStatusCode(0);
            errorResponse.setStatusText("Error");
            errorResponse.setBody("请求失败: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            errorResponse.setResponseTime(endTime - startTime);
            errorResponse.setHeaders(new HashMap<>());

            return ResponseEntity.ok(errorResponse);
        }
    }

    /**
     * 启动压测任务
     */
    @PostMapping("/pressure/start")
    public ResponseEntity<?> startPressureTest(@RequestBody PressureRequest request) {
        String sessionId = request.getSessionId();

        log.info("启动压测任务: {} {} 并发={} sessionId={}",
                request.getMethod(), request.getUrl(), request.getConcurrency(), sessionId);

        try {
            // 验证参数
            if (request.getUrl() == null || request.getUrl().isEmpty()) {
                Map<String, Object> result = new HashMap<>();
                result.put("success", false);
                result.put("message", "URL不能为空");
                return ResponseEntity.badRequest().body(result);
            }

            if (request.getConcurrency() < 1 || request.getConcurrency() > 100) {
                Map<String, Object> result = new HashMap<>();
                result.put("success", false);
                result.put("message", "并发数必须在1-100之间");
                return ResponseEntity.badRequest().body(result);
            }

            if ((request.getTotalRequests() == null || request.getTotalRequests() <= 0)
                    && (request.getDurationSeconds() == null || request.getDurationSeconds() <= 0)) {
                Map<String, Object> result = new HashMap<>();
                result.put("success", false);
                result.put("message", "请设置总请求数或持续时间");
                return ResponseEntity.badRequest().body(result);
            }

            // 创建压测配置
            PressureResult.TestConfig config = new PressureResult.TestConfig();
            config.setUrl(request.getUrl());
            config.setMethod(request.getMethod() != null ? request.getMethod() : "GET");
            config.setHeaders(request.getHeaders());
            config.setBody(request.getBody());
            config.setConcurrency(request.getConcurrency());
            config.setTotalRequests(request.getTotalRequests());
            config.setDurationSeconds(request.getDurationSeconds());
            config.setQpsLimit(request.getQpsLimit());
            config.setDelayMs(request.getDelayMs());

            // 创建压测服务并启动
            PressureTestService pressureTestService = new PressureTestService();

            PressureTestService.ProgressCallback callback = new PressureTestService.ProgressCallback() {
                @Override
                public void onProgress(int completed, int total, PressureResult.PerformanceMetrics metrics) {
                    if (sessionId != null) {
                        pressureWebSocketHandler.sendProgress(sessionId, completed, total, metrics);
                    }
                }

                @Override
                public void onComplete(PressureResult result) {
                    if (sessionId != null) {
                        pressureWebSocketHandler.sendComplete(sessionId, result);
                    }
                }

                @Override
                public void onError(String message) {
                    if (sessionId != null) {
                        pressureWebSocketHandler.sendError(sessionId, message);
                    }
                }
            };

            Future<PressureResult> future = pressureTestService.startPressureTest(config, callback);

            // 存储任务
            if (sessionId != null) {
                runningTasks.put(sessionId, future);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "压测任务已启动");
            result.put("sessionId", sessionId != null ? sessionId : "none");
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("启动压测失败", e);
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "启动压测失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(result);
        }
    }

    /**
     * 停止压测任务
     */
    @PostMapping("/pressure/stop")
    public ResponseEntity<?> stopPressureTest(@RequestBody Map<String, String> request) {
        String sessionId = request.get("sessionId");

        log.info("停止压测任务: sessionId={}", sessionId);

        try {
            if (sessionId != null && runningTasks.containsKey(sessionId)) {
                Future<PressureResult> future = runningTasks.get(sessionId);
                future.cancel(true);
                runningTasks.remove(sessionId);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "压测任务已停止");
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("停止压测失败", e);
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "停止压测失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(result);
        }
    }

    /**
     * 查询压测状态
     */
    @GetMapping("/pressure/status")
    public ResponseEntity<?> getPressureStatus(@RequestParam String sessionId) {
        boolean isRunning = runningTasks.containsKey(sessionId) && !runningTasks.get(sessionId).isDone();

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("running", isRunning);
        return ResponseEntity.ok(result);
    }

    /**
     * 导出 PDF 报告
     */
    @PostMapping("/pressure/export/pdf")
    public void exportPdfReport(HttpServletRequest request, HttpServletResponse response) {
        try {
            PressureResult result = getPressureResultFromRequest(request);
            pressureReportService.exportPdf(result, response);
        } catch (Exception e) {
            log.error("导出 PDF 报告失败", e);
            try {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                response.getWriter().write("导出失败: " + e.getMessage());
            } catch (Exception ex) {
                log.error("写入错误响应失败", ex);
            }
        }
    }

    /**
     * 导出 Excel 报告
     */
    @PostMapping("/pressure/export/excel")
    public void exportExcelReport(HttpServletRequest request, HttpServletResponse response) {
        try {
            PressureResult result = getPressureResultFromRequest(request);
            pressureReportService.exportExcel(result, response);
        } catch (Exception e) {
            log.error("导出 Excel 报告失败", e);
            try {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                response.getWriter().write("导出失败: " + e.getMessage());
            } catch (Exception ex) {
                log.error("写入错误响应失败", ex);
            }
        }
    }

    /**
     * 从请求中解析压测结果
     */
    private PressureResult getPressureResultFromRequest(HttpServletRequest request) {
        PressureResult result = new PressureResult();

        // 解析测试配置
        PressureResult.TestConfig config = new PressureResult.TestConfig();
        config.setUrl(request.getParameter("url"));
        config.setMethod(request.getParameter("method"));
        config.setConcurrency(Integer.parseInt(request.getParameter("concurrency")));
        String durationStr = request.getParameter("durationSeconds");
        if (durationStr != null && !durationStr.isEmpty()) {
            config.setDurationSeconds(Integer.parseInt(durationStr));
        }
        result.setConfig(config);

        // 解析测试摘要
        PressureResult.TestSummary summary = new PressureResult.TestSummary();
        summary.setStartTime(request.getParameter("startTime"));
        summary.setEndTime(request.getParameter("endTime"));
        summary.setDurationSeconds(Long.parseLong(request.getParameter("durationSeconds")));
        summary.setCompletedRequests(Integer.parseInt(request.getParameter("completedRequests")));
        result.setSummary(summary);

        // 解析性能指标
        PressureResult.PerformanceMetrics metrics = new PressureResult.PerformanceMetrics();
        metrics.setSuccessCount(Integer.parseInt(request.getParameter("successCount")));
        metrics.setFailCount(Integer.parseInt(request.getParameter("failCount")));
        metrics.setSuccessRate(Double.parseDouble(request.getParameter("successRate")));
        metrics.setFailRate(Double.parseDouble(request.getParameter("failRate")));
        metrics.setAvgResponseTime(Double.parseDouble(request.getParameter("avgResponseTime")));
        metrics.setMinResponseTime(Long.parseLong(request.getParameter("minResponseTime")));
        metrics.setMaxResponseTime(Long.parseLong(request.getParameter("maxResponseTime")));
        metrics.setTp90(Long.parseLong(request.getParameter("tp90")));
        metrics.setTp95(Long.parseLong(request.getParameter("tp95")));
        metrics.setTp99(Long.parseLong(request.getParameter("tp99")));
        metrics.setQps(Double.parseDouble(request.getParameter("qps")));
        result.setMetrics(metrics);

        // 解析分析结论（从JSON参数）
        String analysisJson = request.getParameter("analysisJson");
        if (analysisJson != null && !analysisJson.isEmpty()) {
            try {
                com.alibaba.fastjson.JSONObject analysisObj =
                        com.alibaba.fastjson.JSONObject.parseObject(analysisJson);
                PressureResult.AnalysisResult analysis = new PressureResult.AnalysisResult();
                analysis.setOverallLevel(analysisObj.getString("overallLevel"));
                analysis.setPerformanceLevel(analysisObj.getString("performanceLevel"));
                analysis.setStabilityLevel(analysisObj.getString("stabilityLevel"));
                analysis.setConclusions(
                        com.alibaba.fastjson.JSONArray.parseArray(
                                analysisObj.getJSONArray("conclusions").toJSONString(), String.class));
                analysis.setSuggestions(
                        com.alibaba.fastjson.JSONArray.parseArray(
                                analysisObj.getJSONArray("suggestions").toJSONString(), String.class));
                result.setAnalysis(analysis);
            } catch (Exception e) {
                log.warn("解析分析结论失败", e);
            }
        }

        // 解析响应时间分布
        String distJson = request.getParameter("responseTimeDistribution");
        if (distJson != null && !distJson.isEmpty()) {
            try {
                java.util.Map<String, Integer> distribution =
                        com.alibaba.fastjson.JSONObject.parseObject(distJson,
                                new com.alibaba.fastjson.TypeReference<java.util.Map<String, Integer>>() {});
                metrics.setResponseTimeDistribution(distribution);
            } catch (Exception e) {
                log.warn("解析响应时间分布失败", e);
            }
        }

        result.setCompleted(true);
        return result;
    }
}
