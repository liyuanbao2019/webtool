package com.li.jc.webtool.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import javax.annotation.PreDestroy;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
@Slf4j
public class TerminalStreamHandler extends TextWebSocketHandler {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {};

    private final Map<String, RuntimeSession> sessions = new ConcurrentHashMap<>();
    private final ExecutorService ioExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "webtool-agent-io");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    private AgentProperties properties;

    @Override
    protected void handleTextMessage(WebSocketSession ws, TextMessage message) throws Exception {
        String sessionId = ws.getId();
        try {
            Map<String, Object> msg = OBJECT_MAPPER.readValue(message.getPayload(), MAP_TYPE);
            String type = asString(msg.get("type"));
            String requestedSessionId = asString(msg.get("sessionId"));
            if (requestedSessionId != null && !requestedSessionId.isEmpty()) {
                sessionId = requestedSessionId;
            }

            if ("connect".equals(type)) {
                openShell(ws, sessionId, msg);
            } else if ("stdin".equals(type)) {
                writeInput(sessionId, asString(msg.get("data")));
            } else if ("ping".equals(type)) {
                sendJson(ws, "pong", sessionId, null, null);
            } else if ("close".equals(type)) {
                closeRuntimeSession(sessionId);
            } else if ("resize".equals(type)) {
                resize(sessionId, asInteger(msg.get("cols")), asInteger(msg.get("rows")));
            } else {
                log.warn("Unknown terminal message type: sessionId={}, type={}", sessionId, type);
            }
        } catch (Exception e) {
            String reason = rootMessage(e);
            log.error("Failed to handle terminal message: sessionId={}", sessionId, e);
            try {
                sendJson(ws, "error", sessionId, null, "agent internal error: " + reason);
                if (ws != null && ws.isOpen()) {
                    ws.close(CloseStatus.SERVER_ERROR.withReason(shortCloseReason(reason)));
                }
            } catch (Exception sendError) {
                log.debug("Failed to send agent error message: sessionId={}, error={}",
                        sessionId, sendError.getMessage());
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.entrySet().removeIf(entry -> {
            boolean matched = entry.getValue().webSocketSession.getId().equals(session.getId());
            if (matched) {
                entry.getValue().destroy();
            }
            return matched;
        });
    }

    @PreDestroy
    public void destroy() {
        sessions.values().forEach(RuntimeSession::destroy);
        sessions.clear();
        ioExecutor.shutdownNow();
    }

    private void openShell(WebSocketSession ws, String sessionId, Map<String, Object> msg) throws Exception {
        if (!authorized(ws, msg)) {
            sendJson(ws, "error", sessionId, null, "unauthorized agent request");
            ws.close(CloseStatus.NOT_ACCEPTABLE.withReason("unauthorized"));
            return;
        }
        if (sessions.size() >= properties.getCommand().getMaxSessions()) {
            sendJson(ws, "error", sessionId, null, "agent session limit reached");
            return;
        }

        closeRuntimeSession(sessionId);
        int cols = positiveOrDefault(asInteger(msg.get("cols")), 120);
        int rows = positiveOrDefault(asInteger(msg.get("rows")), 40);
        String[] command = defaultShell();
        PtyProcessBuilder builder = new PtyProcessBuilder(command)
                .setEnvironment(System.getenv())
                .setRedirectErrorStream(true)
                .setConsole(false)
                .setInitialColumns(cols)
                .setInitialRows(rows);
        PtyProcess process;
        try {
            process = builder.start();
        } catch (Exception e) {
            String reason = "failed to start shell: " + rootMessage(e);
            log.error("Failed to start PTY shell: sessionId={}, command={}, size={}x{}",
                    sessionId, Arrays.toString(command), cols, rows, e);
            throw new IllegalStateException(reason, e);
        }
        RuntimeSession runtimeSession = new RuntimeSession(ws, process);
        sessions.put(sessionId, runtimeSession);
        sendJson(ws, "connected", sessionId, sessionId, null);
        ioExecutor.execute(() -> pump(process.getInputStream(), ws, sessionId, "stdout"));
        ioExecutor.execute(() -> waitForExit(process, ws, sessionId));
        log.info("PTY shell started: sessionId={}, command={}, size={}x{}",
                sessionId, Arrays.toString(command), cols, rows);
    }

    private boolean authorized(WebSocketSession ws, Map<String, Object> msg) {
        if (!isClientAllowed(ws)) {
            log.warn("Agent request rejected by client IP: remote={}", remoteAddress(ws));
            return false;
        }

        if (!properties.getSecurity().isRequireToken()) {
            log.warn("Agent token validation is disabled. Use this only in isolated test environments.");
            return true;
        }

        String configuredToken = properties.getToken();
        String requestToken = asString(msg.get("token"));
        if (configuredToken == null || configuredToken.trim().isEmpty()) {
            log.warn("Agent token validation is enabled but agent.token is empty");
            return false;
        }
        if (!constantTimeEquals(configuredToken, requestToken)) {
            log.warn("Agent request rejected by invalid token: remote={}", remoteAddress(ws));
            return false;
        }
        return true;
    }

    private boolean isClientAllowed(WebSocketSession ws) {
        if (properties.getSecurity().getAllowedClients() == null
                || properties.getSecurity().getAllowedClients().isEmpty()) {
            return true;
        }
        String remote = remoteAddress(ws);
        if (remote == null || remote.isEmpty()) {
            return false;
        }
        for (String allowed : properties.getSecurity().getAllowedClients()) {
            if (allowed != null && remote.equals(allowed.trim())) {
                return true;
            }
        }
        return false;
    }

    private String remoteAddress(WebSocketSession ws) {
        InetSocketAddress address = ws == null ? null : ws.getRemoteAddress();
        return address == null || address.getAddress() == null ? "" : address.getAddress().getHostAddress();
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) {
            return false;
        }
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = actual.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, actualBytes);
    }

    private void writeInput(String sessionId, String data) throws Exception {
        RuntimeSession runtimeSession = sessions.get(sessionId);
        if (runtimeSession == null) {
            return;
        }
        byte[] bytes = data == null ? new byte[0] : data.getBytes(StandardCharsets.UTF_8);
        synchronized (runtimeSession.processInput) {
            runtimeSession.processInput.write(bytes);
            runtimeSession.processInput.flush();
        }
    }

    private void resize(String sessionId, Integer cols, Integer rows) throws Exception {
        RuntimeSession runtimeSession = sessions.get(sessionId);
        if (runtimeSession == null || cols == null || rows == null || cols <= 0 || rows <= 0) {
            return;
        }
        runtimeSession.process.setWinSize(new WinSize(cols, rows));
        log.debug("PTY resized: sessionId={}, cols={}, rows={}", sessionId, cols, rows);
    }

    private void pump(InputStream input, WebSocketSession ws, String sessionId, String type) {
        byte[] buffer = new byte[8192];
        try {
            int len;
            while ((len = input.read(buffer)) != -1) {
                byte[] data = new byte[len];
                System.arraycopy(buffer, 0, data, 0, len);
                sendJson(ws, type, sessionId, Base64.getEncoder().encodeToString(data), null);
            }
        } catch (Exception e) {
            log.debug("Stream pump ended: sessionId={}, type={}, error={}", sessionId, type, e.getMessage());
        }
    }

    private void waitForExit(PtyProcess process, WebSocketSession ws, String sessionId) {
        try {
            int exitCode = process.waitFor();
            sendJson(ws, "exit", sessionId, null, "exitCode=" + exitCode);
        } catch (Exception e) {
            log.debug("Process wait failed: sessionId={}, error={}", sessionId, e.getMessage());
        } finally {
            closeRuntimeSession(sessionId);
        }
    }

    private void closeRuntimeSession(String sessionId) {
        RuntimeSession runtimeSession = sessions.remove(sessionId);
        if (runtimeSession != null) {
            runtimeSession.destroy();
        }
    }

    private String[] defaultShell() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String configured = os.contains("win") ? properties.getShell().getWindows() : properties.getShell().getLinux();
        return configured.split(",");
    }

    private void sendJson(WebSocketSession ws, String type, String sessionId, String dataBase64, String message) throws Exception {
        if (ws == null || !ws.isOpen()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", type);
        payload.put("remoteSessionId", sessionId);
        if (dataBase64 != null) {
            payload.put("dataBase64", dataBase64);
        }
        if (message != null) {
            payload.put("message", message);
        }
        synchronized (ws) {
            ws.sendMessage(new TextMessage(OBJECT_MAPPER.writeValueAsString(payload)));
        }
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer asInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    private int positiveOrDefault(Integer value, int defaultValue) {
        return value == null || value <= 0 ? defaultValue : value;
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.trim().isEmpty()
                ? current.getClass().getSimpleName()
                : message;
    }

    private String shortCloseReason(String reason) {
        if (reason == null || reason.trim().isEmpty()) {
            return "agent internal error";
        }
        return reason.length() <= 120 ? reason : reason.substring(0, 120);
    }

    private static class RuntimeSession {
        final WebSocketSession webSocketSession;
        final PtyProcess process;
        final OutputStream processInput;

        RuntimeSession(WebSocketSession webSocketSession, PtyProcess process) {
            this.webSocketSession = webSocketSession;
            this.process = process;
            this.processInput = process.getOutputStream();
        }

        void destroy() {
            try {
                processInput.close();
            } catch (Exception ignored) {
            }
            process.destroy();
        }
    }
}
