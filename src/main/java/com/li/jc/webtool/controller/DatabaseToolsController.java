package com.li.jc.webtool.controller;

import com.li.jc.webtool.service.DatabaseToolsService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** SQL tool database administration endpoints for Oracle, DM and MySQL. */
@Slf4j
@RestController
@RequestMapping("/api/database/tools")
@RequiredArgsConstructor
public class DatabaseToolsController {

    private final DatabaseToolsService databaseToolsService;

    @GetMapping("/large-tables")
    public Map<String, Object> largeTables(@RequestParam int datasourceIndex,
                                           @RequestParam(defaultValue = "1") double minSizeGb) {
        return databaseToolsService.queryLargeTables(datasourceIndex, minSizeGb);
    }

    @GetMapping("/large-tables/export")
    public ResponseEntity<byte[]> exportLargeTables(@RequestParam int datasourceIndex,
                                                     @RequestParam(defaultValue = "1") double minSizeGb) {
        DatabaseToolsService.ReportFile report = databaseToolsService.exportLargeTables(datasourceIndex, minSizeGb);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(report.getFileName(), StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(headers).body(report.getContent());
    }

    @GetMapping("/archive")
    public Map<String, Object> archive(@RequestParam int datasourceIndex) {
        return databaseToolsService.queryArchiveLogs(datasourceIndex);
    }

    @PostMapping("/archive/cleanup")
    public Map<String, Object> cleanupArchive(@RequestBody MaintenanceRequest request) {
        return databaseToolsService.cleanupArchiveLogs(request.getDatasourceIndex(), request.getRetentionDays(),
                request.isConfirmed());
    }

    @GetMapping("/recycle-bin")
    public Map<String, Object> recycleBin(@RequestParam int datasourceIndex) {
        return databaseToolsService.queryRecycleBin(datasourceIndex);
    }

    @PostMapping("/recycle-bin/cleanup")
    public Map<String, Object> cleanupRecycleBin(@RequestBody MaintenanceRequest request) {
        return databaseToolsService.cleanupRecycleBin(request.getDatasourceIndex(), request.isConfirmed());
    }

    @GetMapping("/health-check")
    public Map<String, Object> healthCheck(@RequestParam int datasourceIndex) {
        return databaseToolsService.runHealthCheck(datasourceIndex);
    }

    @GetMapping("/health-check/report")
    public ResponseEntity<byte[]> healthCheckReport(@RequestParam int datasourceIndex) {
        DatabaseToolsService.ReportFile report = databaseToolsService.exportHealthCheckReport(datasourceIndex);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/zip"));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(report.getFileName(), StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(headers).body(report.getContent());
    }

    @GetMapping("/performance/snapshots")
    public Map<String, Object> performanceSnapshots(@RequestParam int datasourceIndex) {
        return databaseToolsService.queryPerformanceSnapshots(datasourceIndex);
    }

    @PostMapping("/performance/report")
    public ResponseEntity<byte[]> performanceReport(@RequestBody PerformanceReportRequest request) {
        DatabaseToolsService.ReportFile report = databaseToolsService.exportPerformanceReport(
                request.getDatasourceIndex(), request.getBeginSnapId(), request.getEndSnapId());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_HTML);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(report.getFileName(), StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(headers).body(report.getContent());
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, Object>> handleToolError(RuntimeException exception) {
        log.warn("Database tool request failed: {}", exception.getMessage());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("message", exception.getMessage());
        return ResponseEntity.badRequest().body(result);
    }

    @Data
    public static class MaintenanceRequest {
        private int datasourceIndex;
        private int retentionDays = 7;
        private boolean confirmed;
    }

    @Data
    public static class PerformanceReportRequest {
        private int datasourceIndex;
        private Long beginSnapId;
        private Long endSnapId;
    }
}
