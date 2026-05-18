package com.gxcj.xjtool.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gxcj.xjtool.model.PressureResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 压测 WebSocket 处理器
 */
@Slf4j
@Component
public class PressureWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();
    
    // 按 sessionId 存储连接
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    
    // 按 sessionId 存储最新结果
    private final Map<String, PressureResult> latestResults = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sessionId = session.getId();
        sessions.put(sessionId, session);
        log.info("压测 WebSocket 连接已建立: {}", sessionId);
        
        // 发送连接成功消息，包含真实的 sessionId
        Map<String, Object> connData = new ConcurrentHashMap<>();
        connData.put("sessionId", sessionId);
        sendMessage(session, new WsMessage("connected", "连接成功", connData));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        log.debug("收到压测 WebSocket 消息: {}", payload);
        
        try {
            WsRequest request = objectMapper.readValue(payload, WsRequest.class);
            
            switch (request.getType()) {
                case "ping":
                    sendMessage(session, new WsMessage("pong", "pong", null));
                    break;
                case "get_result":
                    PressureResult result = latestResults.get(session.getId());
                    if (result != null) {
                        sendMessage(session, new WsMessage("result", "结果已找到", result));
                    }
                    break;
                default:
                    log.warn("未知的消息类型: {}", request.getType());
            }
        } catch (Exception e) {
            log.error("处理 WebSocket 消息失败", e);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String sessionId = session.getId();
        sessions.remove(sessionId);
        latestResults.remove(sessionId);
        log.info("压测 WebSocket 连接已关闭: {}, status: {}", sessionId, status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("压测 WebSocket 传输错误: {}", session.getId(), exception);
        sessions.remove(session.getId());
        latestResults.remove(session.getId());
    }

    /**
     * 发送进度更新
     */
    public void sendProgress(String sessionId, int completed, int total, PressureResult.PerformanceMetrics metrics) {
        WebSocketSession session = sessions.get(sessionId);
        if (session != null && session.isOpen()) {
            try {
                ProgressData data = new ProgressData();
                data.setCompleted(completed);
                data.setTotal(total);
                data.setMetrics(metrics);
                sendMessage(session, new WsMessage("progress", "进度更新", data));
            } catch (Exception e) {
                log.error("发送进度失败", e);
            }
        }
    }

    /**
     * 发送完成消息
     */
    public void sendComplete(String sessionId, PressureResult result) {
        WebSocketSession session = sessions.get(sessionId);
        if (session != null && session.isOpen()) {
            try {
                latestResults.put(sessionId, result);
                sendMessage(session, new WsMessage("complete", "压测完成", result));
            } catch (Exception e) {
                log.error("发送完成消息失败", e);
            }
        }
    }

    /**
     * 发送错误消息
     */
    public void sendError(String sessionId, String errorMessage) {
        WebSocketSession session = sessions.get(sessionId);
        if (session != null && session.isOpen()) {
            try {
                sendMessage(session, new WsMessage("error", errorMessage, null));
            } catch (Exception e) {
                log.error("发送错误消息失败", e);
            }
        }
    }

    /**
     * 发送消息
     */
    private void sendMessage(WebSocketSession session, WsMessage message) {
        if (session.isOpen()) {
            try {
                synchronized (session) {
                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(message)));
                }
            } catch (IOException e) {
                log.error("发送 WebSocket 消息失败", e);
            }
        }
    }

    /**
     * 获取所有活跃连接数
     */
    public int getActiveConnectionCount() {
        return (int) sessions.values().stream().filter(WebSocketSession::isOpen).count();
    }

    /**
     * WebSocket 消息结构
     */
    public static class WsMessage {
        private String type;
        private String message;
        private Object data;

        public WsMessage() {}

        public WsMessage(String type, String message, Object data) {
            this.type = type;
            this.message = message;
            this.data = data;
        }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public Object getData() { return data; }
        public void setData(Object data) { this.data = data; }
    }

    /**
     * WebSocket 请求结构
     */
    public static class WsRequest {
        private String type;
        private Object data;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public Object getData() { return data; }
        public void setData(Object data) { this.data = data; }
    }

    /**
     * 进度数据结构
     */
    public static class ProgressData {
        private int completed;
        private int total;
        private PressureResult.PerformanceMetrics metrics;

        public int getCompleted() { return completed; }
        public void setCompleted(int completed) { this.completed = completed; }
        public int getTotal() { return total; }
        public void setTotal(int total) { this.total = total; }
        public PressureResult.PerformanceMetrics getMetrics() { return metrics; }
        public void setMetrics(PressureResult.PerformanceMetrics metrics) { this.metrics = metrics; }
    }
}
