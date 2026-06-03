package com.gxcj.xjtool.controller;

import com.gxcj.xjtool.model.ServerInfo;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
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
import java.util.Date;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/deploy")
public class DeployController {

    private static final long SSH_TIMEOUT = 30000L;
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
            @RequestParam(value = "file", required = false) MultipartFile file) {

        BatchDeployResponse response = new BatchDeployResponse();
        response.setSuccess(true);
        response.setResults(new ArrayList<>());

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

        for (String serverId : serverIds) {
            NodeDeployResult result = runOne(serverId, targetDir, commands, enableUpload, enableCommand,
                    existsPolicy, chmodExecutable, customPermission, file);
            response.getResults().add(result);
            if (!result.isSuccess()) {
                response.setSuccess(false);
            }
        }

        return response;
    }

    private NodeDeployResult runOne(String serverId, String targetDir, String commands, boolean enableUpload,
            boolean enableCommand, String existsPolicy, boolean chmodExecutable, String customPermission,
            MultipartFile file) {
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

        try (ClientSession session = openSession(server)) {
            String targetFile = null;
            String renderedTargetDir = renderVariables(targetOrDefault(targetDir, ""), server, "", null, file);
            if (enableUpload) {
                targetFile = uploadFile(session, server, renderedTargetDir, existsPolicy, chmodExecutable, customPermission, file, result);
            }
            if (enableCommand && !isBlank(commands)) {
                String rendered = renderVariables(commands, server, renderedTargetDir, targetFile, file);
                CommandResult commandResult = executeCommand(session, rendered);
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
        CommandResult result = new CommandResult();
        String cleanCommand = command == null ? "" : command.replace("\r\n", "\n").replace("\r", "\n");
        String wrapped = "bash -lc " + shellQuote(cleanCommand);
        try (ClientChannel channel = session.createExecChannel(wrapped);
                ByteArrayOutputStream stdout = new ByteArrayOutputStream();
                ByteArrayOutputStream stderr = new ByteArrayOutputStream()) {
            channel.setOut(stdout);
            channel.setErr(stderr);
            channel.open().verify(SSH_TIMEOUT);
            channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), 10 * 60 * 1000L);
            Integer exitStatus = channel.getExitStatus();
            result.setExitCode(exitStatus == null ? -1 : exitStatus);
            result.setStdout(new String(stdout.toByteArray(), StandardCharsets.UTF_8));
            result.setStderr(new String(stderr.toByteArray(), StandardCharsets.UTF_8));
            return result;
        }
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
