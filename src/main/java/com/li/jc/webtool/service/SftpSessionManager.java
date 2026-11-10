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
import java.time.Duration;
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
    /** su 模式的 SFTP 客户端（每次新建都要走一遍 su，比较慢，所以复用）。 */
    private final ConcurrentMap<String, PooledSuClient> suClientPool = new ConcurrentHashMap<>();
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
        suClientPool.values().forEach(value -> closeQuietly(value.client));
        suClientPool.clear();
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
        return execute(info, null, null, webSocketSessionId, operation);
    }

    /**
     * suUser 不为空且不同于登录用户时，以登录用户建立 SSH 会话，再通过 su 切换到 suUser 访问文件
     * （用于 root 等禁止 SSH 直接登录的用户）。
     */
    public <T> T execute(ServerInfo info, String suUser, String suPassword, String webSocketSessionId,
                         SftpOperation<T> operation) throws Exception {
        assertSftpAllowed();
        if (isSuMode(info, suUser)) {
            return executeAsSuUser(info, suUser.trim(), suPassword, operation);
        }
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

    private boolean isSuMode(ServerInfo info, String suUser) {
        return suUser != null && !suUser.trim().isEmpty() && !suUser.trim().equals(info.getUsername());
    }

    private <T> T executeAsSuUser(ServerInfo info, String suUser, String suPassword,
                                  SftpOperation<T> operation) throws Exception {
        Exception lastException = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            SftpClient sftp = getOrCreateSuClient(info, suUser, suPassword);
            try {
                return operation.apply(sftp);
            } catch (Exception e) {
                // 业务错误（如文件不存在）直接抛出；连接已断开时重建一次
                if (sftp.isOpen()) {
                    throw e;
                }
                lastException = e;
                invalidateSuClient(info, suUser, suPassword);
            }
        }
        throw lastException;
    }

    private SftpClient getOrCreateSuClient(ServerInfo info, String suUser, String suPassword) throws Exception {
        cleanupExpiredSessionsIfNeeded();
        String key = suClientKey(info, suUser, suPassword);
        PooledSuClient pooled = suClientPool.get(key);
        if (pooled != null && pooled.client.isOpen()) {
            pooled.lastAccessAt = System.currentTimeMillis();
            return pooled.client;
        }
        synchronized (suClientPool) {
            pooled = suClientPool.get(key);
            if (pooled != null && pooled.client.isOpen()) {
                pooled.lastAccessAt = System.currentTimeMillis();
                return pooled.client;
            }
            if (pooled != null) {
                suClientPool.remove(key, pooled);
                closeQuietly(pooled.client);
            }
            ClientSession session = getOrCreateSession(info);
            SftpClient client;
            try {
                client = SuSftpClient.open(session, suUser, suPassword, Duration.ofMillis(SFTP_TIMEOUT));
            } catch (Exception e) {
                if (session.isOpen()) {
                    throw e; // su 本身失败（密码错误等），不必重连
                }
                invalidate(info);
                client = SuSftpClient.open(getOrCreateSession(info), suUser, suPassword,
                        Duration.ofMillis(SFTP_TIMEOUT));
            }
            suClientPool.put(key, new PooledSuClient(client, System.currentTimeMillis()));
            log.info("SFTP switched by su: host={}, loginUser={}, suUser={}",
                    info.getHost(), info.getUsername(), suUser);
            return client;
        }
    }

    private void invalidateSuClient(ServerInfo info, String suUser, String suPassword) {
        PooledSuClient removed = suClientPool.remove(suClientKey(info, suUser, suPassword));
        if (removed != null) {
            closeQuietly(removed.client);
        }
    }

    private String suClientKey(ServerInfo info, String suUser, String suPassword) {
        return sessionKey(info) + "|su:" + suUser + ":" + Integer.toHexString(Objects.hashCode(suPassword));
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
        suClientPool.forEach((key, pooled) -> {
            if ((now - pooled.lastAccessAt > SESSION_IDLE_TIMEOUT || !pooled.client.isOpen())
                    && suClientPool.remove(key, pooled)) {
                closeQuietly(pooled.client);
            }
        });
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

    private void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }

    private static final class PooledSuClient {
        private final SftpClient client;
        private volatile long lastAccessAt;

        private PooledSuClient(SftpClient client, long lastAccessAt) {
            this.client = client;
            this.lastAccessAt = lastAccessAt;
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
