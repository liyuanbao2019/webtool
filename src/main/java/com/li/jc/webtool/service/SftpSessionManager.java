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
import java.io.Closeable;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Reuses authenticated SSH sessions and prefers an existing terminal session for SFTP operations. */
@Slf4j
@Component
@RequiredArgsConstructor
public class SftpSessionManager {

    private static final long SFTP_TIMEOUT = 30000L;
    static final long SESSION_IDLE_TIMEOUT = 5 * 60 * 1000L;
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
        return execute(info, suUser, suPassword, webSocketSessionId, true, operation);
    }

    /**
     * @param retryOnFailure 失败时是否换一个新连接把整个操作再执行一次。操作会消费一次性的输入
     *                       （例如上传的请求体）时必须传 false，否则重试会写入残缺的数据；
     *                       已断开的连接仍会被丢弃，由调用方决定是否重试。
     */
    public <T> T execute(ServerInfo info, String suUser, String suPassword, String webSocketSessionId,
                         boolean retryOnFailure, SftpOperation<T> operation) throws Exception {
        assertSftpAllowed();
        int attempts = retryOnFailure ? 2 : 1;
        if (isSuMode(info, suUser)) {
            return executeAsSuUser(info, suUser.trim(), suPassword, attempts, operation);
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
        for (int attempt = 0; attempt < attempts; attempt++) {
            PooledSession pooled = acquireSession(info);
            try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(pooled.session)) {
                return operation.apply(sftp);
            } catch (Exception e) {
                // 连接仍然可用说明是业务错误（文件不存在、权限不足等），直接抛出，不重复执行
                if (pooled.session.isOpen()) {
                    throw e;
                }
                lastException = e;
                invalidate(info, pooled);
            } finally {
                pooled.release();
            }
        }
        throw lastException;
    }

    private boolean isSuMode(ServerInfo info, String suUser) {
        return suUser != null && !suUser.trim().isEmpty() && !suUser.trim().equals(info.getUsername());
    }

    private <T> T executeAsSuUser(ServerInfo info, String suUser, String suPassword, int attempts,
                                  SftpOperation<T> operation) throws Exception {
        Exception lastException = null;
        for (int attempt = 0; attempt < attempts; attempt++) {
            PooledSuClient pooled = acquireSuClient(info, suUser, suPassword);
            try {
                return operation.apply(pooled.client);
            } catch (Exception e) {
                // 业务错误（如文件不存在）直接抛出；连接已断开时重建一次
                if (pooled.client.isOpen()) {
                    throw e;
                }
                lastException = e;
                invalidateSuClient(info, suUser, suPassword, pooled);
            } finally {
                pooled.release();
            }
        }
        throw lastException;
    }

    private PooledSuClient acquireSuClient(ServerInfo info, String suUser, String suPassword) throws Exception {
        cleanupExpiredSessionsIfNeeded();
        String key = suClientKey(info, suUser, suPassword);
        PooledSuClient pooled = suClientPool.get(key);
        if (pooled != null && pooled.client.isOpen() && pooled.tryAcquire()) {
            return pooled;
        }
        synchronized (suClientPool) {
            pooled = suClientPool.get(key);
            if (pooled != null && pooled.client.isOpen() && pooled.tryAcquire()) {
                return pooled;
            }
            if (pooled != null) {
                suClientPool.remove(key, pooled);
                pooled.closeWhenIdle(!pooled.client.isOpen(), this::closeQuietly);
            }
            PooledSession base = acquireSession(info);
            SftpClient client;
            try {
                client = SuSftpClient.open(base.session, suUser, suPassword, Duration.ofMillis(SFTP_TIMEOUT));
            } catch (Exception e) {
                if (base.session.isOpen()) {
                    base.release();
                    throw e; // su 本身失败（密码错误等），不必重连
                }
                invalidate(info, base);
                base.release();
                base = acquireSession(info);
                try {
                    client = SuSftpClient.open(base.session, suUser, suPassword, Duration.ofMillis(SFTP_TIMEOUT));
                } catch (Exception retryError) {
                    base.release();
                    throw retryError;
                }
            }
            base.release();
            PooledSuClient created = new PooledSuClient(client, base.session);
            created.tryAcquire();
            suClientPool.put(key, created);
            log.info("SFTP switched by su: host={}, loginUser={}, suUser={}",
                    info.getHost(), info.getUsername(), suUser);
            return created;
        }
    }

    private void invalidateSuClient(ServerInfo info, String suUser, String suPassword, PooledSuClient expected) {
        if (suClientPool.remove(suClientKey(info, suUser, suPassword), expected)) {
            expected.closeWhenIdle(!expected.client.isOpen(), this::closeQuietly);
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

    /** 取出池中的连接并标记为使用中，用完必须调用 {@link Lease#release()}。 */
    private PooledSession acquireSession(ServerInfo info) throws Exception {
        cleanupExpiredSessionsIfNeeded();
        String key = sessionKey(info);
        PooledSession pooled = sessionPool.get(key);
        if (isOpen(pooled) && pooled.tryAcquire()) {
            return pooled;
        }
        synchronized (sessionPool) {
            pooled = sessionPool.get(key);
            if (isOpen(pooled) && pooled.tryAcquire()) {
                return pooled;
            }
            ClientSession session = sshClient.connect(info.getUsername(), info.getHost(), info.getPort())
                    .verify(SFTP_TIMEOUT).getSession();
            try {
                session.addPasswordIdentity(info.getPassword());
                session.auth().verify(SFTP_TIMEOUT);
            } catch (Exception e) {
                closeQuietly(session);
                throw e;
            }
            PooledSession created = new PooledSession(session);
            created.tryAcquire();
            PooledSession previous = sessionPool.put(key, created);
            if (previous != null) {
                previous.closeWhenIdle(!isOpen(previous), this::closeQuietly);
            }
            return created;
        }
    }

    /**
     * 关闭空闲超过 5 分钟的连接。正在传输的连接不算空闲：以前只看“最近一次取用的时间”，
     * 一次传输超过 5 分钟后，任何其他 SFTP 请求触发清理都会把正在传输的连接关掉。
     */
    private void cleanupExpiredSessionsIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastCleanupAt < CLEANUP_INTERVAL) {
            return;
        }
        lastCleanupAt = now;
        cleanupExpired(now);
    }

    void cleanupExpired(long now) {
        suClientPool.forEach((key, pooled) -> {
            boolean dead = !pooled.client.isOpen();
            if ((dead || pooled.isIdleSince(now)) && pooled.retire(dead) && suClientPool.remove(key, pooled)) {
                closeQuietly(pooled.client);
            }
        });
        Set<ClientSession> usedBySu = Collections.newSetFromMap(new IdentityHashMap<>());
        suClientPool.values().forEach(pooled -> usedBySu.add(pooled.session));
        sessionPool.forEach((key, pooled) -> {
            if (usedBySu.contains(pooled.session)) {
                return;
            }
            boolean dead = !isOpen(pooled);
            if ((dead || pooled.isIdleSince(now)) && pooled.retire(dead) && sessionPool.remove(key, pooled)) {
                closeQuietly(pooled.session);
            }
        });
    }

    private void invalidate(ServerInfo info, PooledSession expected) {
        if (sessionPool.remove(sessionKey(info), expected)) {
            // 其他请求可能还在这个连接上传输：已断开的直接关闭，否则等最后一个使用者释放后再关
            expected.closeWhenIdle(!isOpen(expected), this::closeQuietly);
        }
    }

    int pooledSessionCount() {
        return sessionPool.size();
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

    private void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }

    /**
     * 借用计数：active &gt; 0 表示正被请求使用，-1 表示已退役不再借出。
     * 被要求关闭时如果仍在使用，就等最后一个使用者释放时再关闭。
     */
    abstract static class Lease {
        private final AtomicInteger active = new AtomicInteger();
        private volatile long lastAccessAt = System.currentTimeMillis();
        private volatile java.util.function.Consumer<Closeable> pendingClose;

        final boolean tryAcquire() {
            while (true) {
                int current = active.get();
                if (current < 0) {
                    return false;
                }
                if (active.compareAndSet(current, current + 1)) {
                    lastAccessAt = System.currentTimeMillis();
                    return true;
                }
            }
        }

        final void release() {
            lastAccessAt = System.currentTimeMillis();
            int left = active.updateAndGet(current -> current > 0 ? current - 1 : current);
            java.util.function.Consumer<Closeable> closer = pendingClose;
            if (left == 0 && closer != null && active.compareAndSet(0, -1)) {
                closer.accept(resource());
            }
        }

        final boolean isIdleSince(long now) {
            return active.get() == 0 && now - lastAccessAt > SESSION_IDLE_TIMEOUT;
        }

        /** 空闲时（或 force 时）标记为退役，之后不再借出。 */
        final boolean retire(boolean force) {
            if (active.compareAndSet(0, -1)) {
                return true;
            }
            if (force) {
                active.set(-1);
                return true;
            }
            return false;
        }

        final void closeWhenIdle(boolean force, java.util.function.Consumer<Closeable> closer) {
            pendingClose = closer;
            if (retire(force)) {
                closer.accept(resource());
            }
        }

        abstract Closeable resource();
    }

    private static final class PooledSuClient extends Lease {
        private final SftpClient client;
        /** su 客户端所在的 SSH 连接；客户端还在池里时这个连接不能被清理。 */
        private final ClientSession session;

        private PooledSuClient(SftpClient client, ClientSession session) {
            this.client = client;
            this.session = session;
        }

        @Override
        Closeable resource() {
            return client;
        }
    }

    private static final class PooledSession extends Lease {
        private final ClientSession session;

        private PooledSession(ClientSession session) {
            this.session = session;
        }

        @Override
        Closeable resource() {
            return session;
        }
    }
}
