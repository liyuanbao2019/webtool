package com.gxcj.xjtool.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gxcj.xjtool.model.WebSshData;
import com.gxcj.xjtool.service.SshService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * WebSocket 处理器
 * 处理 Web SSH 终端的 WebSocket 连接和消息
 * 
 * @author 李金才 (li.jc)
 * @version 1.0.0-SNAPSHOT
 * @since 2026-01-16
 */
@Component
@Slf4j
public class WebSshWebSocketHandler implements WebSocketHandler {

    @Autowired
    private SshService sshService;

    private ObjectMapper objectMapper = new ObjectMapper();

    /**
     * WebSocket会话注册表：通过sessionId快速查找WebSocketSession
     * 用于HTTP API（如脚本安全扫描）需要通过sessionId访问现有SSH连接的场景
     */
    private static final Map<String, WebSocketSession> SESSION_REGISTRY = new ConcurrentHashMap<>();

    /**
     * 注册WebSocket会话
     */
    public static void registerSession(WebSocketSession session) {
        SESSION_REGISTRY.put(session.getId(), session);
    }

    /**
     * 注销WebSocket会话
     */
    public static void unregisterSession(String sessionId) {
        SESSION_REGISTRY.remove(sessionId);
    }

    /**
     * 通过sessionId查找WebSocketSession
     */
    public static WebSocketSession findSession(String sessionId) {
        return SESSION_REGISTRY.get(sessionId);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        registerSession(session);
        log.info("WebSocket connected: {}", session.getId());
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
        if (message instanceof TextMessage) {
            String payload = ((TextMessage) message).getPayload();
            try {
                WebSshData webSshData = objectMapper.readValue(payload, WebSshData.class);
                if ("connect".equals(webSshData.getOperate())) {
                    sshService.initConnection(session, webSshData);
                } else if ("command".equals(webSshData.getOperate())) {
                    sshService.recvHandle(session, webSshData);
                } else if ("ping".equals(webSshData.getOperate())) {
                    // 处理心跳ping消息,更新SSH连接活动时间并返回pong
                    sshService.keepAlive(session);
                    session.sendMessage(new TextMessage("pong"));
                    log.debug("Heartbeat ping received from session: {}", session.getId());
                } else if ("resize".equals(webSshData.getOperate())) {
                    // 处理终端大小调整
                    sshService.resizeTerminal(session, webSshData.getCols(), webSshData.getRows());
                } else if ("charset".equals(webSshData.getOperate())) {
                    // 处理终端字符集切换
                    sshService.updateTerminalCharset(session, webSshData.getCharset());
                }
            } catch (Exception e) {
                log.error("Error parsing message", e);
            }
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("WebSocket transport error", exception);
        sshService.close(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
        unregisterSession(session.getId());
        log.info("WebSocket closed: {}", session.getId());
        sshService.close(session);
    }

    @Override
    public boolean supportsPartialMessages() {
        return false;
    }
}
