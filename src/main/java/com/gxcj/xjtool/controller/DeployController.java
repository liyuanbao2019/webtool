package com.gxcj.xjtool.controller;

import com.gxcj.xjtool.agent.AgentTerminalCallback;
import com.gxcj.xjtool.agent.AgentTerminalClient;
import com.gxcj.xjtool.agent.AgentTerminalSession;
import com.gxcj.xjtool.config.ServerConfig;
import com.gxcj.xjtool.model.ServerInfo;
import com.gxcj.xjtool.model.WebSshData;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.common.channel.PtyChannelConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Slf4j
@RestController
@RequestMapping("/api/deploy")
public class DeployController {

    private static final long SSH_TIMEOUT = 30000L;
    private static final long COMMAND_TIMEOUT = 10 * 60 * 1000L;
    private static final String DEFAULT_EXISTS_POLICY = "backup";

    @Autowired
    private ServerController serverController;

    @Autowired
    private ServerConfig serverConfig;

    @Autowired(required = false)
    private AgentTerminalClient agentTerminalClient;

    private SshClient sshClient;
    private final ExecutorService batchTaskExecutor = Executors.newCachedThreadPool();
    private final Map<String, BatchTaskState> batchTasks = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        sshClient = SshClient.setUpDefaultClient();
        sshClient.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        sshClient.start();
    }

    @PreDestroy
    public void shutdown() {
        if (sshClient != null) {
            sshClient.stop();
        }
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
            batchTasks.put(taskId, state);
            batchTaskExecutor.submit(() -> executeBatchTask(state, request));

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
        NodeDeployResult result = new NodeDeployResult();
        result.setServerId(serverId);
        result.setSuccess(true);
        result.setLogs(new ArrayList<>());
        long start = System.currentTimeMillis();

        ServerInfo server = serverController.getById(serverId);
        if (server == null) {
            result.fail("未找到服务器配置: " + serverId);
            return result;
        }
        result.setName(server.getName() != null ? server.getName() : server.getHost());
        result.setHost(server.getHost());

        String privilegedPassword = null;
        if (privilegedExecution) {
            privilegedPassword = validateRootPrivilege(server, result);
            if (!result.isSuccess()) {
                result.setDurationMs(System.currentTimeMillis() - start);
                return result;
            }
            result.getLogs().add("[特权] 已启用 root 特权账号执行策略，将通过登录账号切换到 root 后执行");
        }

        if (isAgentMode(server)) {
            runOneByAgent(server, renderedOrEmpty(targetDir, server, file), commands, enableUpload, enableCommand,
                    privilegedExecution, privilegedPassword, file, result, start);
            return result;
        }

        try (ClientSession session = openSession(server)) {
            String targetFile = null;
            String renderedTargetDir = renderVariables(targetOrDefault(targetDir, ""), server, "", null, file);
            if (enableUpload) {
                targetFile = uploadFile(session, server, renderedTargetDir, existsPolicy, chmodExecutable, customPermission, file, result);
            }
            if (enableCommand && !isBlank(commands)) {
                String rendered = renderVariables(commands, server, renderedTargetDir, targetFile, file);
                CommandResult commandResult = executeCommand(session, rendered,
                        privilegedExecution, privilegedPassword);
                result.setCommandExitCode(commandResult.getExitCode());
                result.getLogs().add("[命令] exitCode=" + commandResult.getExitCode());
                if (!isBlank(commandResult.getStdout())) {
                    result.getLogs().add("[stdout]\n" + commandResult.getStdout());
                }
                if (!isBlank(commandResult.getStderr())) {
                    result.getLogs().add("[stderr]\n" + commandResult.getStderr());
                }
                if (commandResult.getExitCode() != 0) {
                    result.fail("命令执行失败，exitCode=" + commandResult.getExitCode());
                }
            }
        } catch (Exception e) {
            log.error("批量部署节点执行失败: {}", serverId, e);
            result.fail(e.getMessage());
        }

        result.setDurationMs(System.currentTimeMillis() - start);
        return result;
    }

    private String renderedOrEmpty(String targetDir, ServerInfo server, MultipartFile file) {
        return renderVariables(targetOrDefault(targetDir, ""), server, "", null, file);
    }

    private void runOneByAgent(ServerInfo server, String renderedTargetDir, String commands, boolean enableUpload,
            boolean enableCommand, boolean privilegedExecution, String privilegedPassword, MultipartFile file,
            NodeDeployResult result, long start) {
        result.getLogs().add("[Agent] 使用 agent 模式执行");
        if (enableUpload) {
            result.fail("Agent 模式暂不支持文件分发，请使用批量执行命令或补充 agent 文件接口");
            result.setDurationMs(System.currentTimeMillis() - start);
            return;
        }
        if (!enableCommand || isBlank(commands)) {
            result.setDurationMs(System.currentTimeMillis() - start);
            return;
        }
        try {
            String rendered = renderVariables(commands, server, renderedTargetDir, null, file);
            CommandResult commandResult = executeAgentCommand(server, rendered, privilegedExecution, privilegedPassword);
            result.setCommandExitCode(commandResult.getExitCode());
            result.getLogs().add("[命令] exitCode=" + commandResult.getExitCode());
            if (!isBlank(commandResult.getStdout())) {
                result.getLogs().add("[stdout]\n" + commandResult.getStdout());
            }
            if (!isBlank(commandResult.getStderr())) {
                result.getLogs().add("[stderr]\n" + commandResult.getStderr());
            }
            if (commandResult.getExitCode() != 0) {
                result.fail("命令执行失败，exitCode=" + commandResult.getExitCode());
            }
        } catch (Exception e) {
            log.error("Agent 批量执行失败: {}", server.getHost(), e);
            result.fail(e.getMessage());
        }
        result.setDurationMs(System.currentTimeMillis() - start);
    }

    private String validateRootPrivilege(ServerInfo server, NodeDeployResult result) {
        Map<String, String> suPasswords = server.getSuPasswords();
        if (suPasswords == null || suPasswords.isEmpty()) {
            result.fail("已启用特权账号执行策略，但该目标主机未配置 su 账号");
            return null;
        }

        String rootPassword = null;
        for (Map.Entry<String, String> entry : suPasswords.entrySet()) {
            if ("root".equalsIgnoreCase(targetOrDefault(entry.getKey(), "").trim())) {
                rootPassword = entry.getValue();
                break;
            }
        }
        if (rootPassword == null) {
            result.fail("已启用特权账号执行策略，但该目标主机配置的 su 账号不是 root，当前配置: "
                    + String.join(", ", suPasswords.keySet()));
            return null;
        }
        if (isBlank(rootPassword)) {
            result.fail("已启用特权账号执行策略，但 root 的 su 密码为空");
            return null;
        }
        return rootPassword;
    }

    private boolean isAgentMode(ServerInfo server) {
        if (server == null) {
            return false;
        }
        if ("agent".equalsIgnoreCase(targetOrDefault(server.getConnectionMode(), "").trim())) {
            return true;
        }
        return serverConfig != null && serverConfig.getAgent() != null && serverConfig.getAgent().isEnabled();
    }

    private CommandResult executeAgentCommand(ServerInfo server, String command, boolean useRootPrivilege,
            String rootPassword) throws Exception {
        if (agentTerminalClient == null) {
            throw new IllegalStateException("Agent 客户端未初始化");
        }
        String marker = "__XJTOOL_AGENT_DONE_" + System.currentTimeMillis() + "__";
        String outputStartMarker = "__XJTOOL_AGENT_OUTPUT_START_" + System.currentTimeMillis() + "__";
        String baseCommand = buildBashCommand(buildShellScript(command), false);
        String commandScript = buildAgentCommandScript(baseCommand, outputStartMarker, marker, useRootPrivilege);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        CountDownLatch closed = new CountDownLatch(1);
        AgentTerminalSession agentSession = null;
        try {
            agentSession = agentTerminalClient.open(buildAgentRequest(server), "deploy-" + UUID.randomUUID(),
                    new AgentTerminalCallback() {
                        @Override
                        public void onOutput(byte[] data) {
                            synchronized (output) {
                                try {
                                    output.write(data);
                                } catch (Exception ignored) {
                                }
                            }
                        }

                        @Override
                        public void onError(String message) {
                            synchronized (errors) {
                                try {
                                    errors.write(targetOrDefault(message, "").getBytes(StandardCharsets.UTF_8));
                                    errors.write('\n');
                                } catch (Exception ignored) {
                                }
                            }
                        }

                        @Override
                        public void onClosed(String reason) {
                            closed.countDown();
                        }
                    });

            Thread.sleep(500L);
            if (useRootPrivilege) {
                int beforeSu = readAgentOutput(output, errors).length();
                agentSession.sendInput("su - root\n");
                if (!waitForAgentPasswordPrompt(output, errors, beforeSu, 8000L)) {
                    CommandResult result = new CommandResult();
                    result.setExitCode(126);
                    result.setStdout("");
                    result.setStderr("特权切换失败：未检测到 root 密码提示");
                    return result;
                }
                agentSession.sendInput(targetOrDefault(rootPassword, "") + "\n");
                Thread.sleep(350L);
            }
            agentSession.sendInput(commandScript);

            long deadline = System.currentTimeMillis() + COMMAND_TIMEOUT;
            while (System.currentTimeMillis() < deadline) {
                String combined = readAgentOutput(output, errors);
                Integer exitCode = extractMarkedExitCode(combined, marker);
                if (exitCode != null) {
                    CommandResult result = new CommandResult();
                    result.setExitCode(exitCode);
                    result.setStdout(cleanPrivilegedShellOutput(new String(output.toByteArray(), StandardCharsets.UTF_8),
                            outputStartMarker, marker, rootPassword, baseCommand));
                    result.setStderr(removeInteractiveShellWarnings(new String(errors.toByteArray(), StandardCharsets.UTF_8)));
                    return result;
                }
                if (closed.getCount() == 0) {
                    break;
                }
                Thread.sleep(120L);
            }

            CommandResult result = new CommandResult();
            result.setExitCode(-1);
            result.setStdout(cleanPrivilegedShellOutput(new String(output.toByteArray(), StandardCharsets.UTF_8),
                    outputStartMarker, marker, rootPassword, baseCommand));
            String stderr = removeInteractiveShellWarnings(new String(errors.toByteArray(), StandardCharsets.UTF_8));
            result.setStderr(appendMessage(stderr, "Agent 命令执行超时或连接关闭，未检测到结束标记: " + marker));
            return result;
        } finally {
            if (agentSession != null) {
                try {
                    agentSession.close();
                } catch (Exception e) {
                    log.debug("关闭 Agent 临时会话失败: {}", e.getMessage());
                }
            }
        }
    }

    private String buildAgentCommandScript(String baseCommand, String outputStartMarker, String marker,
            boolean requireRoot) {
        String rootCheck = requireRoot
                ? "if [ \"$(id -u)\" != \"0\" ]; then echo '特权切换失败：未成功切换到 root'; printf '\\n" + marker + ":126\\n'; exit; fi\n"
                : "";
        return "stty -echo 2>/dev/null || true\n"
                + rootCheck
                + "printf '\\n" + outputStartMarker + "\\n'\n"
                + baseCommand + "\n"
                + "printf '\\n" + marker + ":%s\\n' \"$?\"\n"
                + "stty echo 2>/dev/null || true\n";
    }

    private String buildShellScript(String command) {
        String cleanCommand = command == null ? "" : command.replace("\r\n", "\n").replace("\r", "\n");
        return "shopt -s expand_aliases\n"
                + "[ -f ~/.bashrc ] && . ~/.bashrc >/dev/null 2>&1\n"
                + "alias ll >/dev/null 2>&1 || alias ll='ls -alF'\n"
                + "alias la >/dev/null 2>&1 || alias la='ls -A'\n"
                + "alias l >/dev/null 2>&1 || alias l='ls -CF'\n"
                + cleanCommand;
    }

    private WebSshData buildAgentRequest(ServerInfo server) {
        WebSshData data = new WebSshData();
        data.setConnectionMode("agent");
        data.setHost(server.getHost());
        data.setUsername(server.getUsername());
        data.setCols(160);
        data.setRows(48);
        data.setCharset(StandardCharsets.UTF_8.name());
        data.setAgentBaseUrl(resolveAgentBaseUrl(server));
        data.setAgentId(!isBlank(server.getAgentId()) ? server.getAgentId() : server.getHost());
        data.setAgentToken(!isBlank(server.getAgentToken()) ? server.getAgentToken() : resolveAgentToken());
        return data;
    }

    private String resolveAgentBaseUrl(ServerInfo server) {
        if (!isBlank(server.getAgentBaseUrl())) {
            return server.getAgentBaseUrl();
        }
        int port = serverConfig != null && serverConfig.getAgent() != null && serverConfig.getAgent().getPort() > 0
                ? serverConfig.getAgent().getPort()
                : 18080;
        return "http://" + server.getHost() + ":" + port;
    }

    private String resolveAgentToken() {
        return serverConfig != null && serverConfig.getAgent() != null ? serverConfig.getAgent().getToken() : null;
    }

    private boolean waitForAgentPasswordPrompt(ByteArrayOutputStream stdout, ByteArrayOutputStream stderr, int startSize,
            long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String combined = readAgentOutput(stdout, stderr);
            String recent = combined.length() > startSize ? combined.substring(startSize) : combined;
            if (containsPasswordPrompt(recent)) {
                return true;
            }
            Thread.sleep(120L);
        }
        return false;
    }

    private String readAgentOutput(ByteArrayOutputStream stdout, ByteArrayOutputStream stderr) {
        synchronized (stdout) {
            synchronized (stderr) {
                return new String(stdout.toByteArray(), StandardCharsets.UTF_8)
                        + "\n" + new String(stderr.toByteArray(), StandardCharsets.UTF_8);
            }
        }
    }

    private ClientSession openSession(ServerInfo server) throws Exception {
        ClientSession session = sshClient.connect(server.getUsername(), server.getHost(), server.getPort())
                .verify(SSH_TIMEOUT).getSession();
        session.addPasswordIdentity(server.getPassword());
        session.auth().verify(SSH_TIMEOUT);
        return session;
    }

    private String uploadFile(ClientSession session, ServerInfo server, String targetDir, String existsPolicy,
            boolean chmodExecutable, String customPermission, MultipartFile file, NodeDeployResult result)
            throws Exception {
        String normalizedDir = normalizeRemoteDir(renderVariables(targetOrDefault(targetDir, "/tmp"), server, targetDir, null, file));
        String originalName = file.getOriginalFilename() == null ? "upload.bin" : file.getOriginalFilename();
        String targetFile = normalizedDir + "/" + originalName;

        try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
            ensureDirectory(sftp, normalizedDir);
            targetFile = resolveTargetFile(sftp, targetFile, existsPolicy, result);
            if (targetFile == null) {
                result.getLogs().add("[上传] 目标文件已存在，按策略跳过");
                result.setUploadStatus("skipped");
                return null;
            }
            try (OutputStream os = sftp.write(targetFile);
                    InputStream is = file.getInputStream()) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = is.read(buffer)) != -1) {
                    os.write(buffer, 0, len);
                }
            }
        }

        result.setTargetFile(targetFile);
        result.setUploadStatus("success");
        result.getLogs().add("[上传] " + originalName + " -> " + targetFile + " 成功");

        String permission = null;
        if (chmodExecutable) {
            permission = "755";
        }
        if (!isBlank(customPermission)) {
            permission = customPermission.trim();
        }
        if (!isBlank(permission)) {
            CommandResult chmodResult = executeCommand(session, "chmod " + shellQuote(permission) + " " + shellQuote(targetFile));
            result.getLogs().add("[权限] chmod " + permission + " " + targetFile + ", exitCode=" + chmodResult.getExitCode());
            if (chmodResult.getExitCode() != 0) {
                result.fail("权限设置失败，exitCode=" + chmodResult.getExitCode());
            }
        }

        return targetFile;
    }

    private String resolveTargetFile(SftpClient sftp, String targetFile, String existsPolicy, NodeDeployResult result)
            throws Exception {
        boolean exists = exists(sftp, targetFile);
        if (!exists) {
            return targetFile;
        }
        String policy = isBlank(existsPolicy) ? DEFAULT_EXISTS_POLICY : existsPolicy;
        if ("skip".equalsIgnoreCase(policy)) {
            return null;
        }
        String suffix = "." + System.currentTimeMillis() + ".bak";
        if ("backup".equalsIgnoreCase(policy)) {
            String backupPath = targetFile + suffix;
            sftp.rename(targetFile, backupPath);
            result.getLogs().add("[备份] " + targetFile + " -> " + backupPath);
            return targetFile;
        }
        if ("rename".equalsIgnoreCase(policy)) {
            int idx = targetFile.lastIndexOf('/');
            String dir = idx >= 0 ? targetFile.substring(0, idx) : "";
            String name = idx >= 0 ? targetFile.substring(idx + 1) : targetFile;
            return dir + "/" + System.currentTimeMillis() + "_" + name;
        }
        return targetFile;
    }

    private void ensureDirectory(SftpClient sftp, String dir) throws Exception {
        String[] parts = dir.split("/");
        String current = dir.startsWith("/") ? "" : ".";
        for (String part : parts) {
            if (part == null || part.isEmpty()) {
                continue;
            }
            current = current.endsWith("/") ? current + part : current + "/" + part;
            if (!exists(sftp, current)) {
                sftp.mkdir(current);
            }
        }
    }

    private boolean exists(SftpClient sftp, String path) {
        try {
            sftp.stat(path);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private CommandResult executeCommand(ClientSession session, String command) throws Exception {
        return executeCommand(session, command, false, null);
    }

    private CommandResult executeCommand(ClientSession session, String command, boolean useRootPrivilege,
            String rootPassword) throws Exception {
        CommandResult result = new CommandResult();
        String cleanCommand = command == null ? "" : command.replace("\r\n", "\n").replace("\r", "\n");
        // 使用登录交互式 shell，兼容 ll 等在 ~/.bashrc 中定义的 alias、PATH 和用户环境变量。
        // 同时申请 PTY，避免 sudo、top 等依赖终端的命令因 "no tty present" 而失败。
        // 登录 shell 默认不一定读取 ~/.bashrc，因此显式加载它，确保 alias 在各 Linux 发行版上行为一致。
        String shellScript = "shopt -s expand_aliases\n"
                + "[ -f ~/.bashrc ] && . ~/.bashrc >/dev/null 2>&1\n"
                + "alias ll >/dev/null 2>&1 || alias ll='ls -alF'\n"
                + "alias la >/dev/null 2>&1 || alias la='ls -A'\n"
                + "alias l >/dev/null 2>&1 || alias l='ls -CF'\n"
                + cleanCommand;
        String baseCommand = buildBashCommand(shellScript, !useRootPrivilege);
        if (useRootPrivilege) {
            return executePrivilegedShellCommand(session, baseCommand, rootPassword);
        }
        String wrapped = baseCommand;
        PtyChannelConfiguration pty = createPty();
        try (ClientChannel channel = session.createExecChannel(wrapped, StandardCharsets.UTF_8, pty, Collections.emptyMap());
                ByteArrayOutputStream stdout = new ByteArrayOutputStream();
                ByteArrayOutputStream stderr = new ByteArrayOutputStream()) {
            channel.setOut(stdout);
            channel.setErr(stderr);
            channel.open().verify(SSH_TIMEOUT);
            channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), COMMAND_TIMEOUT);
            Integer exitStatus = channel.getExitStatus();
            result.setExitCode(exitStatus == null ? -1 : exitStatus);
            result.setStdout(new String(stdout.toByteArray(), StandardCharsets.UTF_8));
            result.setStderr(removeInteractiveShellWarnings(new String(stderr.toByteArray(), StandardCharsets.UTF_8)));
            return result;
        }
    }

    private CommandResult executePrivilegedShellCommand(ClientSession session, String baseCommand, String rootPassword)
            throws Exception {
        CommandResult result = new CommandResult();
        String marker = "__XJTOOL_BATCH_DONE_" + System.currentTimeMillis() + "__";
        String outputStartMarker = "__XJTOOL_BATCH_OUTPUT_START_" + System.currentTimeMillis() + "__";
        StringBuilder commandScript = new StringBuilder();
        commandScript.append("stty -echo 2>/dev/null || true\n")
                .append("if [ \"$(id -u)\" != \"0\" ]; then echo '特权切换失败：未成功切换到 root'; printf '\\n")
                .append(marker).append(":126\\n'; exit; fi\n")
                .append("printf '\\n").append(outputStartMarker).append("\\n'\n")
                .append(baseCommand).append("\n")
                .append("printf '\\n").append(marker).append(":%s\\n' \"$?\"\n")
                .append("stty echo 2>/dev/null || true\n")
                .append("exit\n")
                .append("exit\n");

        PtyChannelConfiguration pty = createPty();
        try (ChannelShell channel = session.createShellChannel(pty, Collections.emptyMap());
                ByteArrayOutputStream stdout = new ByteArrayOutputStream();
                ByteArrayOutputStream stderr = new ByteArrayOutputStream()) {
            channel.setOut(stdout);
            channel.setErr(stderr);
            channel.open().verify(SSH_TIMEOUT);

            OutputStream input = channel.getInvertedIn();
            int beforeSu = readShellOutput(stdout, stderr).length();
            writeShellInput(input, "su - root\n");

            if (!waitForPasswordPrompt(stdout, stderr, beforeSu, 8000L)) {
                result.setExitCode(126);
                result.setStdout(cleanPrivilegedShellOutput(new String(stdout.toByteArray(), StandardCharsets.UTF_8),
                        outputStartMarker, marker, rootPassword, baseCommand));
                result.setStderr(removeInteractiveShellWarnings(new String(stderr.toByteArray(), StandardCharsets.UTF_8)));
                result.setStderr(appendMessage(result.getStderr(), "特权切换失败：未检测到 root 密码提示"));
                channel.close(false);
                return result;
            }

            writeShellInput(input, targetOrDefault(rootPassword, "") + "\n");
            Thread.sleep(350L);
            writeShellInput(input, commandScript.toString());

            long deadline = System.currentTimeMillis() + COMMAND_TIMEOUT;
            while (System.currentTimeMillis() < deadline) {
                String combined = new String(stdout.toByteArray(), StandardCharsets.UTF_8)
                        + "\n" + new String(stderr.toByteArray(), StandardCharsets.UTF_8);
                Integer exitCode = extractMarkedExitCode(combined, marker);
                if (exitCode != null) {
                    result.setExitCode(exitCode);
                    result.setStdout(cleanPrivilegedShellOutput(new String(stdout.toByteArray(), StandardCharsets.UTF_8),
                            outputStartMarker, marker, rootPassword, baseCommand));
                    result.setStderr(removeInteractiveShellWarnings(new String(stderr.toByteArray(), StandardCharsets.UTF_8)));
                    channel.close(false);
                    return result;
                }
                Thread.sleep(120L);
            }

            result.setExitCode(-1);
            result.setStdout(cleanPrivilegedShellOutput(new String(stdout.toByteArray(), StandardCharsets.UTF_8),
                    outputStartMarker, marker, rootPassword, baseCommand));
            result.setStderr(removeInteractiveShellWarnings(new String(stderr.toByteArray(), StandardCharsets.UTF_8)));
            if (isBlank(result.getStderr())) {
                result.setStderr("命令执行超时，未检测到结束标记: " + marker);
            }
            channel.close(false);
            return result;
        }
    }

    private void writeShellInput(OutputStream input, String text) throws Exception {
        input.write(targetOrDefault(text, "").getBytes(StandardCharsets.UTF_8));
        input.flush();
    }

    private boolean waitForPasswordPrompt(ByteArrayOutputStream stdout, ByteArrayOutputStream stderr, int startSize,
            long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String combined = readShellOutput(stdout, stderr);
            String recent = combined.length() > startSize ? combined.substring(startSize) : combined;
            if (containsPasswordPrompt(recent)) {
                return true;
            }
            Thread.sleep(120L);
        }
        return false;
    }

    private String readShellOutput(ByteArrayOutputStream stdout, ByteArrayOutputStream stderr) {
        return new String(stdout.toByteArray(), StandardCharsets.UTF_8)
                + "\n" + new String(stderr.toByteArray(), StandardCharsets.UTF_8);
    }

    private boolean containsPasswordPrompt(String output) {
        if (output == null) {
            return false;
        }
        String normalized = output.toLowerCase();
        return normalized.contains("password")
                || normalized.contains("密码")
                || normalized.contains("口令");
    }

    private String buildBashCommand(String shellScript, boolean interactiveLoginShell) {
        return (interactiveLoginShell ? "bash -ilc " : "bash -lc ") + shellQuote(shellScript);
    }

    private PtyChannelConfiguration createPty() {
        PtyChannelConfiguration pty = new PtyChannelConfiguration();
        pty.setPtyType("xterm");
        pty.setPtyColumns(160);
        pty.setPtyLines(48);
        return pty;
    }

    private Integer extractMarkedExitCode(String output, String marker) {
        if (output == null) {
            return null;
        }
        String normalizedOutput = removeTerminalControlSequences(output);
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?m)^\\s*(?:\\[[^\\r\\n]*\\][#$]\\s*)?"
                        + java.util.regex.Pattern.quote(marker) + ":(\\d+)\\s*$")
                .matcher(normalizedOutput);
        Integer exitCode = null;
        while (matcher.find()) {
            exitCode = Integer.parseInt(matcher.group(1));
        }
        return exitCode;
    }

    private String cleanPrivilegedShellOutput(String stdout, String outputStartMarker, String marker,
            String rootPassword, String baseCommand) {
        if (stdout == null || stdout.isEmpty()) {
            return stdout;
        }
        String cleaned = removeTerminalControlSequences(stdout);
        int outputStart = findMarkerLineEnd(cleaned, outputStartMarker);
        if (outputStart >= 0) {
            cleaned = cleaned.substring(outputStart);
            int outputEnd = findMarkerLineStart(cleaned, marker + ":");
            if (outputEnd >= 0) {
                cleaned = cleaned.substring(0, outputEnd);
            }
        }
        if (!isBlank(rootPassword)) {
            cleaned = cleaned.replace(rootPassword, "");
        }
        if (!isBlank(baseCommand)) {
            cleaned = cleaned.replace(baseCommand, "");
        }
        cleaned = cleaned.replaceAll("(?m)^su - root\\r?\\n?", "")
                .replaceAll("(?m)^" + java.util.regex.Pattern.quote(outputStartMarker) + "\\r?\\n?", "")
                .replaceAll("(?m)^" + java.util.regex.Pattern.quote(marker) + ":\\d+\\r?\\n?", "")
                .replaceAll("(?m)^stty -echo.*\\r?\\n?", "")
                .replaceAll("(?m)^if \\[ \"\\$\\(id -u\\)\" != \"0\" \\].*\\r?\\n?", "")
                .replaceAll("(?m)^printf '\\\\n" + java.util.regex.Pattern.quote(marker) + ".*\\r?\\n?", "")
                .replaceAll("(?m)^printf '\\\\n" + java.util.regex.Pattern.quote(outputStartMarker) + ".*\\r?\\n?", "")
                .replaceAll("(?m)^\\[[^\\r\\n]*\\]\\$\\s*", "")
                .replaceAll("(?m)^\\[[^\\r\\n]*\\]#\\s*", "")
                .replaceAll("(?m)^>\\s*", "")
                .replaceAll("(?m)^exit\\r?\\n?", "");
        return cleaned.trim();
    }

    private int findMarkerLineEnd(String text, String markerPrefix) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?m)^\\s*(?:\\[[^\\r\\n]*\\][#$]\\s*)?"
                        + java.util.regex.Pattern.quote(markerPrefix) + "[^\\r\\n]*\\r?\\n?")
                .matcher(text);
        return matcher.find() ? matcher.end() : -1;
    }

    private int findMarkerLineStart(String text, String markerPrefix) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?m)^\\s*(?:\\[[^\\r\\n]*\\][#$]\\s*)?"
                        + java.util.regex.Pattern.quote(markerPrefix) + "[^\\r\\n]*\\s*$")
                .matcher(text);
        return matcher.find() ? matcher.start() : -1;
    }

    private String removeTerminalControlSequences(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return text
                .replaceAll("\\u001B\\][^\\u0007]*(\\u0007|\\u001B\\\\)", "")
                .replaceAll("\\u001B\\[[0-9;?]*[ -/]*[@-~]", "")
                .replace("\r", "");
    }

    private String appendMessage(String current, String message) {
        if (isBlank(current)) {
            return message;
        }
        return current + "\n" + message;
    }

    private String removeInteractiveShellWarnings(String stderr) {
        if (stderr == null || stderr.isEmpty()) {
            return stderr;
        }
        return stderr.replaceAll("(?m)^bash: cannot set terminal process group \\([^\\r\\n]*\\): [^\\r\\n]*\\r?\\n?", "")
                .replaceAll("(?m)^bash: no job control in this shell\\r?\\n?", "");
    }

    private String renderVariables(String text, ServerInfo server, String targetDir, String targetFile, MultipartFile file) {
        if (text == null) {
            return "";
        }
        String fileName = file != null && file.getOriginalFilename() != null ? file.getOriginalFilename() : "";
        String fileBaseName = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
        Map<String, String> values = new LinkedHashMap<>();
        values.put("host", targetOrDefault(server.getHost(), ""));
        values.put("username", targetOrDefault(server.getUsername(), ""));
        values.put("targetDir", targetOrDefault(targetDir, ""));
        values.put("targetFile", targetOrDefault(targetFile, ""));
        values.put("fileName", fileName);
        values.put("fileBaseName", fileBaseName);
        values.put("timestamp", String.valueOf(System.currentTimeMillis()));
        values.put("date", new SimpleDateFormat("yyyyMMdd").format(new Date()));

        String rendered = text;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            rendered = rendered.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return rendered;
    }

    private String shellQuote(String value) {
        return "'" + targetOrDefault(value, "").replace("'", "'\"'\"'") + "'";
    }

    private String normalizeRemoteDir(String dir) {
        String normalized = targetOrDefault(dir, "/tmp").trim().replace("\\", "/");
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String targetOrDefault(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value;
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

    @Data
    public static class NodeDeployResult {
        private String serverId;
        private String name;
        private String host;
        private String status;
        private boolean success;
        private String message;
        private String uploadStatus;
        private String targetFile;
        private Integer commandExitCode;
        private long durationMs;
        private List<String> logs;

        void fail(String error) {
            this.success = false;
            this.message = error;
            if (this.logs == null) {
                this.logs = new ArrayList<>();
            }
            this.logs.add("[失败] " + error);
        }
    }

    @Data
    public static class CommandResult {
        private int exitCode;
        private String stdout;
        private String stderr;
    }
}
