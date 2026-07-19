package com.gxcj.xjtool.controller;

import com.gxcj.xjtool.dto.NodeDeployResult;
import com.gxcj.xjtool.model.ServerInfo;
import com.gxcj.xjtool.service.DeployExecutionService;
import com.gxcj.xjtool.service.ExpiringTaskRegistry;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Slf4j
@RestController
@RequestMapping("/api/deploy")
public class DeployController {

    private static final long COMMAND_TIMEOUT = 10 * 60 * 1000L;
    private static final long COMPLETED_TASK_RETENTION_MS = 30 * 60 * 1000L;
    private static final int MAX_BATCH_TASKS = 500;
    private static final String DEFAULT_EXISTS_POLICY = "backup";

    @Autowired
    private ServerController serverController;

    @Autowired
    private DeployExecutionService deployExecutionService;
    private final ExecutorService batchTaskExecutor = Executors.newCachedThreadPool();
    private final ExpiringTaskRegistry<BatchTaskState> batchTasks =
            new ExpiringTaskRegistry<>(COMPLETED_TASK_RETENTION_MS, MAX_BATCH_TASKS);

    @PreDestroy
    public void shutdown() {
        batchTaskExecutor.shutdownNow();
    }

    @PostMapping("/batch-run")
    public BatchDeployResponse batchRun(
            @RequestParam("serverIds") List<String> serverIds,
            @RequestParam(value = "targetDir", required = false) String targetDir,
            @RequestParam(value = "commands", required = false) String commands,
            @RequestParam(value = "enableUpload", defaultValue = "false") boolean enableUpload,
            @RequestParam(value = "enableCommand", defaultValue = "false") boolean enableCommand,
            @RequestParam(value = "existsPolicy", defaultValue = DEFAULT_EXISTS_POLICY) String existsPolicy,
            @RequestParam(value = "chmodExecutable", defaultValue = "false") boolean chmodExecutable,
            @RequestParam(value = "customPermission", required = false) String customPermission,
            @RequestParam(value = "taskType", defaultValue = "mixed") String taskType,
            @RequestParam(value = "executionStrategy", defaultValue = "serial") String executionStrategy,
            @RequestParam(value = "maxParallel", defaultValue = "5") int maxParallel,
            @RequestParam(value = "stopOnError", defaultValue = "false") boolean stopOnError,
            @RequestParam(value = "privilegedExecution", defaultValue = "false") boolean privilegedExecution,
            @RequestParam(value = "file", required = false) MultipartFile file) {

        BatchDeployResponse response = new BatchDeployResponse();
        response.setSuccess(true);
        response.setResults(new ArrayList<>());

        String normalizedTaskType = isBlank(taskType) ? "mixed" : taskType.trim().toLowerCase();
        if ("upload".equals(normalizedTaskType)) {
            enableUpload = true;
            enableCommand = false;
        } else if ("command".equals(normalizedTaskType)) {
            enableUpload = false;
            enableCommand = true;
        } else if (!"mixed".equals(normalizedTaskType)) {
            response.setSuccess(false);
            response.setMessage("不支持的任务类型: " + taskType);
            return response;
        }

        if ((serverIds == null || serverIds.isEmpty()) || (!enableUpload && !enableCommand)) {
            response.setSuccess(false);
            response.setMessage("请选择服务器并至少启用一种操作");
            return response;
        }
        if (enableUpload && (file == null || file.isEmpty())) {
            response.setSuccess(false);
            response.setMessage("启用附件分发时必须选择附件");
            return response;
        }
        if (enableUpload && isBlank(targetDir)) {
            response.setSuccess(false);
            response.setMessage("启用附件分发时必须填写目标目录");
            return response;
        }
        if (enableCommand && isBlank(commands)) {
            response.setSuccess(false);
            response.setMessage("启用批量执行时必须填写命令内容");
            return response;
        }
        if (!enableCommand) {
            privilegedExecution = false;
        }

        boolean parallel = "parallel".equalsIgnoreCase(executionStrategy);
        if (parallel) {
            runParallel(serverIds, targetDir, commands, enableUpload, enableCommand, existsPolicy,
                    chmodExecutable, customPermission, privilegedExecution, file, Math.min(Math.max(maxParallel, 1), 20), response);
        } else {
            runSerial(serverIds, targetDir, commands, enableUpload, enableCommand, existsPolicy,
                    chmodExecutable, customPermission, privilegedExecution, file, stopOnError, response);
        }

        return response;
    }

