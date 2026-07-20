package com.li.jc.webtool.service;

import com.li.jc.webtool.config.ServerConfig;
import com.li.jc.webtool.model.ServerInfo;
import com.li.jc.webtool.websocket.WebSshWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.WebSocketSession;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Reuses authenticated SSH sessions and prefers an existing terminal session for SFTP operations. */
@Slf4j
@Component
@RequiredArgsConstructor
public class SftpSessionManager {

    private static final long SFTP_TIMEOUT = 30000L;
    private static final long SESSION_IDLE_TIMEOUT = 5 * 60 * 1000L;
    private static final long CLEANUP_INTERVAL = 60 * 1000L;

    private final ServerConfig serverConfig;
    private final SshService sshService;
    private final ConcurrentMap<String, PooledSession> sessionPool = new ConcurrentHashMap<>();
    private volatile long lastCleanupAt;
    private SshClient sshClient;

    @FunctionalInterface
    public interface SftpOperation<T> {
        T apply(SftpClient sftp) throws Exception;
    }

    @PostConstruct
    public void init() {
        sshClient = SshClient.setUpDefaultClient();
        sshClient.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        sshClient.getProperties().put("heartbeat-interval", "30000");
        sshClient.getProperties().put("heartbeat-reply-wait", "10000");
        sshClient.start();
        log.info("SFTP shared SSH client started");
    }

    @PreDestroy
    public void close() {
        sessionPool.values().forEach(value -> closeQuietly(value.session));
        sessionPool.clear();
        if (sshClient != null) {
            try {
                sshClient.stop();
            } catch (Exception e) {
                log.warn("Close shared SFTP SSH client failed", e);
            }
        }
    }

    public <T> T execute(ServerInfo info, String webSocketSessionId, SftpOperation<T> operation) throws Exception {
        assertSftpAllowed();
        try {
            WebSocketSession webSocketSession = terminalSession(webSocketSessionId);
            if (webSocketSession != null) {
                return sshService.executeSftpOnExistingSession(
                        webSocketSession, info.getUsername(), operation::apply);
            }
        } catch (IllegalStateException e) {
            log.debug("Skip terminal SSH session for SFTP: {}", e.getMessage());
        }

        Exception lastException = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            ClientSession session = getOrCreateSession(info);
            try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
                return operation.apply(sftp);
            } catch (Exception e) {
                lastException = e;
                invalidate(info);
            }
        }
        throw lastException;
    }

    private WebSocketSession terminalSession(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return null;
        }
        WebSocketSession session = WebSshWebSocketHandler.findSession(sessionId.trim());
        return session != null && session.isOpen() ? session : null;
    }

    private ClientSession getOrCreateSession(ServerInfo info) throws Exception {
        cleanupExpiredSessionsIfNeeded();
        String key = sessionKey(info);
        PooledSession pooled = sessionPool.get(key);
        if (isOpen(pooled)) {
            pooled.lastAccessAt = System.currentTimeMillis();
            return pooled.session;
        }
        synchronized (sessionPool) {
            pooled = sessionPool.get(key);
            if (isOpen(pooled)) {
                pooled.lastAccessAt = System.currentTimeMillis();
                return pooled.session;
            }
            ClientSession session = sshClient.connect(info.getUsername(), info.getHost(), info.getPort())
                    .verify(SFTP_TIMEOUT).getSession();
            session.addPasswordIdentity(info.getPassword());
            session.auth().verify(SFTP_TIMEOUT);
            sessionPool.put(key, new PooledSession(session, System.currentTimeMillis()));
            return session;
        }
    }

    private void cleanupExpiredSessionsIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastCleanupAt < CLEANUP_INTERVAL) {
            return;
        }
        lastCleanupAt = now;
        sessionPool.forEach((key, pooled) -> {
            if (now - pooled.lastAccessAt > SESSION_IDLE_TIMEOUT && sessionPool.remove(key, pooled)) {
                closeQuietly(pooled.session);
            }
        });
    }

    private void invalidate(ServerInfo info) {
        PooledSession removed = sessionPool.remove(sessionKey(info));
        if (removed != null) {
            closeQuietly(removed.session);
        }
    }

    public void assertSftpAllowed() {
        if (serverConfig != null && serverConfig.getAgent() != null && serverConfig.getAgent().isEnabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "SFTP is disabled while Agent mode is enabled");
        }
    }

    private String sessionKey(ServerInfo info) {
        return info.getHost() + ":" + info.getPort() + ":" + info.getUsername() + ":"
                + Integer.toHexString(Objects.hashCode(info.getPassword()));
    }

    private boolean isOpen(PooledSession pooled) {
        return pooled != null && pooled.session != null && pooled.session.isOpen();
    }

    private void closeQuietly(ClientSession session) {
        if (session == null) {
            return;
        }
        try {
            session.close();
        } catch (Exception ignored) {
        }
    }

    private static final class PooledSession {
        private final ClientSession session;
        private volatile long lastAccessAt;

        private PooledSession(ClientSession session, long lastAccessAt) {
            this.session = session;
            this.lastAccessAt = lastAccessAt;
        }
    }
}
