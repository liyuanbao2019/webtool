package com.gxcj.xjtool.controller;

import com.gxcj.xjtool.model.ServerInfo;
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
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private SshClient sshClient;

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
        return stderr.replaceAll("(?m)^bash: cannot set terminal process group \\([^\\r\\n]*\\): Inappropriate ioctl for device\\r?\\n?", "")
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
    public static class NodeDeployResult {
        private String serverId;
        private String name;
        private String host;
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
