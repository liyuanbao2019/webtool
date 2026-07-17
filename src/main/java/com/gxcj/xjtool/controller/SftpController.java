package com.gxcj.xjtool.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gxcj.xjtool.config.ServerConfig;
import com.gxcj.xjtool.model.ServerInfo;
import com.gxcj.xjtool.service.SshService;
import com.gxcj.xjtool.websocket.WebSshWebSocketHandler;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.socket.WebSocketSession;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;

@Slf4j
@RestController
@RequestMapping("/api/sftp")
public class SftpController {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // SFTP 连接和认证超时时间（毫秒）
    private static final long SFTP_TIMEOUT = 30000; // 30秒
    private static final long SESSION_IDLE_TIMEOUT = 5 * 60 * 1000L; // 5分钟空闲超时
    private static final long CLEANUP_INTERVAL = 60 * 1000L; // 60秒清理一次
    private static final long MAX_EDITABLE_FILE_SIZE = 2 * 1024 * 1024L;
    private static final int TEXT_SNIFF_BYTES = 8192;
    private static final double NON_TEXT_RATIO_THRESHOLD = 0.30d;

    private final ConcurrentMap<String, PooledSession> sessionPool = new ConcurrentHashMap<>();
    private volatile long lastCleanupAt = 0L;
    private SshClient sharedSshClient;

    @Autowired
    private ServerConfig serverConfig;

    @Autowired
    private SshService sshService;

    @Data
    private static class PooledSession {
        private final ClientSession session;
        private volatile long lastAccessAt;
    }

    @FunctionalInterface
    private interface SftpAction<T> {
        T apply(SftpClient sftp) throws Exception;
    }

    @Data
    private static class TerminalSftpAttempt<T> {
        private final boolean attempted;
        private final T result;
    }

    @Data
    @lombok.EqualsAndHashCode(callSuper = false)
    public static class SftpRequest extends ServerInfo {
        private String path;
        private String webSocketSessionId;
    }

    @Data
    @lombok.EqualsAndHashCode(callSuper = false)
    public static class SftpTextRequest extends SftpRequest {
        private String content;
    }

    @Data
    @lombok.EqualsAndHashCode(callSuper = false)
    public static class SftpFileOperationRequest extends SftpRequest {
        private String targetPath;
        private String permissions;
        private boolean recursive;
    }

    @Data
    public static class FileEntry {
        private String name;
        private boolean isDir;
        private long size;
        private String mtime;
    }

    @Data
    public static class SftpErrorResponse {
        private String errorCode;
        private String message;
        private String path;
    }

    @Data
    public static class SftpPathStat {
        private String path;
        private boolean directory;
        private long size;
    }

    @PostConstruct
    public void initSharedSshClient() {
        sharedSshClient = SshClient.setUpDefaultClient();
        sharedSshClient.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        sharedSshClient.getProperties().put("heartbeat-interval", "30000");
        sharedSshClient.getProperties().put("heartbeat-reply-wait", "10000");
        sharedSshClient.start();
        log.info("SFTP共享SSH客户端已启动");
    }

    @PreDestroy
    public void destroySharedResources() {
        sessionPool.forEach((k, v) -> closeSessionQuietly(v.getSession()));
        sessionPool.clear();
        if (sharedSshClient != null) {
            try {
                sharedSshClient.stop();
            } catch (Exception e) {
                log.warn("关闭共享SSH客户端失败", e);
            }
        }
    }

    private String buildSessionKey(ServerInfo info) {
        return info.getHost() + ":" + info.getPort() + ":" + info.getUsername() + ":"
                + Integer.toHexString(Objects.hashCode(info.getPassword()));
    }

    private void cleanupExpiredSessionsIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastCleanupAt < CLEANUP_INTERVAL) {
            return;
        }
        lastCleanupAt = now;
        sessionPool.forEach((key, pooled) -> {
            if (now - pooled.getLastAccessAt() > SESSION_IDLE_TIMEOUT) {
                if (sessionPool.remove(key, pooled)) {
                    closeSessionQuietly(pooled.getSession());
                }
            }
        });
    }

    private void closeSessionQuietly(ClientSession session) {
        if (session == null) {
            return;
        }
        try {
            session.close();
        } catch (Exception ignored) {
        }
    }

    private ClientSession createAuthenticatedSession(ServerInfo info) throws Exception {
        ClientSession session = sharedSshClient.connect(info.getUsername(), info.getHost(), info.getPort())
                .verify(SFTP_TIMEOUT).getSession();
        session.addPasswordIdentity(info.getPassword());
        session.auth().verify(SFTP_TIMEOUT);
        return session;
    }

    private ClientSession getOrCreateSession(ServerInfo info) throws Exception {
        cleanupExpiredSessionsIfNeeded();
        String key = buildSessionKey(info);
        PooledSession pooled = sessionPool.get(key);
        if (pooled != null && pooled.getSession() != null && pooled.getSession().isOpen()) {
            pooled.setLastAccessAt(System.currentTimeMillis());
            return pooled.getSession();
        }
        synchronized (sessionPool) {
            pooled = sessionPool.get(key);
            if (pooled != null && pooled.getSession() != null && pooled.getSession().isOpen()) {
                pooled.setLastAccessAt(System.currentTimeMillis());
                return pooled.getSession();
            }
            ClientSession session = createAuthenticatedSession(info);
            PooledSession newPooled = new PooledSession(session);
            newPooled.setLastAccessAt(System.currentTimeMillis());
            sessionPool.put(key, newPooled);
            return session;
        }
    }

    private void invalidateSession(ServerInfo info) {
        String key = buildSessionKey(info);
        PooledSession removed = sessionPool.remove(key);
        if (removed != null) {
            closeSessionQuietly(removed.getSession());
        }
    }

    private String getRequestWebSocketSessionId(ServerInfo info) {
        if (info instanceof SftpRequest) {
            return ((SftpRequest) info).getWebSocketSessionId();
        }
        return null;
    }

    private SftpRequest buildRequest(ServerInfo info, String path, String webSocketSessionId) {
        SftpRequest request = new SftpRequest();
        request.setHost(info.getHost());
        request.setPort(info.getPort());
        request.setUsername(info.getUsername());
        request.setPassword(info.getPassword());
        request.setPath(path);
        request.setWebSocketSessionId(webSocketSessionId);
        return request;
    }

    private <T> TerminalSftpAttempt<T> tryExecuteWithTerminalSession(ServerInfo info, SftpAction<T> action) throws Exception {
        String webSocketSessionId = getRequestWebSocketSessionId(info);
        if (webSocketSessionId == null || webSocketSessionId.trim().isEmpty()) {
            return new TerminalSftpAttempt<>(false, null);
        }
        WebSocketSession webSocketSession = WebSshWebSocketHandler.findSession(webSocketSessionId.trim());
        if (webSocketSession == null || !webSocketSession.isOpen()) {
            return new TerminalSftpAttempt<>(false, null);
        }
        T result = sshService.executeSftpOnExistingSession(webSocketSession, info.getUsername(), action::apply);
        return new TerminalSftpAttempt<>(true, result);
    }

    private void assertSftpAllowed() {
        if (serverConfig != null
                && serverConfig.getAgent() != null
                && serverConfig.getAgent().isEnabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "SFTP is disabled while Agent mode is enabled");
        }
    }

    private <T> T executeWithSftp(ServerInfo info, SftpAction<T> action) throws Exception {
        assertSftpAllowed();
        try {
            TerminalSftpAttempt<T> terminalAttempt = tryExecuteWithTerminalSession(info, action);
            if (terminalAttempt.isAttempted()) {
                return terminalAttempt.getResult();
            }
        } catch (IllegalStateException e) {
            log.debug("Skip terminal SSH session for SFTP: {}", e.getMessage());
        }
        Exception lastException = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            ClientSession session = getOrCreateSession(info);
            try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
                return action.apply(sftp);
            } catch (Exception e) {
                lastException = e;
                invalidateSession(info);
            }
        }
        throw lastException;
    }

    @PostMapping("/list")
    public ResponseEntity<?> list(@RequestBody SftpRequest request) {
        try {
            return executeWithSftp(request, sftp -> {
                String path = normalizeRemotePath(request.getPath());
                String targetPath = resolveReadablePath(sftp, path);
                Iterable<SftpClient.DirEntry> entries = sftp.readDir(targetPath);
                List<FileEntry> fileEntries = new ArrayList<>();
                for (SftpClient.DirEntry entry : entries) {
                    String name = entry.getFilename();
                    if (".".equals(name) || "..".equals(name)) {
                        continue;
                    }
                    FileEntry f = new FileEntry();
                    f.setName(name);
                    f.setDir(entry.getAttributes().isDirectory());
                    f.setSize(entry.getAttributes().getSize());
                    f.setMtime(entry.getAttributes().getModifyTime().toString());
                    fileEntries.add(f);
                }
                return ResponseEntity.ok(fileEntries);
            });
        } catch (Exception e) {
            return buildSftpErrorResponse(e, request.getPath(), "访问目录失败");
        }
    }

    @PostMapping("/download")
    public ResponseEntity<StreamingResponseBody> download(@RequestBody SftpRequest request) throws IOException {
        assertSftpAllowed();
        String requestedPath = normalizeRemotePath(request.getPath());
        long[] fileSize = new long[] { -1L };
        try {
            executeWithSftp(request, sftp -> {
                String realPath = resolveReadablePath(sftp, requestedPath);
                SftpClient.Attributes attrs = sftp.stat(realPath);
                if (attrs.isDirectory()) {
                    throw new IOException("SFTP_ERROR_CODE=IS_DIRECTORY; path=" + realPath);
                }
                fileSize[0] = attrs.getSize();
                return null;
            });
        } catch (Exception e) {
            return buildSftpErrorStreamResponse(e, requestedPath, "下载失败");
        }

        StreamingResponseBody responseBody = outputStream -> {
            try {
                executeWithSftp(request, sftp -> {
                    String realPath = resolveReadablePath(sftp, requestedPath);
                    try (InputStream is = sftp.read(realPath)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = is.read(buffer)) != -1) {
                            outputStream.write(buffer, 0, len);
                        }
                        outputStream.flush();
                    }
                    return null;
                });
            } catch (Exception e) {
                throw new IOException("SFTP下载失败: " + e.getMessage(), e);
            }
        };

        String fileName = requestedPath.substring(requestedPath.lastIndexOf('/') + 1);
        String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8.toString())
                .replace("+", "%20");

        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + encodedFileName + "\"; filename*=UTF-8''" + encodedFileName)
                .contentType(MediaType.APPLICATION_OCTET_STREAM);

        if (fileSize[0] > 0) {
            builder.contentLength(fileSize[0]);
        }

        return builder.body(responseBody);
    }

    @PostMapping("/download-direct")
    public ResponseEntity<StreamingResponseBody> downloadDirect(
            @RequestParam("host") String host,
            @RequestParam("port") Integer port,
            @RequestParam("username") String username,
            @RequestParam("password") String password,
            @RequestParam("path") String path,
            @RequestParam(value = "webSocketSessionId", required = false) String webSocketSessionId) throws IOException {
        SftpRequest request = new SftpRequest();
        request.setHost(host);
        request.setPort(port);
        request.setUsername(username);
        request.setPassword(password);
        request.setPath(path);
        request.setWebSocketSessionId(webSocketSessionId);
        return download(request);
    }

    @PostMapping("/upload")
    public String upload(ServerInfo serverInfo, @RequestParam("path") String path,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "webSocketSessionId", required = false) String webSocketSessionId) throws IOException {
        SftpRequest request = buildRequest(serverInfo, path, webSocketSessionId);
        try {
            executeWithSftp(request, sftp -> {
                String targetPath = path + "/" + file.getOriginalFilename();
                try (OutputStream os = sftp.write(targetPath);
                        InputStream is = file.getInputStream()) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        os.write(buffer, 0, bytesRead);
                    }
                }
                return null;
            });
        } catch (Exception e) {
            throw new IOException("上传失败: " + e.getMessage(), e);
        }
        return "success";
    }

    /**
     * 递归获取文件夹内所有文件列表（用于文件夹下载）
     * 返回文件夹内所有文件的相对路径和属性
     */
    @PostMapping("/list-recursive")
    public ResponseEntity<?> listRecursive(@RequestBody SftpRequest request) {
        try {
            return executeWithSftp(request, sftp -> {
                String basePath = request.getPath();
                List<Map<String, Object>> allFiles = new ArrayList<>();
                listFilesRecursive(sftp, basePath, "", allFiles);
                return ResponseEntity.ok(allFiles);
            });
        } catch (Exception e) {
            log.error("递归获取文件列表失败", e);
            return ResponseEntity.badRequest().body("获取文件列表失败: " + e.getMessage());
        }
    }

    /**
     * 递归列出目录下所有文件
     * 
     * @param sftp         SFTP客户端
     * @param basePath     基础路径
     * @param relativePath 相对路径
     * @param allFiles     存储所有文件信息的列表
     */
    private void listFilesRecursive(SftpClient sftp, String basePath, String relativePath,
            List<Map<String, Object>> allFiles) throws IOException {
        String currentPath = relativePath.isEmpty() ? basePath : basePath + "/" + relativePath;
        Iterable<SftpClient.DirEntry> entries = sftp.readDir(currentPath);

        for (SftpClient.DirEntry entry : entries) {
            String name = entry.getFilename();
            if (name.equals(".") || name.equals("..")) {
                continue;
            }

            String itemRelativePath = relativePath.isEmpty() ? name : relativePath + "/" + name;

            if (entry.getAttributes().isDirectory()) {
                // 递归处理子目录
                listFilesRecursive(sftp, basePath, itemRelativePath, allFiles);
            } else {
                // 添加文件信息
                Map<String, Object> fileInfo = new HashMap<>();
                fileInfo.put("relativePath", itemRelativePath);
                fileInfo.put("size", entry.getAttributes().getSize());
                allFiles.add(fileInfo);
            }
        }
    }

    /**
     * 下载文件夹为 ZIP 压缩包
     * 递归将文件夹内所有文件打包成 ZIP 后下载
     */
    @PostMapping("/download-folder")
    public ResponseEntity<StreamingResponseBody> downloadFolder(@RequestBody SftpRequest request) {
        assertSftpAllowed();
        String folderPath = request.getPath();
        // 获取文件夹名称作为 ZIP 文件名
        String folderName = folderPath.substring(folderPath.lastIndexOf('/') + 1);

        StreamingResponseBody responseBody = outputStream -> {
            ZipOutputStream zipOut = null;

            try {
                zipOut = new ZipOutputStream(outputStream);
                ZipOutputStream finalZipOut = zipOut;
                executeWithSftp(request, sftp -> {
                    addFolderToZip(sftp, folderPath, folderName, finalZipOut);
                    return null;
                });

                zipOut.finish();
                zipOut.flush();
                log.info("文件夹 ZIP 下载完成: {}", folderPath);
            } catch (Exception e) {
                log.error("文件夹 ZIP 下载失败: {}", folderPath, e);
            } finally {
                if (zipOut != null) {
                    try {
                        zipOut.close();
                    } catch (Exception e) {
                        /* ignore */ }
                }
            }
        };

        // 对文件夹名进行 URL 编码，支持中文
        String encodedFolderName;
        try {
            encodedFolderName = URLEncoder.encode(folderName, StandardCharsets.UTF_8.toString())
                    .replace("+", "%20");
        } catch (Exception e) {
            encodedFolderName = folderName;
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + encodedFolderName + ".zip\"; filename*=UTF-8''" + encodedFolderName
                                + ".zip")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(responseBody);
    }

    @PostMapping("/download-folder-direct")
    public ResponseEntity<StreamingResponseBody> downloadFolderDirect(
            @RequestParam("host") String host,
            @RequestParam("port") Integer port,
            @RequestParam("username") String username,
            @RequestParam("password") String password,
            @RequestParam("path") String path,
            @RequestParam(value = "webSocketSessionId", required = false) String webSocketSessionId) {
        SftpRequest request = new SftpRequest();
        request.setHost(host);
        request.setPort(port);
        request.setUsername(username);
        request.setPassword(password);
        request.setPath(path);
        request.setWebSocketSessionId(webSocketSessionId);
        return downloadFolder(request);
    }

    /**
     * 递归将文件夹内容添加到 ZIP 输出流
     * 
     * @param sftp          SFTP客户端
     * @param folderPath    文件夹路径
     * @param zipFolderName ZIP内的文件夹名称
     * @param zipOut        ZIP输出流
     */
    private void addFolderToZip(SftpClient sftp, String folderPath, String zipFolderName, ZipOutputStream zipOut)
            throws IOException {
        Iterable<SftpClient.DirEntry> entries = sftp.readDir(folderPath);

        for (SftpClient.DirEntry entry : entries) {
            String name = entry.getFilename();
            if (name.equals(".") || name.equals("..")) {
                continue;
            }

            String filePath = folderPath + "/" + name;
            String zipEntryName = zipFolderName + "/" + name;

            if (entry.getAttributes().isDirectory()) {
                // 递归处理子目录
                addFolderToZip(sftp, filePath, zipEntryName, zipOut);
            } else {
                // 添加文件到 ZIP
                try (InputStream is = sftp.read(filePath)) {
                    ZipEntry zipEntry = new ZipEntry(zipEntryName);
                    zipOut.putNextEntry(zipEntry);

                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = is.read(buffer)) != -1) {
                        zipOut.write(buffer, 0, len);
                    }
                    zipOut.closeEntry();
                } catch (Exception e) {
                    log.warn("无法添加文件到 ZIP: {}", filePath, e);
                }
            }
        }
    }

    /**
     * 上传文件到指定路径（支持带相对路径的文件，用于文件夹上传）
     * 
     * @param serverInfo   服务器连接信息
     * @param path         目标基础路径
     * @param relativePath 文件相对路径（包含子目录结构）
     * @param file         要上传的文件
     */
    @PostMapping("/upload-with-path")
    public ResponseEntity<?> uploadWithPath(
            ServerInfo serverInfo,
            @RequestParam("path") String path,
            @RequestParam("relativePath") String relativePath,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "webSocketSessionId", required = false) String webSocketSessionId) {
        SftpRequest request = buildRequest(serverInfo, path, webSocketSessionId);

        try {
            executeWithSftp(request, sftp -> {
                // 构建完整的目标路径
                String targetPath = path.endsWith("/") ? path + relativePath : path + "/" + relativePath;

                // 确保目标目录存在
                String targetDir = targetPath.substring(0, targetPath.lastIndexOf('/'));
                ensureDirectoryExists(sftp, targetDir);

                // 上传文件
                try (OutputStream os = sftp.write(targetPath);
                        InputStream is = file.getInputStream()) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        os.write(buffer, 0, bytesRead);
                    }
                }
                log.info("文件上传成功: {}", targetPath);
                return null;
            });
            return ResponseEntity.ok("success");
        } catch (Exception e) {
            log.error("文件上传失败: {}", relativePath, e);
            return ResponseEntity.badRequest().body("上传失败: " + e.getMessage());
        }
    }

    /**
     * 确保目录存在，如果不存在则递归创建
     */
    private void ensureDirectoryExists(SftpClient sftp, String path) throws IOException {
        if (path == null || path.isEmpty() || path.equals("/")) {
            return;
        }

        try {
            sftp.stat(path);
            // 目录已存在
        } catch (IOException e) {
            // 目录不存在，先确保父目录存在
            String parent = path.substring(0, path.lastIndexOf('/'));
            if (!parent.isEmpty()) {
                ensureDirectoryExists(sftp, parent);
            }
            // 创建当前目录
            try {
                sftp.mkdir(path);
                log.info("创建目录: {}", path);
            } catch (IOException ex) {
                // 可能是并发创建导致的，忽略
                log.debug("创建目录可能已存在: {}", path);
            }
        }
    }

    @PostMapping("/read-text")
    public ResponseEntity<?> readTextFile(@RequestBody SftpRequest request) {
        try {
            return executeWithSftp(request, sftp -> {
                SftpClient.Attributes attributes = sftp.stat(request.getPath());
                if (attributes.isDirectory()) {
                    return ResponseEntity.badRequest().body("读取失败: 该路径是目录");
                }
                if (attributes.getSize() > MAX_EDITABLE_FILE_SIZE) {
                    return ResponseEntity.badRequest().body("读取失败: 文件过大，暂不支持在线编辑");
                }
                try (InputStream is = sftp.read(request.getPath());
                        ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = is.read(buffer)) != -1) {
                        baos.write(buffer, 0, len);
                    }
                    Map<String, Object> result = new HashMap<>();
                    result.put("path", request.getPath());
                    result.put("size", attributes.getSize());
                    result.put("content", new String(baos.toByteArray(), StandardCharsets.UTF_8));
                    return ResponseEntity.ok(result);
                }
            });
        } catch (Exception e) {
            log.error("读取文本文件失败: {}", request.getPath(), e);
            return ResponseEntity.badRequest().body("读取失败: " + e.getMessage());
        }
    }

    @PostMapping("/probe-text")
    public ResponseEntity<?> probeTextFile(@RequestBody SftpRequest request) {
        try {
            return executeWithSftp(request, sftp -> {
                SftpClient.Attributes attributes = sftp.stat(request.getPath());
                Map<String, Object> result = new HashMap<>();
                result.put("path", request.getPath());
                result.put("size", attributes.getSize());
                if (attributes.isDirectory()) {
                    result.put("editable", false);
                    result.put("reason", "该路径是目录");
                    return ResponseEntity.ok(result);
                }
                if (attributes.getSize() > MAX_EDITABLE_FILE_SIZE) {
                    result.put("editable", false);
                    result.put("reason", "文件过大，暂不支持在线编辑");
                    return ResponseEntity.ok(result);
                }
                int sniffSize = (int) Math.min(TEXT_SNIFF_BYTES, attributes.getSize());
                if (sniffSize <= 0) {
                    result.put("editable", true);
                    result.put("reason", "空文件");
                    return ResponseEntity.ok(result);
                }
                byte[] bytes = new byte[sniffSize];
                int totalRead = 0;
                try (InputStream is = sftp.read(request.getPath())) {
                    while (totalRead < sniffSize) {
                        int n = is.read(bytes, totalRead, sniffSize - totalRead);
                        if (n <= 0) {
                            break;
                        }
                        totalRead += n;
                    }
                }
                boolean editable = isLikelyTextContent(bytes, totalRead);
                result.put("editable", editable);
                result.put("reason", editable ? "文本内容" : "检测到可能为二进制文件");
                result.put("sniffBytes", totalRead);
                return ResponseEntity.ok(result);
            });
        } catch (Exception e) {
            log.error("探测文本文件失败: {}", request.getPath(), e);
            return ResponseEntity.badRequest().body("探测失败: " + e.getMessage());
        }
    }

    @PostMapping("/save-text")
    public ResponseEntity<?> saveTextFile(@RequestBody SftpTextRequest request) {
        try {
            return executeWithSftp(request, sftp -> {
                byte[] bytes = (request.getContent() == null ? "" : request.getContent())
                        .getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = sftp.write(request.getPath())) {
                    os.write(bytes);
                    os.flush();
                }
                return ResponseEntity.ok("success");
            });
        } catch (Exception e) {
            log.error("保存文本文件失败: {}", request.getPath(), e);
            return ResponseEntity.badRequest().body("保存失败: " + e.getMessage());
        }
    }

    /**
     * 获取用户的家目录路径
     */
    @PostMapping("/home")
    public ResponseEntity<?> getHomeDirectory(@RequestBody SftpRequest serverInfo) {
        try {
            return executeWithSftp(serverInfo, sftp -> {
                String homeDir = sftp.canonicalPath(".");
                return ResponseEntity.ok(homeDir);
            });
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.badRequest().body("获取家目录失败: " + e.getMessage());
        }
    }

    private boolean isLikelyTextContent(byte[] bytes, int length) {
        if (bytes == null || length <= 0) {
            return true;
        }
        int suspicious = 0;
        for (int i = 0; i < length; i++) {
            int b = bytes[i] & 0xFF;
            if (b == 0) {
                return false;
            }
            boolean isCommonControl = b == 9 || b == 10 || b == 13;
            boolean isPrintableAscii = b >= 32 && b <= 126;
            boolean isExtendedUtf8 = b >= 128;
            if (!(isCommonControl || isPrintableAscii || isExtendedUtf8)) {
                suspicious++;
            }
        }
        return ((double) suspicious / (double) length) < NON_TEXT_RATIO_THRESHOLD;
    }

    private String normalizeRemotePath(String path) {
        if (path == null) {
            return ".";
        }
        String trimmed = path.trim();
        return trimmed.isEmpty() ? "." : trimmed;
    }

    private String resolveReadablePath(SftpClient sftp, String path) throws IOException {
        String normalized = normalizeRemotePath(path);
        try {
            return sftp.canonicalPath(normalized);
        } catch (Exception ignored) {
            try {
                sftp.stat(normalized);
                return normalized;
            } catch (Exception e) {
                throw new IOException("SFTP_ERROR_CODE=PATH_NOT_FOUND; path=" + normalized, e);
            }
        }
    }

    private ResponseEntity<SftpErrorResponse> buildSftpErrorResponse(Exception e, String path, String fallbackMessage) {
        String raw = e == null ? "" : String.valueOf(e.getMessage());
        String upper = raw.toUpperCase();
        SftpErrorResponse body = new SftpErrorResponse();
        body.setPath(normalizeRemotePath(path));

        HttpStatus status = HttpStatus.BAD_REQUEST;
        if (upper.contains("SFTP_ERROR_CODE=PATH_NOT_FOUND") || upper.contains("SSH_FX_NO_SUCH_FILE")) {
            body.setErrorCode("PATH_NOT_FOUND");
            body.setMessage("远程路径不存在: " + body.getPath());
            status = HttpStatus.NOT_FOUND;
        } else if (upper.contains("SFTP_ERROR_CODE=IS_DIRECTORY") || upper.contains("SSH_FX_FILE_IS_A_DIRECTORY")) {
            body.setErrorCode("IS_DIRECTORY");
            body.setMessage("目标是目录，不支持按文件处理: " + body.getPath());
            status = HttpStatus.BAD_REQUEST;
        } else if (upper.contains("NOT A DIRECTORY") || upper.contains("SSH_FX_NOT_A_DIRECTORY")) {
            body.setErrorCode("NOT_DIRECTORY");
            body.setMessage("目标不是目录: " + body.getPath());
            status = HttpStatus.BAD_REQUEST;
        } else if (upper.contains("SSH_FX_PERMISSION_DENIED")) {
            body.setErrorCode("PERMISSION_DENIED");
            body.setMessage("权限不足，无法访问: " + body.getPath());
            status = HttpStatus.FORBIDDEN;
        } else {
            body.setErrorCode("SFTP_OPERATION_FAILED");
            body.setMessage(fallbackMessage + ": " + raw);
        }
        log.warn("SFTP操作失败 code={}, path={}, raw={}", body.getErrorCode(), body.getPath(), raw);
        return ResponseEntity.status(status).body(body);
    }

    private ResponseEntity<StreamingResponseBody> buildSftpErrorStreamResponse(Exception e, String path,
            String fallbackMessage) {
        ResponseEntity<SftpErrorResponse> error = buildSftpErrorResponse(e, path, fallbackMessage);
        SftpErrorResponse errorBody = error.getBody();
        StreamingResponseBody body = outputStream -> {
            try {
                byte[] json = OBJECT_MAPPER.writeValueAsBytes(errorBody);
                outputStream.write(json);
                outputStream.flush();
            } catch (Exception io) {
                throw new IOException("写入错误响应失败", io);
            }
        };
        return ResponseEntity.status(error.getStatusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    @PostMapping("/mkdir")
    public ResponseEntity<?> createDirectory(@RequestBody SftpFileOperationRequest request) {
        String path = normalizeRemotePath(request.getPath());
        if ("/".equals(path) || ".".equals(path)) {
            return ResponseEntity.badRequest().body("目录路径无效");
        }
        try {
            return executeWithSftp(request, sftp -> {
                sftp.mkdir(path);
                return ResponseEntity.ok(operationResult("mkdir", path));
            });
        } catch (Exception e) {
            return buildSftpErrorResponse(e, path, "创建目录失败");
        }
    }

    @PostMapping("/rename")
    public ResponseEntity<?> renamePath(@RequestBody SftpFileOperationRequest request) {
        String sourcePath = normalizeRemotePath(request.getPath());
        String targetPath = normalizeRemotePath(request.getTargetPath());
        if ("/".equals(sourcePath) || ".".equals(sourcePath)
                || "/".equals(targetPath) || ".".equals(targetPath)) {
            return ResponseEntity.badRequest().body("源路径或目标路径无效");
        }
        try {
            return executeWithSftp(request, sftp -> {
                sftp.rename(sourcePath, targetPath);
                Map<String, Object> result = operationResult("rename", sourcePath);
                result.put("targetPath", targetPath);
                return ResponseEntity.ok(result);
            });
        } catch (Exception e) {
            return buildSftpErrorResponse(e, sourcePath, "重命名失败");
        }
    }

    @PostMapping("/delete")
    public ResponseEntity<?> deletePath(@RequestBody SftpFileOperationRequest request) {
        String path = normalizeRemotePath(request.getPath());
        if ("/".equals(path) || ".".equals(path) || path.trim().isEmpty()) {
            return ResponseEntity.badRequest().body("禁止删除根目录");
        }
        try {
            return executeWithSftp(request, sftp -> {
                SftpClient.Attributes attributes = sftp.stat(path);
                if (attributes.isDirectory()) {
                    if (request.isRecursive()) {
                        deleteDirectoryRecursively(sftp, path);
                    } else {
                        sftp.rmdir(path);
                    }
                } else {
                    sftp.remove(path);
                }
                return ResponseEntity.ok(operationResult("delete", path));
            });
        } catch (Exception e) {
            return buildSftpErrorResponse(e, path, "删除失败");
        }
    }

    @PostMapping("/chmod")
    public ResponseEntity<?> changePermissions(@RequestBody SftpFileOperationRequest request) {
        String path = normalizeRemotePath(request.getPath());
        if ("/".equals(path) || ".".equals(path)) {
            return ResponseEntity.badRequest().body("禁止修改根目录权限");
        }
        String permissions = request.getPermissions() == null ? "" : request.getPermissions().trim();
        if (!permissions.matches("[0-7]{3,4}")) {
            return ResponseEntity.badRequest().body("权限格式应为 755 或 0755");
        }
        int permissionBits = Integer.parseInt(permissions, 8);
        try {
            return executeWithSftp(request, sftp -> {
                SftpClient.Attributes attributes = sftp.stat(path);
                attributes.setPermissions(permissionBits);
                sftp.setStat(path, attributes);
                Map<String, Object> result = operationResult("chmod", path);
                result.put("permissions", permissions);
                return ResponseEntity.ok(result);
            });
        } catch (Exception e) {
            return buildSftpErrorResponse(e, path, "修改权限失败");
        }
    }

    private Map<String, Object> operationResult(String operation, String path) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("operation", operation);
        result.put("path", path);
        return result;
    }

    private void deleteDirectoryRecursively(SftpClient sftp, String path) throws IOException {
        for (SftpClient.DirEntry entry : sftp.readDir(path)) {
            String name = entry.getFilename();
            if (".".equals(name) || "..".equals(name)) continue;
            String childPath = path.endsWith("/") ? path + name : path + "/" + name;
            if (entry.getAttributes().isDirectory()) {
                deleteDirectoryRecursively(sftp, childPath);
            } else {
                sftp.remove(childPath);
            }
        }
        sftp.rmdir(path);
    }

    @PostMapping("/stat")
    public ResponseEntity<?> statPath(@RequestBody SftpRequest request) {
        String requestedPath = normalizeRemotePath(request.getPath());
        try {
            return executeWithSftp(request, sftp -> {
                String realPath = resolveReadablePath(sftp, requestedPath);
                SftpClient.Attributes attrs = sftp.stat(realPath);
                SftpPathStat stat = new SftpPathStat();
                stat.setPath(realPath);
                stat.setDirectory(attrs.isDirectory());
                stat.setSize(attrs.getSize());
                return ResponseEntity.ok(stat);
            });
        } catch (Exception e) {
            return buildSftpErrorResponse(e, requestedPath, "获取文件信息失败");
        }
    }
}