    @PostMapping("/batch-start")
    public BatchStartResponse batchStart(
            @RequestParam("serverIds") List<String> serverIds,
            @RequestParam(value = "targetDir", required = false) String targetDir,
            @RequestParam(value = "commands", required = false) String commands,
            @RequestParam(value = "enableUpload", defaultValue = "false") boolean enableUpload,
            @RequestParam(value = "enableCommand", defaultValue = "false") boolean enableCommand,
            @RequestParam(value = "existsPolicy", defaultValue = DEFAULT_EXISTS_POLICY) String existsPolicy,
            @RequestParam(value = "chmodExecutable", defaultValue = "false") boolean chmodExecutable,
            @RequestParam(value = "customPermission", required = false) String customPermission,
            @RequestParam(value = "taskType", defaultValue = "mixed") String taskType,
            @RequestParam(value = "executionStrategy", defaultValue = "serial") String executionStrategy,
            @RequestParam(value = "maxParallel", defaultValue = "5") int maxParallel,
            @RequestParam(value = "stopOnError", defaultValue = "false") boolean stopOnError,
            @RequestParam(value = "privilegedExecution", defaultValue = "false") boolean privilegedExecution,
            @RequestParam(value = "file", required = false) MultipartFile file) {

        BatchStartResponse response = new BatchStartResponse();
        response.setSuccess(false);

        String normalizedTaskType = isBlank(taskType) ? "mixed" : taskType.trim().toLowerCase();
        if ("upload".equals(normalizedTaskType)) {
            enableUpload = true;
            enableCommand = false;
        } else if ("command".equals(normalizedTaskType)) {
            enableUpload = false;
            enableCommand = true;
        } else if (!"mixed".equals(normalizedTaskType)) {
            response.setMessage("不支持的任务类型: " + taskType);
            return response;
        }

        if ((serverIds == null || serverIds.isEmpty()) || (!enableUpload && !enableCommand)) {
            response.setMessage("请选择服务器并至少启用一种操作");
            return response;
        }
        if (enableUpload && (file == null || file.isEmpty())) {
            response.setMessage("启用附件分发时必须选择附件");
            return response;
        }
        if (enableUpload && isBlank(targetDir)) {
            response.setMessage("启用附件分发时必须填写目标目录");
            return response;
        }
        if (enableCommand && isBlank(commands)) {
            response.setMessage("启用批量执行时必须填写命令内容");
            return response;
        }
        if (!enableCommand) {
            privilegedExecution = false;
        }

        try {
            BatchRunRequest request = new BatchRunRequest();
            request.setServerIds(new ArrayList<>(serverIds));
            request.setTargetDir(targetDir);
            request.setCommands(commands);
            request.setEnableUpload(enableUpload);
            request.setEnableCommand(enableCommand);
            request.setExistsPolicy(existsPolicy);
            request.setChmodExecutable(chmodExecutable);
            request.setCustomPermission(customPermission);
            request.setTaskType(normalizedTaskType);
            request.setExecutionStrategy(executionStrategy);
            request.setMaxParallel(Math.min(Math.max(maxParallel, 1), 20));
            request.setStopOnError(stopOnError);
            request.setPrivilegedExecution(privilegedExecution);
            request.setFile(copyUploadedFile(file));

            String taskId = UUID.randomUUID().toString();
            BatchTaskState state = new BatchTaskState(taskId);
            batchTasks.register(taskId, state);
            try {
                batchTaskExecutor.submit(() -> executeBatchTask(state, request));
            } catch (RuntimeException e) {
                batchTasks.remove(taskId);
                throw e;
            }

            response.setSuccess(true);
            response.setTaskId(taskId);
            response.setMessage("任务已启动");
            return response;
        } catch (Exception e) {
            response.setMessage("启动任务失败: " + e.getMessage());
            log.error("启动批量任务失败", e);
            return response;
        }
    }

    @GetMapping(value = "/batch-events/{taskId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter batchEvents(@PathVariable("taskId") String taskId) {
        BatchTaskState state = batchTasks.get(taskId);
        if (state == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在或已过期");
        }
        SseEmitter emitter = new SseEmitter(COMMAND_TIMEOUT + 60000L);
        state.attach(emitter);
        return emitter;
    }

