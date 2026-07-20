package com.li.jc.webtool.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.li.jc.webtool.model.WebSshData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class AgentTerminalClient {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {};

    public AgentTerminalSession open(WebSshData request, String browserSessionId, AgentTerminalCallback callback)
            throws Exception {
        String baseUrl = requireText(request.getAgentBaseUrl(), "agentBaseUrl is required for agent mode");
        String localSessionId = AgentTerminalSession.newLocalSessionId();
        URI uri = URI.create(toWebSocketUrl(baseUrl));
        AgentWebSocketHandler handler = new AgentWebSocketHandler(localSessionId, request, callback);
        log.info("Opening agent terminal websocket: localSessionId={}, uri={}", localSessionId, maskUri(uri));
        ListenableFuture<WebSocketSession> future = new StandardWebSocketClient()
                .doHandshake(handler, (WebSocketHttpHeaders) null, uri);
        WebSocketSession ws = future.get(10, TimeUnit.SECONDS);
        AgentTerminalSession agentSession = new AgentTerminalSession(localSessionId, ws);
        handler.setAgentSession(agentSession);
        handler.sendConnect(browserSessionId);
        return agentSession;
    }

    private static String maskUri(URI uri) {
        if (uri == null) {
            return "";
        }
        String value = uri.toString();
        return value.replaceAll("(?i)(ws|wss|http|https)://[^\\s/]+", "$1://***");
    }

    private String toWebSocketUrl(String baseUrl) {
        String normalized = baseUrl.trim();
        if (normalized.startsWith("http://")) {
            normalized = "ws://" + normalized.substring("http://".length());
        } else if (normalized.startsWith("https://")) {
            normalized = "wss://" + normalized.substring("https://".length());
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (!normalized.endsWith("/api/v1/terminal/stream")) {
            normalized = normalized + "/api/v1/terminal/stream";
        }
        return normalized;
    }

    private String requireText(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static class AgentWebSocketHandler implements WebSocketHandler {
        private final String localSessionId;
        private final WebSshData request;
        private final AgentTerminalCallback callback;
        private AgentTerminalSession agentSession;
        private WebSocketSession rawSession;

        AgentWebSocketHandler(String localSessionId, WebSshData request, AgentTerminalCallback callback) {
            this.localSessionId = localSessionId;
            this.request = request;
            this.callback = callback;
        }

        void setAgentSession(AgentTerminalSession agentSession) {
            this.agentSession = agentSession;
        }

        void sendConnect(String browserSessionId) throws Exception {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("type", "connect");
            msg.put("sessionId", localSessionId);
            msg.put("browserSessionId", browserSessionId);
            msg.put("agentId", request.getAgentId());
            msg.put("token", request.getAgentToken());
            msg.put("host", request.getHost());
            msg.put("username", request.getUsername());
            msg.put("cols", request.getCols());
            msg.put("rows", request.getRows());
            msg.put("charset", request.getCharset());
            synchronized (rawSession) {
                rawSession.sendMessage(new TextMessage(OBJECT_MAPPER.writeValueAsString(msg)));
            }
        }

        @Override
        public void afterConnectionEstablished(WebSocketSession session) {
            this.rawSession = session;
            log.info("Agent websocket established: localSessionId={}, wsSessionId={}",
                    localSessionId, session.getId());
        }

        @Override
        public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
            if (!(message instanceof TextMessage)) {
                return;
            }
            Map<String, Object> msg = OBJECT_MAPPER.readValue(((TextMessage) message).getPayload(), MAP_TYPE);
            String type = asString(msg.get("type"));
            if ("session".equals(type) || "connected".equals(type)) {
                if (agentSession != null) {
                    agentSession.setRemoteSessionId(asString(msg.get("remoteSessionId")));
                }
                log.info("Agent terminal session ready: localSessionId={}, remoteSessionId={}",
                        localSessionId, asString(msg.get("remoteSessionId")));
                return;
            }
            if ("stdout".equals(type) || "stderr".equals(type) || "output".equals(type)) {
                callback.onOutput(resolvePayload(msg, request.getCharset()));
                return;
            }
            if ("error".equals(type)) {
                String errorMessage = asString(msg.get("message"));
                log.warn("Agent terminal error: localSessionId={}, remoteSessionId={}, message={}",
                        localSessionId, remoteSessionId(), errorMessage);
                callback.onError(errorMessage);
                return;
            }
            if ("exit".equals(type) || "closed".equals(type)) {
                String closeMessage = asString(msg.get("message"));
                log.info("Agent terminal remote closed: localSessionId={}, remoteSessionId={}, type={}, message={}",
                        localSessionId, remoteSessionId(), type, closeMessage);
                callback.onClosed(closeMessage);
            }
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) {
            log.warn("Agent websocket transport error: localSessionId={}, remoteSessionId={}, wsSessionId={}",
                    localSessionId, remoteSessionId(), session == null ? null : session.getId(), exception);
            callback.onError(exception.getMessage());
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) {
            log.info("Agent websocket closed: localSessionId={}, remoteSessionId={}, wsSessionId={}, status={}",
                    localSessionId, remoteSessionId(), session == null ? null : session.getId(), closeStatus);
            callback.onClosed(closeStatus.toString());
        }

        @Override
        public boolean supportsPartialMessages() {
            return false;
        }

        private byte[] resolvePayload(Map<String, Object> msg, String charsetName) {
            String dataBase64 = asString(msg.get("dataBase64"));
            if (dataBase64 != null && !dataBase64.isEmpty()) {
                return Base64.getDecoder().decode(dataBase64);
            }
            String data = asString(msg.get("data"));
            Charset charset = charsetName == null || charsetName.trim().isEmpty()
                    ? StandardCharsets.UTF_8
                    : Charset.forName(charsetName);
            return data == null ? new byte[0] : data.getBytes(charset);
        }

        private String asString(Object value) {
            return value == null ? null : String.valueOf(value);
        }

        private String remoteSessionId() {
            return agentSession == null ? null : agentSession.getRemoteSessionId();
        }
    }
}
