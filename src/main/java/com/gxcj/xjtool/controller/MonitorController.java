package com.gxcj.xjtool.controller;

import com.gxcj.xjtool.service.impl.SshServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 系统监控控制器
 * 提供系统运行状态和性能指标接口
 */
@RestController
@RequestMapping("/api/monitor")
public class MonitorController {

    @Autowired
    private SshServiceImpl sshService;

    /**
     * 获取SSH连接状态
     * 
     * @return 连接状态信息
     */
    @GetMapping("/ssh-status")
    public Map<String, Object> getSshStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("currentConnections", sshService.getCurrentConnectionCount());
        status.put("maxConnections", sshService.getMaxConnections());
        status.put("availableConnections", sshService.getMaxConnections() - sshService.getCurrentConnectionCount());

        // 计算使用率
        double usageRate = (double) sshService.getCurrentConnectionCount() / sshService.getMaxConnections() * 100;
        status.put("usageRate", String.format("%.1f%%", usageRate));

        return status;
    }

    /**
     * 获取系统运行状态
     * 
     * @return 系统状态信息
     */
    @GetMapping("/system-status")
    public Map<String, Object> getSystemStatus() {
        Map<String, Object> status = new HashMap<>();

        // JVM内存信息
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory() / 1024 / 1024;
        long totalMemory = runtime.totalMemory() / 1024 / 1024;
        long freeMemory = runtime.freeMemory() / 1024 / 1024;
        long usedMemory = totalMemory - freeMemory;

        status.put("maxMemoryMB", maxMemory);
        status.put("totalMemoryMB", totalMemory);
        status.put("usedMemoryMB", usedMemory);
        status.put("freeMemoryMB", freeMemory);

        // 线程信息
        status.put("activeThreads", Thread.activeCount());

        // 处理器信息
        status.put("availableProcessors", runtime.availableProcessors());

        return status;
    }

    /**
     * 健康检查接口
     * 
     * @return 健康状态
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("timestamp", System.currentTimeMillis());

        // SSH连接池状态
        int currentConn = sshService.getCurrentConnectionCount();
        int maxConn = sshService.getMaxConnections();
        String sshStatus = currentConn < maxConn * 0.9 ? "HEALTHY" : "WARNING";
        health.put("sshPool", sshStatus);

        return health;
    }
}