    private void executeBatchTask(BatchTaskState state, BatchRunRequest request) {
        try {
            state.emit(BatchTaskEvent.taskStart(state.getTaskId(), request.getServerIds().size(),
                    request.getExecutionStrategy(), request.getMaxParallel()));
            for (int i = 0; i < request.getServerIds().size(); i++) {
                state.emit(nodeEvent("node-pending", state.getTaskId(), request.getServerIds().get(i), i,
                        request.getServerIds().size(), "等待执行", null));
            }

            if ("parallel".equalsIgnoreCase(request.getExecutionStrategy())) {
                runParallelStream(state, request);
            } else {
                runSerialStream(state, request);
            }

            state.emit(BatchTaskEvent.taskComplete(state.getTaskId(), state.isSuccess(), state.getResults()));
            state.complete();
        } catch (Exception e) {
            log.error("批量任务执行异常 taskId={}", state.getTaskId(), e);
            state.emit(BatchTaskEvent.taskError(state.getTaskId(), "任务执行异常: " + e.getMessage()));
            state.completeWithError(e);
        } finally {
            batchTasks.markCompleted(state.getTaskId());
        }
    }

    private void runSerialStream(BatchTaskState state, BatchRunRequest request) {
        boolean stopped = false;
        for (int i = 0; i < request.getServerIds().size(); i++) {
            String serverId = request.getServerIds().get(i);
            if (stopped) {
                NodeDeployResult skipped = skippedResult(serverId, "因前序节点失败而跳过");
                skipped.setStatus("skipped");
                enrichResultServerInfo(skipped, serverId);
                state.addResult(skipped);
                state.emit(nodeEvent("node-result", state.getTaskId(), serverId, i, request.getServerIds().size(),
                        "已跳过", skipped));
                continue;
            }

            state.emit(nodeEvent("node-running", state.getTaskId(), serverId, i, request.getServerIds().size(),
                    "正在执行", null));
            NodeDeployResult result = runOne(serverId, request.getTargetDir(), request.getCommands(),
                    request.isEnableUpload(), request.isEnableCommand(), request.getExistsPolicy(),
                    request.isChmodExecutable(), request.getCustomPermission(), request.isPrivilegedExecution(),
                    request.getFile());
            result.setStatus(result.isSuccess() ? "success" : "error");
            state.addResult(result);
            state.emit(nodeEvent("node-result", state.getTaskId(), serverId, i, request.getServerIds().size(),
                    result.isSuccess() ? "执行成功" : "执行失败", result));
            if (!result.isSuccess()) {
                state.setSuccess(false);
                stopped = request.isStopOnError();
            }
        }
    }

