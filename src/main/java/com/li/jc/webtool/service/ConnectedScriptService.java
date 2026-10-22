package com.li.jc.webtool.service;

import com.li.jc.webtool.config.SshSecurityConfig;
import com.li.jc.webtool.dto.ScriptScanResult;
import com.li.jc.webtool.websocket.WebSshWebSocketHandler;
import lombok.Data;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.WebSocketSession;

import javax.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Service
public class ConnectedScriptService {
    private final SshService ssh;
    private final ScriptImportService importer;
    private final ScriptSecurityService scanner;
    private final SshSecurityConfig security;
    private final DangerousCommandTokenService tokens;
    private final SshAuditService audit;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16));
    private final ExpiringTaskRegistry<Task> tasks = new ExpiringTaskRegistry<>(30 * 60 * 1000L, 100);
    private final Map<String, String> activeSessions = new ConcurrentHashMap<>();

    public ConnectedScriptService(SshService ssh, ScriptImportService importer, ScriptSecurityService scanner,
                                  SshSecurityConfig security, DangerousCommandTokenService tokens, SshAuditService audit) {
        this.ssh = ssh;
        this.importer = importer;
        this.scanner = scanner;
        this.security = security;
        this.tokens = tokens;
        this.audit = audit;
    }

    @Data
    public static class RunRequest {
        private String sessionId;
        private String name;
        private String content;
        private String charset = "UTF-8";
        private int timeoutSeconds = 600;
        private String confirmationToken;
    }

    public Map<String, Object> start(String owner, RunRequest request) throws Exception {
        if (request.getSessionId() == null || request.getSessionId().trim().isEmpty()) {
            throw new IllegalArgumentException("请先连接服务器");
        }
        WebSocketSession session = WebSshWebSocketHandler.findSession(request.getSessionId());
        if (session == null || !owner.equals(session.getAttributes().get("HTTP.SESSION.ID"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该终端连接");
        }
        if (!session.isOpen()) throw new IllegalArgumentException("终端连接已关闭");
        if (!ssh.isSshConnected(session)) throw new IllegalArgumentException("SSH 连接不可用；此功能暂不支持 Agent 模式");
        String content = importer.normalize(request.getContent());
        if (request.getTimeoutSeconds() < 1 || request.getTimeoutSeconds() > 3600) {
            throw new IllegalArgumentException("超时时间应为 1–3600 秒");
        }
        if (!java.util.Arrays.asList("UTF-8", "GBK", "GB2312", "GB18030").contains(request.getCharset())) {
            throw new IllegalArgumentException("不支持的输出编码");
        }
        Charset charset = Charset.forName(request.getCharset());
        ScriptScanResult scan = scanner.deepScanScriptContent("connected-script.sh", content);
        if (!scan.isPassed() && security.isBlockCriticalScriptOps()
                && ("high".equals(scan.getRiskLevel()) || "critical".equals(scan.getRiskLevel()))) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("blocked", true);
            response.put("scan", scan);
            return response;
        }
        String binding = "connected-script:" + session.getId() + ":" + Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        if (!scan.isPassed() || scan.isNeedConfirm()) {
            if (request.getConfirmationToken() == null) {
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("needsConfirmation", true);
                response.put("scan", scan);
                response.put("confirmationToken", tokens.generateToken(owner, binding));
                return response;
            }
            if (!tokens.validateAndConsumeToken(owner, binding, request.getConfirmationToken())) {
                throw new IllegalArgumentException("确认已失效或脚本已修改，请重新执行并确认");
            }
        }
        Task task = new Task(owner, session.getId(), charset);
        if (activeSessions.putIfAbsent(session.getId(), task.id) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前连接已有脚本任务，请等待完成或停止任务");
        }
        try {
            tasks.register(task.id, task);
            task.future = new FutureTask<>(() -> {
                execute(task, session, content, request.getTimeoutSeconds() * 1000L);
                return null;
            });
            audit.logCommand((String) session.getAttributes().get("username"), session.getId(),
                    (String) session.getAttributes().get("SSH.TARGET.HOST"),
                    "[脚本执行 sha256=" + binding.substring(binding.lastIndexOf(':') + 1) + "]\n" + content);
            executor.execute(task.future);
        } catch (RuntimeException e) {
            tasks.remove(task.id);
            activeSessions.remove(session.getId(), task.id);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "无法启动脚本任务，请稍后重试", e);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", task.id);
        return result;
    }

    private void execute(Task task, WebSocketSession session, String content, long timeoutMillis) {
        synchronized (task) {
            if (task.cancelled) return;
            task.status = "running";
        }
        try {
            int code = ssh.executeScript(session, content, task.output, timeoutMillis);
            synchronized (task) {
                task.exitCode = code;
                task.status = code == 0 ? "success" : "failed";
            }
        } catch (Exception e) {
            synchronized (task) {
                task.status = "failed";
                task.message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
        } finally {
            synchronized (task) {
                if (task.cancelled) {
                    task.status = "cancelled";
                    task.message = "已关闭执行通道；已脱离通道的后台进程需在服务器上确认";
                }
            }
            activeSessions.remove(task.sessionId, task.id);
            tasks.markCompleted(task.id);
        }
    }

    public Map<String, Object> status(String owner, String id, int offset) {
        Task task = ownedTask(owner, id);
        synchronized (task) {
            // Decode a complete snapshot, omitting an incomplete character at a running packet boundary.
            byte[] bytes = task.bytes.toByteArray();
            CharBuffer decoded = CharBuffer.allocate(bytes.length + 1);
            task.charset.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                    .decode(ByteBuffer.wrap(bytes), decoded,
                            !"running".equals(task.status) && !"queued".equals(task.status));
            decoded.flip();
            String output = decoded.toString();
            int start = Math.min(Math.max(offset, 0), output.length());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("taskId", task.id);
            result.put("status", task.status);
            result.put("exitCode", task.exitCode);
            result.put("message", task.message);
            result.put("output", output.substring(start));
            result.put("offset", output.length());
            result.put("truncated", task.truncated);
            return result;
        }
    }

    public void cancel(String owner, String id) {
        Task task = ownedTask(owner, id);
        synchronized (task) {
            if (!"running".equals(task.status) && !"queued".equals(task.status)) return;
            task.cancelled = true;
            if (task.future != null) task.future.cancel(true);
            if ("queued".equals(task.status)) {
                executor.remove(task.future);
                task.status = "cancelled";
                activeSessions.remove(task.sessionId, task.id);
                tasks.markCompleted(task.id);
            }
        }
    }

    private Task ownedTask(String owner, String id) {
        Task task = tasks.get(id);
        if (task == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在或已过期");
        if (!owner.equals(task.owner)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该脚本任务");
        return task;
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }

    private static class Task {
        final String id = UUID.randomUUID().toString();
        final String owner;
        final String sessionId;
        final Charset charset;
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        String status = "queued";
        String message;
        Integer exitCode;
        boolean cancelled;
        boolean truncated;
        FutureTask<Void> future;
        final OutputStream output = new OutputStream() {
            @Override public void write(int b) { write(new byte[]{(byte) b}, 0, 1); }
            @Override public void write(byte[] b, int off, int len) {
                synchronized (Task.this) {
                    int count = Math.min(len, 2 * 1024 * 1024 - bytes.size());
                    if (count > 0) bytes.write(b, off, count);
                    if (count < len) truncated = true;
                }
            }
        };

        Task(String owner, String sessionId, Charset charset) {
            this.owner = owner;
            this.sessionId = sessionId;
            this.charset = charset;
        }
    }
}
