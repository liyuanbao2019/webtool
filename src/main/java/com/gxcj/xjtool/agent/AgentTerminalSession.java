package com.gxcj.xjtool.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class AgentTerminalSession {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final String localSessionId;
    private final WebSocketSession webSocketSession;
    private volatile String remoteSessionId;

    AgentTerminalSession(String localSessionId, WebSocketSession webSocketSession) {
        this.localSessionId = localSessionId;
        this.webSocketSession = webSocketSession;
    }

    public static String newLocalSessionId() {
        return UUID.randomUUID().toString();
    }

    public String getLocalSessionId() {
        return localSessionId;
    }

    public String getRemoteSessionId() {
        return remoteSessionId;
    }

    public void setRemoteSessionId(String remoteSessionId) {
        this.remoteSessionId = remoteSessionId;
    }

    public boolean isOpen() {
        return webSocketSession != null && webSocketSession.isOpen();
    }

    public void sendInput(String data) throws IOException {
        Map<String, Object> msg = baseMessage("stdin");
        msg.put("data", data);
        send(msg);
    }

    public void resize(int cols, int rows) throws IOException {
        Map<String, Object> msg = baseMessage("resize");
        msg.put("cols", cols);
        msg.put("rows", rows);
        send(msg);
    }

    public void ping() throws IOException {
        send(baseMessage("ping"));
    }

    public void close() throws IOException {
        if (isOpen()) {
            send(baseMessage("close"));
            webSocketSession.close();
        }
    }

    private Map<String, Object> baseMessage(String type) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", type);
        msg.put("sessionId", localSessionId);
        if (remoteSessionId != null && !remoteSessionId.isEmpty()) {
            msg.put("remoteSessionId", remoteSessionId);
        }
        return msg;
    }

    private void send(Map<String, Object> msg) throws IOException {
        synchronized (webSocketSession) {
            webSocketSession.sendMessage(new TextMessage(OBJECT_MAPPER.writeValueAsString(msg)));
        }
    }
}