    private void runParallelStream(BatchTaskState state, BatchRunRequest request) {
        ExecutorService executor = Executors.newFixedThreadPool(request.getMaxParallel());
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < request.getServerIds().size(); i++) {
                final int index = i;
                final String serverId = request.getServerIds().get(i);
                futures.add(executor.submit(() -> {
                    state.emit(nodeEvent("node-running", state.getTaskId(), serverId, index,
                            request.getServerIds().size(), "正在执行", null));
                    NodeDeployResult result = runOne(serverId, request.getTargetDir(), request.getCommands(),
                            request.isEnableUpload(), request.isEnableCommand(), request.getExistsPolicy(),
                            request.isChmodExecutable(), request.getCustomPermission(), request.isPrivilegedExecution(),
                            request.getFile());
                    result.setStatus(result.isSuccess() ? "success" : "error");
                    state.addResult(result);
                    if (!result.isSuccess()) {
                        state.setSuccess(false);
                    }
                    state.emit(nodeEvent("node-result", state.getTaskId(), serverId, index,
                            request.getServerIds().size(), result.isSuccess() ? "执行成功" : "执行失败", result));
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        } catch (Exception e) {
            state.setSuccess(false);
            state.emit(BatchTaskEvent.taskError(state.getTaskId(), "并行任务执行异常: " + e.getMessage()));
            log.error("并行流式批量任务执行失败", e);
        } finally {
            executor.shutdownNow();
        }
    }

    private BatchTaskEvent nodeEvent(String type, String taskId, String serverId, int index, int total,
            String message, NodeDeployResult result) {
        BatchTaskEvent event = new BatchTaskEvent();
        event.setType(type);
        event.setTaskId(taskId);
        event.setServerId(serverId);
        event.setIndex(index);
        event.setTotal(total);
        event.setMessage(message);
        event.setResult(result);
        ServerInfo server = serverController.getById(serverId);
        if (server != null) {
            event.setHost(server.getHost());
            event.setName(server.getName() != null ? server.getName() : server.getHost());
        } else if (result != null) {
            event.setHost(result.getHost());
            event.setName(result.getName());
        }
        return event;
    }

    private void enrichResultServerInfo(NodeDeployResult result, String serverId) {
        ServerInfo server = serverController.getById(serverId);
        if (server != null) {
            result.setName(server.getName() != null ? server.getName() : server.getHost());
            result.setHost(server.getHost());
        }
    }

    private MultipartFile copyUploadedFile(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            return file;
        }
        return new StoredMultipartFile(file.getName(), file.getOriginalFilename(), file.getContentType(), file.getBytes());
    }

    private void runSerial(List<String> serverIds, String targetDir, String commands, boolean enableUpload,
            boolean enableCommand, String existsPolicy, boolean chmodExecutable, String customPermission,
            boolean privilegedExecution, MultipartFile file, boolean stopOnError, BatchDeployResponse response) {
        boolean stopped = false;
        for (String serverId : serverIds) {
            if (stopped) {
                NodeDeployResult skipped = skippedResult(serverId, "因前序节点失败而跳过");
                response.getResults().add(skipped);
                continue;
            }
            NodeDeployResult result = runOne(serverId, targetDir, commands, enableUpload, enableCommand,
                    existsPolicy, chmodExecutable, customPermission, privilegedExecution, file);
            response.getResults().add(result);
            if (!result.isSuccess()) {
                response.setSuccess(false);
                stopped = stopOnError;
            }
        }
    }

    private void runParallel(List<String> serverIds, String targetDir, String commands, boolean enableUpload,
            boolean enableCommand, String existsPolicy, boolean chmodExecutable, String customPermission,
            boolean privilegedExecution, MultipartFile file, int maxParallel, BatchDeployResponse response) {
        ExecutorService executor = Executors.newFixedThreadPool(maxParallel);
        try {
            List<Future<NodeDeployResult>> futures = new ArrayList<>();
            for (String serverId : serverIds) {
                futures.add(executor.submit(() -> runOne(serverId, targetDir, commands, enableUpload, enableCommand,
                        existsPolicy, chmodExecutable, customPermission, privilegedExecution, file)));
            }
            for (Future<NodeDeployResult> future : futures) {
                NodeDeployResult result = future.get();
                response.getResults().add(result);
                if (!result.isSuccess()) {
                    response.setSuccess(false);
                }
            }
        } catch (Exception e) {
            response.setSuccess(false);
            response.setMessage("并行任务执行异常: " + e.getMessage());
            log.error("并行批量任务执行失败", e);
        } finally {
            executor.shutdownNow();
        }
    }

    private NodeDeployResult skippedResult(String serverId, String message) {
        NodeDeployResult result = new NodeDeployResult();
        result.setServerId(serverId);
        result.setSuccess(false);
        result.setMessage(message);
        result.setLogs(new ArrayList<>());
        result.getLogs().add("[跳过] " + message);
        return result;
    }

    private NodeDeployResult runOne(String serverId, String targetDir, String commands, boolean enableUpload,
            boolean enableCommand, String existsPolicy, boolean chmodExecutable, String customPermission,
            boolean privilegedExecution, MultipartFile file) {
        ServerInfo server = serverController.getById(serverId);
        return deployExecutionService.runOne(serverId, server, targetDir, commands, enableUpload, enableCommand,
                existsPolicy, chmodExecutable, customPermission, privilegedExecution, file);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    @Data
    public static class BatchDeployResponse {
        private boolean success;
        private String message;
        private List<NodeDeployResult> results;
    }

    @Data
    public static class BatchStartResponse {
        private boolean success;
        private String message;
        private String taskId;
    }

    @Data
    public static class BatchRunRequest {
        private List<String> serverIds;
        private String targetDir;
        private String commands;
        private boolean enableUpload;
        private boolean enableCommand;
        private String existsPolicy;
        private boolean chmodExecutable;
        private String customPermission;
        private String taskType;
        private String executionStrategy;
        private int maxParallel;
        private boolean stopOnError;
        private boolean privilegedExecution;
        private MultipartFile file;
    }

    @Data
    public static class BatchTaskEvent {
        private String type;
        private String taskId;
        private String serverId;
        private String name;
        private String host;
        private int index;
        private int total;
        private String message;
        private boolean success;
        private String executionStrategy;
        private int maxParallel;
        private NodeDeployResult result;
        private List<NodeDeployResult> results;

        static BatchTaskEvent taskStart(String taskId, int total, String executionStrategy, int maxParallel) {
            BatchTaskEvent event = new BatchTaskEvent();
            event.setType("task-start");
            event.setTaskId(taskId);
            event.setTotal(total);
            event.setExecutionStrategy(executionStrategy);
            event.setMaxParallel(maxParallel);
            event.setSuccess(true);
            event.setMessage("任务开始执行");
            return event;
        }

        static BatchTaskEvent taskComplete(String taskId, boolean success, List<NodeDeployResult> results) {
            BatchTaskEvent event = new BatchTaskEvent();
            event.setType("task-complete");
            event.setTaskId(taskId);
            event.setSuccess(success);
            event.setResults(new ArrayList<>(results));
            event.setMessage(success ? "任务执行完成" : "任务执行完成，存在失败节点");
            return event;
        }

        static BatchTaskEvent taskError(String taskId, String message) {
            BatchTaskEvent event = new BatchTaskEvent();
            event.setType("task-error");
            event.setTaskId(taskId);
            event.setSuccess(false);
            event.setMessage(message);
            return event;
        }
    }

    private static class BatchTaskState {
        private final String taskId;
        private final List<BatchTaskEvent> events = new CopyOnWriteArrayList<>();
        private final List<NodeDeployResult> results = Collections.synchronizedList(new ArrayList<>());
        private volatile SseEmitter emitter;
        private volatile boolean success = true;
        private volatile boolean completed = false;

        BatchTaskState(String taskId) {
            this.taskId = taskId;
        }

        String getTaskId() {
            return taskId;
        }

        List<NodeDeployResult> getResults() {
            synchronized (results) {
                return new ArrayList<>(results);
            }
        }

        boolean isSuccess() {
            return success;
        }

        void setSuccess(boolean success) {
            this.success = success;
        }

        void addResult(NodeDeployResult result) {
            results.add(result);
        }

        void attach(SseEmitter emitter) {
            this.emitter = emitter;
            emitter.onCompletion(() -> clearEmitter(emitter));
            emitter.onTimeout(() -> clearEmitter(emitter));
            emitter.onError((e) -> clearEmitter(emitter));
            for (BatchTaskEvent event : events) {
                send(event);
            }
            if (completed) {
                complete();
            }
        }

        void emit(BatchTaskEvent event) {
            events.add(event);
            send(event);
        }

        synchronized void send(BatchTaskEvent event) {
            if (emitter == null) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().name(event.getType()).data(event));
            } catch (IOException | IllegalStateException e) {
                clearEmitter(emitter);
            }
        }

        void complete() {
            completed = true;
            SseEmitter current = emitter;
            if (current != null) {
                current.complete();
            }
        }

        void completeWithError(Exception e) {
            completed = true;
            SseEmitter current = emitter;
            if (current != null) {
                current.completeWithError(e);
            }
        }

        void clearEmitter(SseEmitter target) {
            if (this.emitter == target) {
                this.emitter = null;
            }
        }
    }

    private static class StoredMultipartFile implements MultipartFile {
        private final String name;
        private final String originalFilename;
        private final String contentType;
        private final byte[] bytes;

        StoredMultipartFile(String name, String originalFilename, String contentType, byte[] bytes) {
            this.name = name;
            this.originalFilename = originalFilename;
            this.contentType = contentType;
            this.bytes = bytes == null ? new byte[0] : bytes;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getOriginalFilename() {
            return originalFilename;
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public boolean isEmpty() {
            return bytes.length == 0;
        }

        @Override
        public long getSize() {
            return bytes.length;
        }

        @Override
        public byte[] getBytes() {
            return bytes.clone();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public void transferTo(File dest) throws IOException {
            Files.write(dest.toPath(), bytes);
        }
    }

}
