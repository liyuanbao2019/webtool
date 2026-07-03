package com.gxcj.xjtool.service.impl;

import com.gxcj.xjtool.config.ServerConfig;
import com.gxcj.xjtool.config.SshSecurityConfig;
import com.gxcj.xjtool.dto.ScriptReadResult;
import com.gxcj.xjtool.model.ServerGroup;
import com.gxcj.xjtool.model.ServerInfo;
import com.gxcj.xjtool.model.WebSshData;
import com.gxcj.xjtool.service.SshService;
import com.gxcj.xjtool.service.SshAuditService;
import com.gxcj.xjtool.service.DangerousCommandTokenService;
import com.gxcj.xjtool.service.ScriptSecurityService;
import com.gxcj.xjtool.config.SshSecurityConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * SSH服务实现类（优化版）
 * 
 * 优化点：
 * 1. 使用固定大小的线程池，避免线程无限增长
 * 2. 共享单一SshClient实例，减少资源消耗
 * 3. 添加连接数限制，防止资源耗尽
 * 4. 异步消息发送，避免阻塞
 * 5. 添加连接超时和清理机制
 * 6. 使用缓冲区合并小数据包，减少网络开销
 * 
 * @author 李金才 (li.jc)
 * @version 1.0.0-SNAPSHOT
 * @since 2026-01-16
 */
@Service
@Slf4j
public class SshServiceImpl implements SshService {

    // 最大并发连接数
    @Value("${ssh.max-connections:50}")
    private int maxConnections;

    // 连接超时时间（毫秒）
    @Value("${ssh.connection-timeout:30000}")
    private long connectionTimeout;

    // 保活间隔时间（秒）
    @Value("${ssh.keepalive-interval:60}")
    private int keepaliveInterval;

    // SSH连接存储（线程安全）
    private final Map<String, SshConnection> sshMap = new ConcurrentHashMap<>();

    // 当前连接数计数器
    private final AtomicInteger connectionCount = new AtomicInteger(0);

    // 共享的SSH客户端（单例，避免重复创建）
    private SshClient sharedClient;

    // 固定大小的线程池，用于处理SSH连接
    private ExecutorService connectionExecutor;

    // 消息发送线程池（单独的线程池避免阻塞）
    private ExecutorService messageExecutor;

    // 定时任务调度器，用于清理僵死连接
    private ScheduledExecutorService cleanupScheduler;

    // SSH审计服务（记录SSH连接和操作日志）
    @Autowired(required = false)
    private SshAuditService sshAuditService;

    // 危险命令Token验证服务
    @Autowired(required = false)
    private DangerousCommandTokenService tokenService;

    // SSH安全配置
    @Autowired
    private SshSecurityConfig securityConfig;

    // 脚本安全扫描服务
    @Autowired(required = false)
    private ScriptSecurityService scriptSecurityService;

    // 服务器配置（用于查找 su/sudo 密码映射）
    @Autowired
    private ServerConfig serverConfig;

    /**
     * SSH连接封装类
     */
    private static class SshConnection {
        ClientSession session;
        ChannelShell channel;
        long lastActivityTime;

        // 缓冲区刷新标记
        private volatile boolean bufferPending = false;

        // vim编辑器状态检测
        private volatile boolean inVimMode = false;
        private StringBuilder outputBuffer = new StringBuilder(1000);
        private long lastVimCheckTime = 0;

        // 用户输入活动检测 - 避免在用户输入命令时发送保活
        private volatile long lastInputTime = 0;
        private volatile boolean commandCompleted = true; // 命令是否已完成（已按回车）

        // 命令缓冲区 - 累积用户输入的命令，直到遇到回车符
        private StringBuilder commandBuffer = new StringBuilder();

        // 目标服务器IP - 用于命令审计
        String targetHost;

        // 用户切换检测 - 用于在su后发送unset TMOUT
        String currentUser;
        Charset charset = StandardCharsets.UTF_8;
        String charsetName = "UTF-8";

        // WebSocket消息解码用的不完整字节暂存区
        // 当SSH数据分包恰好切断了一个多字节UTF-8/GBK字符时，
        // 未解码完的尾部字节会暂存在这里，等下一批数据到来后拼合解码
        byte[] pendingBytes = null;

        /** SSH 登录用户名（远端），用于推断初始家目录 */
        volatile String sshRemoteUsername;
        /**
         * 推断的交互式 shell 当前工作目录（根据用户提交的 cd 命令维护，与侧栏 SFTP 路径无关）
         */
        volatile String shellWorkingDirectory;
        /** su/sudo 用户名 → 密码映射，来自服务器配置 */
        java.util.Map<String, String> suPasswords = new java.util.LinkedHashMap<>();
        /** 最近一次 su/sudo 命令（用于匹配密码提示） */
        volatile String lastSudoSuCommand;

        void updateActivity() {
            this.lastActivityTime = System.currentTimeMillis();
        }

        void updateInputTime() {
            this.lastInputTime = System.currentTimeMillis();
        }
    }

    /**
     * 初始化服务
     */
    @PostConstruct
    public void init() {
        // 创建共享的SSH客户端
        sharedClient = SshClient.setUpDefaultClient();
        sharedClient.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        // 设置连接属性
        sharedClient.getProperties().put("connect-timeout", String.valueOf(connectionTimeout));
        // 设置心跳保活机制,防止连接超时
        sharedClient.getProperties().put("heartbeat-interval", "30000"); // 30秒发送一次心跳
        sharedClient.getProperties().put("heartbeat-reply-wait", "10000"); // 等待心跳响应的时间
        sharedClient.start();

        // 创建固定大小的线程池（核心线程数=CPU核心数*2，最大线程数=maxConnections）
        // 确保核心线程数不超过最大连接数（ThreadPoolExecutor要求corePoolSize <= maximumPoolSize）
        int corePoolSize = Math.min(Runtime.getRuntime().availableProcessors() * 2, maxConnections);
        connectionExecutor = new ThreadPoolExecutor(
                corePoolSize,
                maxConnections,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                new ThreadFactory() {
                    private final AtomicInteger counter = new AtomicInteger(0);

                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "ssh-conn-" + counter.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    }
                },
                new ThreadPoolExecutor.CallerRunsPolicy() // 队列满时在调用线程执行
        );

        // 消息发送线程池
        messageExecutor = Executors.newFixedThreadPool(corePoolSize, r -> {
            Thread t = new Thread(r, "ssh-msg-sender");
            t.setDaemon(true);
            return t;
        });

        // 定时清理僵死连接（每60秒检查一次）
        cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ssh-cleanup");
            t.setDaemon(true);
            return t;
        });
        // 定时清理空闲连接（每 60 秒）
        cleanupScheduler.scheduleAtFixedRate(this::cleanupIdleConnections, 60, 60, TimeUnit.SECONDS);
        // 【临时禁用】定时发送心跳保持连接活跃（从配置文件读取间隔时间）
        // cleanupScheduler.scheduleAtFixedRate(this::sendKeepAlive, keepaliveInterval,
        // keepaliveInterval,
        // TimeUnit.SECONDS);

        log.info("SshService initialized with maxConnections={}, connectionTimeout={}ms, keepaliveInterval={}s",
                maxConnections, connectionTimeout, keepaliveInterval);
    }

    /**
     * 销毁服务
     */
    @PreDestroy
    public void destroy() {
        log.info("Shutting down SshService...");

        // 关闭所有连接
        sshMap.keySet().forEach(this::closeBySessionId);

        // 关闭线程池
        shutdownExecutor(connectionExecutor, "connectionExecutor");
        shutdownExecutor(messageExecutor, "messageExecutor");
        shutdownExecutor(cleanupScheduler, "cleanupScheduler");

        // 关闭SSH客户端
        if (sharedClient != null) {
            try {
                sharedClient.stop();
            } catch (Exception e) {
                log.warn("Error stopping SSH client", e);
            }
        }

        log.info("SshService shutdown complete");
    }

    private void shutdownExecutor(ExecutorService executor, String name) {
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 清理空闲连接
     */
    private void cleanupIdleConnections() {
        long now = System.currentTimeMillis();
        long idleTimeout = connectionTimeout * 2; // 空闲超时时间为连接超时的2倍

        sshMap.forEach((sessionId, connection) -> {
            if (now - connection.lastActivityTime > idleTimeout) {
                log.info("Cleaning up idle connection: {}", sessionId);
                closeBySessionId(sessionId);
            }
        });
    }

    @Override
    public void initConnection(WebSocketSession session, WebSshData webSshData) {
        String sessionId = session.getId();

        // 检查连接数限制
        if (connectionCount.get() >= maxConnections) {
            sendMessage(session, "服务器连接数已达上限，请稍后重试".getBytes(StandardCharsets.UTF_8));
            log.warn("Connection limit reached: {}/{}", connectionCount.get(), maxConnections);
            return;
        }

        // 在线程池中异步建立连接
        connectionExecutor.execute(() -> {
            SshConnection sshConnection = new SshConnection();
            sshConnection.updateActivity();
            // 标记连接是否已成功建立（已加入sshMap）
            boolean connectionEstablished = false;

            try {
                // 使用共享客户端建立会话
                ClientSession clientSession = sharedClient
                        .connect(webSshData.getUsername(), webSshData.getHost(), webSshData.getPort())
                        .verify(connectionTimeout).getSession();

                // 添加调试日志：脱敏显示密码信息
                String password = webSshData.getPassword();
                String maskedPassword = password.length() > 2
                        ? password.charAt(0) + "***" + password.charAt(password.length() - 1) + " (len="
                                + password.length() + ")"
                        : "*** (len=" + password.length() + ")";
                log.info("SSH连接认证: user={}, host={}:{}, password={}",
                        webSshData.getUsername(), webSshData.getHost(), webSshData.getPort(), maskedPassword);

                clientSession.addPasswordIdentity(password);

                if (!clientSession.auth().verify(connectionTimeout).isSuccess()) {
                    sendMessage(session, "认证失败：用户名或密码错误".getBytes(StandardCharsets.UTF_8));
                    // 认证失败，关闭已创建的session
                    try {
                        clientSession.close();
                    } catch (Exception e) {
                        /* ignore */
                    }
                    return;
                }
                sshConnection.session = clientSession;
                sshConnection.sshRemoteUsername = webSshData.getUsername();
                sshConnection.shellWorkingDirectory = defaultUnixHomeForUser(webSshData.getUsername());

                // 从 ServerConfig 中查找此服务器的 su/sudo 密码映射
                boolean suLoaded = false;
                if (serverConfig != null && webSshData.getHost() != null) {
                    for (ServerGroup group : serverConfig.getServerGroups()) {
                        if (group.getServers() == null) continue;
                        for (ServerInfo si : group.getServers()) {
                            if (webSshData.getHost().equals(si.getHost())
                                    && si.getSuPasswords() != null
                                    && !si.getSuPasswords().isEmpty()) {
                                sshConnection.suPasswords.putAll(si.getSuPasswords());
                                suLoaded = true;
                                log.info("已加载 su/sudo 密码映射: host={}, users={}",
                                        si.getHost(), si.getSuPasswords().keySet());
                                break;
                            }
                        }
                    }
                }
                if (!suLoaded) {
                    log.warn("未找到该 host 的 su 密码映射: host={}", webSshData.getHost());
                }

                // 创建Shell通道
                ChannelShell channel = clientSession.createShellChannel();
                channel.setPtyType("xterm-256color");
                // 关键修复：使用前端 xterm.js 传来的真实终端尺寸初始化 PTY
                // PTY 列数必须与 xterm.js 显示列数完全一致，否则：
                // - MySQL/readline 等按 PTY 列数计算光标位置，xterm.js 按实际列数渲染
                // - 粘贴长命令时 ANSI 光标码错位 → 字符互相覆盖 → 显示乱码
                // - su/passwd 等密码提示行中文字符宽度计算错误 → 光标跳位
                int ptyRows = (webSshData.getRows() != null && webSshData.getRows() > 0)
                        ? webSshData.getRows()
                        : 50;
                int ptyCols = (webSshData.getCols() != null && webSshData.getCols() > 0)
                        ? webSshData.getCols()
                        : 220;
                channel.setPtyLines(ptyRows);
                channel.setPtyColumns(ptyCols);
                log.info("PTY initialized: {}x{} (frontend actual size)", ptyCols, ptyRows);

                Charset selectedCharset = resolveCharset(webSshData.getCharset());
                String selectedCharsetName = normalizeCharsetName(webSshData.getCharset());
                sshConnection.charset = selectedCharset;
                sshConnection.charsetName = selectedCharsetName;

                try {
                    String langValue = "UTF-8".equalsIgnoreCase(selectedCharsetName) ? "zh_CN.UTF-8" : "zh_CN.GBK";
                    channel.setEnv("LANG", langValue);
                    channel.setEnv("LC_ALL", langValue);
                    channel.setEnv("LC_CTYPE", langValue);
                } catch (Exception e) {
                    log.warn("Failed to set charset environment variables, charset={}", selectedCharsetName,
                            e);
                }

                sshConnection.channel = channel;

                // 保存目标服务器IP，用于命令审计
                sshConnection.targetHost = webSshData.getHost();

                // 设置输出流（带缓冲）
                channel.setOut(createBufferedOutputStream(session, sshConnection));
                channel.setErr(createBufferedOutputStream(session, sshConnection));

                channel.open().verify(5000);

                // 连接真正建立成功后，才增加连接计数并加入Map
                // 这样确保连接数和sshMap中的连接数量一致
                connectionCount.incrementAndGet();
                sshMap.put(sessionId, sshConnection);
                connectionEstablished = true;

                // 保存默认字符集到 WebSocketSession attributes，供 HTTP API（如脚本安全扫描）读取远程文件时使用
                session.getAttributes().put("charset", sshConnection.charsetName);

                // 发送初始化命令（解决 Linux login profile 初始化过慢，导致自动启动命令在终端输入缓冲区被清空吞掉、从而使 alias
                // vi=vim 等命令无效的问题）
                if (cleanupScheduler != null && !cleanupScheduler.isShutdown()) {
                    cleanupScheduler.schedule(() -> {
                        try {
                            OutputStream input = channel.getInvertedIn();
                            // 1. 禁用服务器的TMOUT自动登出
                            input.write("unset TMOUT\n".getBytes(StandardCharsets.UTF_8));
                            // 2. 设置vi别名指向vim，使vi命令也能享受vim的语法高亮和正确清屏等功能
                            input.write("command -v vim >/dev/null 2>&1 && alias vi='vim'\n"
                                    .getBytes(StandardCharsets.UTF_8));
                            input.flush();
                            log.info("Sent delayed initialization commands for session: {} (unset TMOUT, alias vi=vim)",
                                    sessionId);
                        } catch (Exception e) {
                            log.warn("Failed to send delayed initialization commands for session {}: {}", sessionId,
                                    e.getMessage());
                        }
                    }, 0, TimeUnit.MILLISECONDS);
                }

                log.info("SSH connection established: {} -> {}:{}, total connections: {}",
                        sessionId, webSshData.getHost(), webSshData.getPort(), connectionCount.get());

                // 发送会话ID给前端（用于Token验证）
                try {
                    String sessionIdMsg = String.format("\u001b[0;32m会话ID: %s\u001b[0m\r\n", sessionId);
                    session.sendMessage(new TextMessage(sessionIdMsg));
                } catch (Exception e) {
                    log.warn("Failed to send session ID to frontend: {}", e.getMessage());
                }

                // SSH审计：记录连接成功
                if (sshAuditService != null) {
                    String username = (String) session.getAttributes().get("username");
                    if (username == null) {
                        username = "unknown";
                    }
                    sshAuditService.logConnection(username, sessionId, webSshData.getHost(),
                            webSshData.getPort(), webSshData.getUsername(), true, null);
                }

            } catch (Exception e) {
                log.error("SSH connection failed for session {}", sessionId, e);
                sendMessage(session, ("连接失败: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));

                // SSH审计：记录连接失败
                if (sshAuditService != null) {
                    String username = (String) session.getAttributes().get("username");
                    if (username == null) {
                        username = "unknown";
                    }
                    sshAuditService.logConnection(username, sessionId, webSshData.getHost(),
                            webSshData.getPort(), webSshData.getUsername(), false, e.getMessage());
                }

                // 如果连接已经加入Map，通过closeBySessionId清理
                // 否则直接清理资源（因为不在Map中，closeBySessionId不会做任何事）
                if (connectionEstablished) {
                    closeBySessionId(sessionId);
                } else {
                    // 手动清理资源
                    if (sshConnection.channel != null) {
                        try {
                            sshConnection.channel.close();
                        } catch (Exception ex) {
                            /* ignore */
                        }
                    }
                    if (sshConnection.session != null) {
                        try {
                            sshConnection.session.close();
                        } catch (Exception ex) {
                            /* ignore */
                        }
                    }
                }
            }
        });
    }

    /**
     * 创建带缓冲的输出流
     * 合并小数据包，减少网络开销，同时针对高速数据流优化以避免卡顿
     */
    private OutputStream createBufferedOutputStream(WebSocketSession session, SshConnection connection) {
        return new OutputStream() {
            private static final int BUFFER_SIZE = 8192; // 增大缓冲区到8KB
            private final byte[] buffer = new byte[BUFFER_SIZE];
            private int position = 0;
            private long lastFlushTime = System.currentTimeMillis();

            @Override
            public synchronized void write(int b) throws IOException {
                buffer[position++] = (byte) b;
                if (position >= BUFFER_SIZE) {
                    flushBuffer();
                }
                connection.updateActivity();
            }

            @Override
            public synchronized void write(byte[] b, int off, int len) throws IOException {
                // 检测用户切换（su命令）
                detectUserSwitch(session, b, off, len, connection);

                // 检测vim编辑器状态
                detectVimMode(session, b, off, len, connection);

                long now = System.currentTimeMillis();

                // 如果数据量大于缓冲区，直接发送
                if (len >= BUFFER_SIZE) {
                    flushBuffer();
                    byte[] data = new byte[len];
                    System.arraycopy(b, off, data, 0, len);
                    sendMessageAsync(session, data);
                    lastFlushTime = now;
                } else {
                    // 如果缓冲区放不下，先刷新
                    if (position + len > BUFFER_SIZE) {
                        flushBuffer();
                        lastFlushTime = now;
                    }
                    System.arraycopy(b, off, buffer, position, len);
                    position += len;

                    // 优化：对于高速数据流（上次刷新时间很近），立即刷新避免卡顿
                    // 对于低速数据流，使用短延迟合并数据包
                    long timeSinceLastFlush = now - lastFlushTime;
                    if (timeSinceLastFlush < 50) {
                        // 高速数据流（50ms内有多次写入），立即刷新
                        flushBuffer();
                        lastFlushTime = now;
                    } else if (position > BUFFER_SIZE / 2) {
                        // 缓冲区超过一半，立即刷新
                        flushBuffer();
                        lastFlushTime = now;
                    } else {
                        // 低速数据流，使用短延迟合并
                        scheduleFlush();
                    }
                }
                connection.updateActivity();
            }

            private void flushBuffer() throws IOException {
                if (position > 0) {
                    // 查找安全的刷新位置（不会截断UTF-8字符）
                    int safePosition = findSafeFlushPosition(buffer, position, connection.charset);

                    if (safePosition > 0) {
                        // 发送完整的UTF-8字符
                        byte[] data = new byte[safePosition];
                        System.arraycopy(buffer, 0, data, 0, safePosition);
                        sendMessageAsync(session, data);

                        // 将不完整的UTF-8字节移到缓冲区开头
                        int remaining = position - safePosition;
                        if (remaining > 0) {
                            System.arraycopy(buffer, safePosition, buffer, 0, remaining);
                        }
                        position = remaining;
                    }
                }
            }

            /**
             * 查找安全的刷新位置，确保不会在UTF-8字符中间截断
             * 
             * @param buf 缓冲区
             * @param len 当前数据长度
             * @return 安全的刷新位置（不会截断UTF-8字符的最大位置）
             */
            private int findSafeFlushPosition(byte[] buf, int len, Charset charset) {
                if (len == 0)
                    return 0;
                if (charset != null && !"UTF-8".equalsIgnoreCase(charset.name())) {
                    return findSafeFlushPositionForDoubleByteCharset(buf, len);
                }

                int pos = len;

                // 从末尾开始检查，最多回退3个字节（UTF-8字符最多4个字节，为安全起见检查3个）
                for (int i = 0; i < 4 && pos > 0; i++) {
                    pos--;
                    byte b = buf[pos];

                    // 检查是否是UTF-8字符的起始字节
                    if ((b & 0x80) == 0) {
                        // 0xxxxxxx - ASCII字符（单字节）
                        return pos + 1;
                    } else if ((b & 0xE0) == 0xC0) {
                        // 110xxxxx - 2字节字符的起始
                        // 检查后面是否有足够的字节
                        if (pos + 2 <= len) {
                            return pos + 2;
                        } else {
                            return pos; // 不完整，从这里截断
                        }
                    } else if ((b & 0xF0) == 0xE0) {
                        // 1110xxxx - 3字节字符的起始（中文通常是这种）
                        // 检查后面是否有足够的字节
                        if (pos + 3 <= len) {
                            return pos + 3;
                        } else {
                            return pos; // 不完整，从这里截断
                        }
                    } else if ((b & 0xF8) == 0xF0) {
                        // 11110xxx - 4字节字符的起始
                        if (pos + 4 <= len) {
                            return pos + 4;
                        } else {
                            return pos; // 不完整，从这里截断
                        }
                    } else if ((b & 0xC0) == 0x80) {
                        // 10xxxxxx - 这是多字节字符的延续字节，继续往前找
                        continue;
                    }
                }

                // 如果回退了4个字节还没找到起始字节，说明数据可能有问题
                // 为安全起见，返回原始长度
                return len;
            }

            private int findSafeFlushPositionForDoubleByteCharset(byte[] buf, int len) {
                if (len == 0) {
                    return 0;
                }
                int tailHighBytes = 0;
                for (int i = len - 1; i >= 0; i--) {
                    int b = buf[i] & 0xFF;
                    if (b <= 0x7F) {
                        break;
                    }
                    tailHighBytes++;
                }
                if (tailHighBytes % 2 == 1) {
                    return len - 1;
                }
                return len;
            }

            private void scheduleFlush() {
                // 简单实现：通过标记延迟刷新
                if (!connection.bufferPending) {
                    connection.bufferPending = true;
                    messageExecutor.execute(() -> {
                        try {
                            Thread.sleep(2); // 缩短到2ms，减少延迟
                            synchronized (this) {
                                flushBuffer();
                                lastFlushTime = System.currentTimeMillis();
                            }
                        } catch (Exception e) {
                            // ignore
                        } finally {
                            connection.bufferPending = false;
                        }
                    });
                }
            }

            @Override
            public synchronized void flush() throws IOException {
                flushBuffer();
            }
        };
    }

    @Override
    public void recvHandle(WebSocketSession session, WebSshData webSshData) {
        String command = webSshData.getCommand();
        if (command == null) {
            return;
        }
        log.debug("[SU-AUTO] recvHandle: command={}, hasEnter={}", command, command.contains("\n") || command.contains("\r"));
        SshConnection connection = sshMap.get(session.getId());
        if (connection != null && connection.channel != null) {
            try {
                boolean hasEnter = command.contains("\n") || command.contains("\r");
                String submittedCommandText = hasEnter ? resolveSubmittedCommand(webSshData, connection) : "";
                String commandText = submittedCommandText.trim();
                log.debug("[SU-AUTO] recvHandle: commandText after resolve={}", commandText);
                // Vim 内编辑时回车仅为换行，commandBuffer/前端 commandText 会误当成 shell 行，禁止参与危险/脚本/cd 判断
                if (connection.inVimMode) {
                    submittedCommandText = "";
                    commandText = "";
                }

                // ============================================================
                // 阶段1: 危险命令验证（原有逻辑，保持不变）
                // ============================================================
                if (hasEnter) {
                    // 检查是否是危险命令
                    if (isDangerousCommand(commandText)) {
                        // 验证Token
                        String token = webSshData.getDangerousCommandToken();

                        // 优先获取 HTTP Session ID 进行验证
                        String httpSessionId = (String) session.getAttributes().get("HTTP.SESSION.ID");
                        String verifySessionId = (httpSessionId != null && !httpSessionId.isEmpty()) ? httpSessionId
                                : session.getId();

                        if (tokenService == null ||
                                !tokenService.validateAndConsumeToken(verifySessionId, commandText, token)) {
                            // Token验证失败，拒绝执行
                            log.warn("危险命令被拒绝: verifySessionId={}, command={}, hasToken={}",
                                    verifySessionId, commandText, token != null);

                            // 发送拒绝消息到终端
                            String rejectMsg = "\r\n⚠️  危险命令被系统拒绝：未经授权的命令执行尝试\r\n";
                            sendMessage(session, rejectMsg.getBytes(connection.charset));

                            // 发送Ctrl+C打断信号，彻底清空远程服务器系统的终端输入缓冲区内潜伏的危险残余文本
                            try {
                                OutputStream input = connection.channel.getInvertedIn();
                                input.write(3); // Ctrl+C 的 ASCII 码
                                input.flush();
                            } catch (Exception e) {
                                log.warn("向SSH终端强制发送Ctrl+C清理危险命令缓冲区失败", e);
                            }

                            // 清空后端应用级命令缓冲区
                            connection.commandBuffer.setLength(0);
                            return; // 拒绝执行
                        }
                        log.info("危险命令Token验证通过: sessionId={}, command={}",
                                session.getId(), commandText);
                    }
                }

                // ============================================================
                // 阶段2: 脚本执行拦截（新逻辑）
                // 当用户执行 sh/bash/./ 等脚本执行命令时，
                // 先拦截并扫描脚本内容，发现危险操作则阻断并要求确认
                // ============================================================
                if (hasEnter && !commandText.isEmpty()) {
                    ScriptBlockResult scriptBlock = checkScriptExecution(session, webSshData, commandText, connection);
                    if (scriptBlock != null && scriptBlock.blocked) {
                        // 脚本被阻断，不执行
                        connection.commandBuffer.setLength(0);
                        return;
                    }
                }

                // ============================================================
                // 阶段3: 检测 su/sudo 命令，记录目标用户名用于后续自动填充密码
                // ============================================================
                if (hasEnter) {
                    String targetCandidate = !commandText.isEmpty() ? commandText : command;
                    recordSudoSuTarget(connection, targetCandidate);
                }

                // ============================================================
                // 阶段4: 正常命令执行
                // ============================================================
                if (hasEnter && !commandText.isEmpty()) {
                    updateShellWorkingDirectoryFromCd(connection, commandText);
                }
                // 检测用户输入的命令，辅助vim状态判断
                detectVimFromCommand(session, command, connection);

                OutputStream input = connection.channel.getInvertedIn();
                Charset commandCharset = connection.charset != null ? connection.charset : StandardCharsets.UTF_8;
                input.write(command.getBytes(commandCharset));
                input.flush();
                connection.updateActivity();
                connection.updateInputTime();

                // 检测用户是否提交了命令（按下回车）
                if (hasEnter) {
                    connection.commandCompleted = true; // 命令已提交

                    // SSH审计：记录命令执行（如果启用了命令记录）
                    if (sshAuditService != null) {
                        String username = (String) session.getAttributes().get("username");
                        if (username == null) {
                            username = "unknown";
                        }
                        if (!submittedCommandText.isEmpty()) {
                            sshAuditService.logCommand(username, session.getId(),
                                    connection.targetHost, submittedCommandText);
                        }
                    }
                    connection.commandBuffer.setLength(0);
                } else {
                    connection.commandCompleted = false; // 正在输入中

                    // 累积命令到缓冲区（过滤退格和其他控制字符）
                    for (char c : command.toCharArray()) {
                        if (c == '\b' || c == 127) { // 退格键
                            if (connection.commandBuffer.length() > 0) {
                                connection.commandBuffer.setLength(connection.commandBuffer.length() - 1);
                            }
                        } else if (!Character.isISOControl(c) || c == '\t') {
                            // 允许所有非控制字符（包括中文等Unicode字符）和制表符
                            connection.commandBuffer.append(c);
                        }
                        // 其他控制字符忽略
                    }
                }
            } catch (IOException e) {
                log.error("Failed to send command to SSH", e);
            }
        }
    }

    private String resolveSubmittedCommand(WebSshData webSshData, SshConnection connection) {
        String fromFrontend = cleanCommand(webSshData.getCommandText());
        if (!fromFrontend.isEmpty()) {
            return fromFrontend;
        }
        String dangerousText = cleanCommand(webSshData.getDangerousCommandText());
        if (!dangerousText.isEmpty()) {
            return dangerousText;
        }
        return cleanCommand(connection.commandBuffer.toString());
    }

    /**
     * 异步发送消息 - 已修改为同步发送以保证消息的顺序性。
     * SSH 输出流需要严格的字节顺序，若使用线程池异步投递则可能导致数据片乱序（如缓冲区flush和大数据包乱序），
     * 进而导致终端ANSI光标错位和乱码。
     */
    private void sendMessageAsync(WebSocketSession session, byte[] buffer) {
        sendMessage(session, buffer);
    }

    @Override
    public void sendMessage(WebSocketSession session, byte[] buffer) {
        try {
            if (session.isOpen()) {
                synchronized (session) { // WebSocketSession不是线程安全的
                    SshConnection connection = sshMap.get(session.getId());
                    Charset charset = connection != null && connection.charset != null ? connection.charset
                            : StandardCharsets.UTF_8;

                    // 将上次遗留的不完整字节和本次数据拼合
                    byte[] fullData;
                    if (connection != null && connection.pendingBytes != null && connection.pendingBytes.length > 0) {
                        fullData = new byte[connection.pendingBytes.length + buffer.length];
                        System.arraycopy(connection.pendingBytes, 0, fullData, 0, connection.pendingBytes.length);
                        System.arraycopy(buffer, 0, fullData, connection.pendingBytes.length, buffer.length);
                        connection.pendingBytes = null;
                    } else {
                        fullData = buffer;
                    }

                    // 使用CharsetDecoder安全解码，发现末尾不完整的多字节字符时暂存
                    String decoded = safeDecodeBytes(fullData, charset, connection);
                    if (decoded != null && !decoded.isEmpty()) {
                        session.sendMessage(new TextMessage(decoded));
                    }
                }
            }
        } catch (IOException e) {
            log.error("Failed to send message to WebSocket", e);
        }
    }

    /**
     * 安全解码字节数组为字符串。
     * 如果数据末尾存在不完整的多字节字符（UTF-8中文为3字节、GBK中文为2字节），
     * 则将不完整的尾部字节暂存到connection.pendingBytes中，只返回已完整解码的部分。
     * 这样下一批数据到来时，可以与暂存字节拼合后再解码，避免乱码。
     */
    private String safeDecodeBytes(byte[] data, Charset charset, SshConnection connection) {
        if (data == null || data.length == 0) {
            return "";
        }

        // 查找安全截断位置（不切断多字节字符）
        int safeLen = findSafeDecodeLength(data, data.length, charset);

        if (safeLen < data.length && connection != null) {
            // 有不完整的尾部字节，暂存起来
            int remaining = data.length - safeLen;
            connection.pendingBytes = new byte[remaining];
            System.arraycopy(data, safeLen, connection.pendingBytes, 0, remaining);
        }

        if (safeLen == 0) {
            return "";
        }

        return new String(data, 0, safeLen, charset);
    }

    /**
     * 查找安全的解码长度，确保不会在多字节字符中间截断。
     * 支持UTF-8和GBK/GB2312/GB18030等双字节字符集。
     */
    private int findSafeDecodeLength(byte[] buf, int len, Charset charset) {
        if (len == 0) return 0;

        String charsetName = charset.name().toUpperCase();
        if (charsetName.contains("UTF-8") || charsetName.contains("UTF8")) {
            return findSafeDecodeLengthUTF8(buf, len);
        } else {
            // GBK/GB2312/GB18030等双字节字符集
            return findSafeDecodeLengthDoubleByte(buf, len);
        }
    }

    /**
     * UTF-8安全截断：从末尾检测是否有不完整的多字节序列
     */
    private int findSafeDecodeLengthUTF8(byte[] buf, int len) {
        // 从末尾往前找，最多回退3个字节（UTF-8最多4字节）
        int pos = len;
        for (int i = 0; i < 4 && pos > 0; i++) {
            pos--;
            byte b = buf[pos];
            if ((b & 0x80) == 0) {
                // ASCII，单字节完整
                return pos + 1;
            } else if ((b & 0xE0) == 0xC0) {
                // 2字节UTF-8起始
                return (pos + 2 <= len) ? pos + 2 : pos;
            } else if ((b & 0xF0) == 0xE0) {
                // 3字节UTF-8起始（中文常用）
                return (pos + 3 <= len) ? pos + 3 : pos;
            } else if ((b & 0xF8) == 0xF0) {
                // 4字节UTF-8起始
                return (pos + 4 <= len) ? pos + 4 : pos;
            } else if ((b & 0xC0) == 0x80) {
                // 延续字节，继续往前找起始字节
                continue;
            }
        }
        // 回退4字节仍未找到起始字节，原样返回
        return len;
    }

    /**
     * 双字节字符集（GBK/GB2312/GB18030）安全截断
     */
    private int findSafeDecodeLengthDoubleByte(byte[] buf, int len) {
        if (len == 0) return 0;
        // 统计末尾连续的高位字节数量
        int tailHighBytes = 0;
        for (int i = len - 1; i >= 0; i--) {
            int b = buf[i] & 0xFF;
            if (b <= 0x7F) break;
            tailHighBytes++;
        }
        // 奇数个高位字节说明最后一个双字节字符不完整
        if (tailHighBytes % 2 == 1) {
            return len - 1;
        }
        return len;
    }

    @Override
    public void updateTerminalCharset(WebSocketSession session, String charset) {
        SshConnection connection = sshMap.get(session.getId());
        if (connection == null) {
            return;
        }
        Charset resolved = resolveCharset(charset);
        String normalized = normalizeCharsetName(charset);
        connection.charset = resolved;
        connection.charsetName = normalized;

        // 将字符集保存到 WebSocketSession attributes，供 HTTP API（如脚本安全扫描）使用
        session.getAttributes().put("charset", normalized);

        String notice = String.format("\r\n[系统提示] 终端字符集已切换为: %s\r\n", normalized);
        sendMessage(session, notice.getBytes(resolved));
        log.info("终端字符集已切换: sessionId={}, charset={}", session.getId(), normalized);
    }

    @Override
    public void close(WebSocketSession session) {
        String sessionId = session.getId();
        SshConnection connection = sshMap.get(sessionId);

        // SSH审计：记录连接断开
        if (sshAuditService != null && connection != null) {
            String username = (String) session.getAttributes().get("username");
            if (username == null) {
                username = "unknown";
            }

            // 计算连接时长
            long connectionTime = System.currentTimeMillis() - connection.lastActivityTime;
            sshAuditService.logDisconnection(username, sessionId, connectionTime);
        }

        closeBySessionId(sessionId);
    }

    private void closeBySessionId(String sessionId) {
        SshConnection connection = sshMap.remove(sessionId);
        if (connection != null) {
            // 减少连接计数
            connectionCount.decrementAndGet();

            // 关闭资源
            if (connection.channel != null) {
                try {
                    connection.channel.close();
                } catch (Exception e) {
                    /* ignore */ }
            }
            if (connection.session != null) {
                try {
                    connection.session.close();
                } catch (Exception e) {
                    /* ignore */ }
            }

            log.info("SSH connection closed: {}, remaining connections: {}",
                    sessionId, connectionCount.get());
        }
    }

    /**
     * 获取当前连接数（用于监控）
     */
    public int getCurrentConnectionCount() {
        return connectionCount.get();
    }

    /**
     * 获取最大连接数（用于监控）
     */
    public int getMaxConnections() {
        return maxConnections;
    }

    /**
     * 保持连接活跃,更新最后活动时间
     * 用于心跳机制,防止连接被空闲清理
     */
    @Override
    public void keepAlive(WebSocketSession session) {
        SshConnection connection = sshMap.get(session.getId());
        if (connection != null) {
            connection.updateActivity();
            log.debug("Connection keepalive updated: {}", session.getId());
        }
    }

    /**
     * 通过用户输入的命令辅助检测vim状态
     * 包括检测vim启动命令和vim退出命令
     */

    /**
     * 检查命令是否是危险命令（后端验证）
     */
    private boolean isDangerousCommand(String command) {
        if (command == null || command.trim().isEmpty()) {
            return false;
        }

        // 检查是否启用危险命令确认
        if (securityConfig == null || !securityConfig.isDangerousCommandConfirm()) {
            return false;
        }

        String cmd = command.trim().toLowerCase();
        // 检查命令是否以危险命令开头
        for (String dangerous : securityConfig.getDangerousCommands()) {
            if (cmd.startsWith(dangerous.toLowerCase() + " ") || cmd.equals(dangerous.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 清理命令中的ANSI控制字符和特殊字符
     * 确保前后端命令哈希一致
     */
    private String cleanCommand(String command) {
        if (command == null) {
            return "";
        }
        // 移除ANSI转义序列 (如 [A, [B, [C, [D 等)
        String cleaned = command.replaceAll("\\x1B\\[[0-9;]*[a-zA-Z]", "");
        // 移除简单的控制字符序列 (如 [A)
        cleaned = cleaned.replaceAll("\\[[A-Z]", "");
        // 移除其他控制字符
        cleaned = cleaned.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "");
        return cleaned.trim();
    }

    private void notifyFrontendVimMode(WebSocketSession session, boolean active) {
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            String json = "{\"type\":\"vim_mode\",\"active\":" + active + "}";
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
        } catch (Exception e) {
            log.debug("notify vim_mode to frontend failed: {}", e.getMessage());
        }
    }

    private void detectVimFromCommand(WebSocketSession session, String command, SshConnection connection) {
        try {
            String cmd = command.trim().toLowerCase();

            // 检测vim启动命令
            if (!connection.inVimMode) {
                // 检测各种vim启动方式
                if (cmd.matches("^vi\\s.*") || // vi filename
                        cmd.matches("^vim\\s.*") || // vim filename
                        cmd.matches("^vi$") || // vi (空)
                        cmd.matches("^vim$") || // vim (空)
                        cmd.matches("^nvim\\s.*") || // neovim
                        cmd.matches("^nvim$")) { // neovim (空)

                    // 立即激活vim模式！
                    connection.inVimMode = true;
                    connection.lastVimCheckTime = System.currentTimeMillis();
                    notifyFrontendVimMode(session, true);
                    log.info("✓ Vim模式已激活 (命令检测: {}) - keepalive已暂停", cmd.split("\\s+")[0]);
                }
            } else {
                // 检测vim退出命令（用户按下回车时）
                if (cmd.contains("\n") || cmd.contains("\r")) {
                    String beforeNewline = cmd.split("[\\r\\n]")[0].trim();

                    // vim退出命令检测
                    if (beforeNewline.matches("^:q!?$") || // :q 或 :q!
                            beforeNewline.matches("^:wq!?$") || // :wq 或 :wq!
                            beforeNewline.matches("^:x$") || // :x
                            beforeNewline.matches("^:qa!?$") || // :qa 或 :qa!
                            beforeNewline.matches("^ZZ$")) { // ZZ (normal mode)

                        log.debug("Vim退出命令已检测: {}", beforeNewline);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Error detecting vim from command", e);
        }
    }

    /**
     * 检测用户切换（su命令）并自动发送 unset TMOUT 和密码自动填充
     * 通过分析终端输出中的用户名变化来判断是否切换了用户
     */
    private void detectUserSwitch(WebSocketSession session, byte[] b, int off, int len, SshConnection connection) {
        try {
            String output = new String(b, off, len, connection.charset);

            // === 阶段1：su/sudo 密码提示检测与自动填充 ===
            autoFillSudoPassword(session, output, connection);

            // === 阶段2：命令提示符用户名检测 ===
            // 检测命令提示符中的用户名（格式：[user@host dir]$ 或 user@host:~$）
            Pattern pattern = Pattern.compile("\\[?([a-zA-Z0-9_-]+)@[^\\]\\s]+[\\]\\s].*?[$#]\\s*$", Pattern.MULTILINE);
            Matcher matcher = pattern.matcher(output);

            if (matcher.find()) {
                String detectedUser = matcher.group(1);

                // 如果检测到用户变化
                if (connection.currentUser != null && !connection.currentUser.equals(detectedUser)) {
                    String oldUser = connection.currentUser;
                    connection.currentUser = detectedUser;

                    log.info("用户切换检测: {} -> {}", oldUser, detectedUser);

                    // 自动发送初始化命令（unset TMOUT 和 vi 别名）
                    try {
                        OutputStream input = connection.channel.getInvertedIn();

                        // 1. 禁用TMOUT自动登出
                        input.write("unset TMOUT\n".getBytes(StandardCharsets.UTF_8));

                        // 2. 设置vi别名指向vim
                        input.write(
                                "command -v vim >/dev/null 2>&1 && alias vi='vim'\n".getBytes(StandardCharsets.UTF_8));

                        input.flush();
                        log.info("已为新用户 {} 发送初始化命令 (unset TMOUT, alias vi=vim)", detectedUser);
                    } catch (Exception e) {
                        log.warn("发送初始化命令失败: {}", e.getMessage());
                    }
                } else if (connection.currentUser == null) {
                    // 首次检测到用户
                    connection.currentUser = detectedUser;
                    log.debug("初始用户: {}", detectedUser);
                }
            }
        } catch (Exception e) {
            log.debug("用户切换检测出错", e);
        }
    }

    /**
     * 检测终端输出中的 su/sudo 密码提示，并自动填充密码
     * 支持的提示格式：
     * - [sudo] password for user:
     * - [su - user] password:
     * - Password: / password: (英文)
     * - 密码：/ 密码: (中文)
     *
     * 目标用户名通过 recordSudoSuTarget 在用户敲回车时记录（而非从输出解析），
     * 这样即使服务器输出中没有用户名信息也能正确填充
     */
    private void autoFillSudoPassword(WebSocketSession session, String output, SshConnection connection) {
        log.debug("[SU-AUTO] autoFillSudoPassword called. suPasswords.size={}, lastSudoSuCommand={}, channelOpen={}",
                connection.suPasswords.size(), connection.lastSudoSuCommand,
                connection.channel != null && connection.channel.isOpen());

        if (connection.suPasswords.isEmpty()) {
            log.debug("[SU-AUTO] suPasswords 为空，跳过");
            return;
        }
        if (connection.channel == null || !connection.channel.isOpen()) {
            log.debug("[SU-AUTO] channel 未打开，跳过");
            return;
        }
        if (connection.lastSudoSuCommand == null) {
            log.debug("[SU-AUTO] lastSudoSuCommand 为空（用户没敲 su/sudo 命令），跳过");
            return;
        }

        String targetUser = connection.lastSudoSuCommand;
        log.debug("[SU-AUTO] 目标用户: {}", targetUser);

        // 检测密码提示（从输出中找）
        String[] lines = output.split("\\r?\\n");
        boolean foundPrompt = false;
        for (String line : lines) {
            String l = line.trim();

            // [sudo] password for oracle:
            if (l.matches("(?i)\\[sudo\\]\\s*password\\s+for\\s+[a-zA-Z0-9_-]+.*")) {
                foundPrompt = true;
                break;
            }
            // [su - oracle] password:
            if (l.matches("(?i)\\[su.*\\]\\s*password.*")) {
                foundPrompt = true;
                break;
            }
            // 纯 "Password:" 或 "密码:"
            if (l.equalsIgnoreCase("Password:") || l.equalsIgnoreCase("password:")
                    || l.equals("密码：") || l.equals("密码:")) {
                foundPrompt = true;
                break;
            }
        }

        if (!foundPrompt) {
            log.debug("[SU-AUTO] 未检测到密码提示行，跳过（output 前100字符: {})", output.length() > 100 ? output.substring(0, 100) : output);
            return;
        }

        log.debug("[SU-AUTO] 检测到密码提示，targetUser={}", targetUser);
        // 用 lastSudoSuCommand 中记录的用户名查密码
        String password = connection.suPasswords.get(targetUser.toLowerCase());
        if (password == null) {
            log.debug("[SU-AUTO] 密码未找到: targetUser={}, suPasswords keys={}", targetUser, connection.suPasswords.keySet());
            return;
        }
        try {
            OutputStream input = connection.channel.getInvertedIn();
            String masked = password.length() > 2
                    ? password.charAt(0) + "***" + password.charAt(password.length() - 1)
                    : "***";
            log.info("自动填充 su/sudo 密码: target={}, pass={}", targetUser, masked);
            input.write((password + "\n").getBytes(connection.charset));
            input.flush();
            connection.lastSudoSuCommand = null;
        } catch (Exception e) {
            log.warn("自动填充密码失败: {}", e.getMessage());
        }
    }

    /**
     * 从用户敲的命令中解析并记录 su/sudo 目标用户名
     * 在 recvHandle 中调用，用户按回车后、发送命令前执行
     * 这样即使输出中解析失败，也能可靠地获取目标用户
     */
    private void recordSudoSuTarget(SshConnection connection, String commandText) {
        log.debug("[SU-AUTO] recordSudoSuTarget ENTRY: commandText={}", commandText);
        if (commandText == null || commandText.trim().isEmpty()) {
            log.debug("[SU-AUTO] recordSudoSuTarget: commandText 为空，跳过");
            return;
        }

        // 模拟退格处理：去掉 \b 及其前一个字符
        String cleaned = simulateBackspace(commandText);
        String lower = cleaned.toLowerCase().trim();

        // su 或 su - （无目标用户 → 切换到 root）
        if (lower.equals("su") || lower.equals("su -") || lower.equals("su-") || lower.matches("su\\s+-[a-z]*")) {
            connection.lastSudoSuCommand = "root";
            log.debug("[SU-AUTO] recordSudoSuTarget: 检测到 su 命令，目标: root, cleaned={}", cleaned);
            return;
        }

        // su username / su - username / su - username
        if (lower.startsWith("su ")) {
            String rest = lower.substring(3).trim();
            // 去掉开头的 -xxx 参数
            if (rest.startsWith("-") && !rest.startsWith("-password") && !rest.startsWith("-p")) {
                int spaceIdx = rest.indexOf(' ');
                if (spaceIdx > 0) {
                    rest = rest.substring(spaceIdx + 1).trim();
                } else {
                    rest = "";
                }
            }
            if (!rest.isEmpty()) {
                String target = rest.split("\\s+")[0];
                if (!target.isEmpty()) {
                    connection.lastSudoSuCommand = target;
                    log.debug("[SU-AUTO] recordSudoSuTarget: 检测到 su 命令，目标: {}, cleaned={}", target, cleaned);
                    return;
                }
            }
        }

        // sudo -u username
        if (lower.startsWith("sudo ") || lower.startsWith("sudo\t")) {
            String rest = lower.substring(5).trim();
            // 跳过 -u / -i 等选项
            if (rest.startsWith("-")) {
                int spaceIdx = rest.indexOf(' ');
                if (spaceIdx > 0) {
                    rest = rest.substring(spaceIdx + 1).trim();
                } else {
                    rest = "";
                }
            }
            if (!rest.isEmpty()) {
                String target = rest.split("\\s+")[0];
                if (!target.isEmpty()) {
                    connection.lastSudoSuCommand = target;
                    log.debug("[SU-AUTO] recordSudoSuTarget: 检测到 sudo 命令，目标: {}, cleaned={}", target, cleaned);
                }
            }
        }
    }

    /**
     * 模拟退格处理：移除 \b 字符及其前一个字符
     * 例如 "su\b-\b root" -> "su root"
     */
    private String simulateBackspace(String s) {
        if (s == null || !s.contains("\b")) return s;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\b') {
                if (sb.length() > 0) sb.deleteCharAt(sb.length() - 1);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 检测vim编辑器状态（增强版）
     * 通过分析终端输出来判断是否进入或退出vim编辑模式
     * 使用多种特征进行更可靠的检测
     */
    private void detectVimMode(WebSocketSession session, byte[] b, int off, int len, SshConnection connection) {
        try {
            String output = new String(b, off, len, connection.charset);

            // 将输出添加到缓冲区
            connection.outputBuffer.append(output);

            // 保持缓冲区在合理大小（最多保留最近2000个字符，增加检测准确性）
            if (connection.outputBuffer.length() > 2000) {
                connection.outputBuffer.delete(0, connection.outputBuffer.length() - 2000);
            }

            String bufferContent = connection.outputBuffer.toString();

            // 检测vim启动的特征（更全面的检测）
            if (!connection.inVimMode) {
                // vim启动特征：
                // 1. vim的欢迎信息
                // 2. vim的模式显示
                // 3. vim的底部状态行特征
                // 4. vim使用的特殊ANSI转义序列（光标定位、清屏等）
                boolean hasVimWelcome = bufferContent.contains("VIM - Vi IMproved") ||
                        bufferContent.contains("Vi IMproved");

                boolean hasVimMode = bufferContent.contains("-- INSERT --") ||
                        bufferContent.contains("-- VISUAL --") ||
                        bufferContent.contains("-- REPLACE --") ||
                        bufferContent.contains("-- NORMAL --") ||
                        // 中文vim模式支持
                        bufferContent.contains("-- 插入 --") ||
                        bufferContent.contains("-- 可视 --") ||
                        bufferContent.contains("-- 替换 --");

                boolean hasVimHelp = bufferContent.contains("type  :q<Enter>  to exit") ||
                        bufferContent.contains("type  :help");

                // vim特有的波浪线标记（空行标记）
                boolean hasVimTildes = output.contains("~\r\n") || output.contains("~\n");

                // vim启动时的清屏序列：ESC[H ESC[2J 或类似
                boolean hasVimClearScreen = output.contains("\u001b[H\u001b[2J") ||
                        output.contains("\u001b[2J") ||
                        output.contains("\u001b[?1049h"); // 切换到备用缓冲区

                // 调试日志改为debug级别，避免刷屏
                log.debug("Vim检测状态: hasVimMode={}", hasVimMode);

                if (hasVimWelcome || hasVimMode ||
                        (hasVimHelp && hasVimTildes) ||
                        (hasVimClearScreen && hasVimTildes)) {

                    connection.inVimMode = true;
                    connection.lastVimCheckTime = System.currentTimeMillis();
                    notifyFrontendVimMode(session, true);
                    log.info("✓ Vim模式已激活 - keepalive已暂停");
                }
            } else {
                // 检测vim退出的特征（更严格的检测）

                // 1. vim退出到正常缓冲区的转义序列
                boolean hasExitSequence = output.contains("\u001b[?1049l");

                // 2. 检测shell提示符（更精确的匹配）
                String[] lines = bufferContent.split("\\r?\\n");
                boolean hasShellPrompt = false;

                if (lines.length > 0) {
                    // 检查最后几行是否有shell提示符
                    for (int i = Math.max(0, lines.length - 3); i < lines.length; i++) {
                        String line = lines[i].trim();
                        // 检测常见的shell提示符格式
                        if (line.matches(".*[$#]\\s*$") ||
                                line.matches(".*][$#]\\s*$") ||
                                line.matches("^[$#]\\s*$")) {
                            hasShellPrompt = true;
                            break;
                        }
                    }
                }

                // 3. 检测是否不再有vim特征（含中文界面）
                long timeSinceLastCheck = System.currentTimeMillis() - connection.lastVimCheckTime;
                boolean noVimFeatures = !bufferContent.contains("-- INSERT --") &&
                        !bufferContent.contains("-- VISUAL --") &&
                        !bufferContent.contains("-- REPLACE --") &&
                        !bufferContent.contains("-- NORMAL --") &&
                        !bufferContent.contains("-- 插入 --") &&
                        !bufferContent.contains("-- 可视 --") &&
                        !bufferContent.contains("-- 替换 --");

                // 只有在有明确退出特征时才确认退出vim
                if (hasExitSequence ||
                        (hasShellPrompt && noVimFeatures && timeSinceLastCheck > 1000)) {

                    connection.inVimMode = false;
                    connection.lastVimCheckTime = System.currentTimeMillis();
                    notifyFrontendVimMode(session, false);
                    log.info("✓ Vim模式已退出 - keepalive已恢复");
                    // 清空缓冲区
                    connection.outputBuffer.setLength(0);
                }
            }
        } catch (Exception e) {
            log.debug("Error detecting vim mode", e);
        }
    }

    /*
     * 用户切换检测功能已禁用
     * 
     * 检测用户切换(su命令)
     * 通过分析终端输出检测su命令的执行和用户切换
     * 只有在检测到新的命令提示符时才确认切换成功
     */
    /*
     * private void detectUserSwitch(byte[] b, int off, int len, WebSocketSession
     * session, SshConnection connection) {
     * try {
     * String output = new String(b, off, len, StandardCharsets.UTF_8);
     * connection.commandBuffer.append(output);
     * 
     * // 保持缓冲区在合理大小(最多保留最近2000个字符,以便检测完整的su过程)
     * if (connection.commandBuffer.length() > 2000) {
     * connection.commandBuffer.delete(0, connection.commandBuffer.length() - 2000);
     * }
     * 
     * String bufferContent = connection.commandBuffer.toString();
     * 
     * // 过滤掉bash错误信息行,只保留有效内容
     * // 这些错误信息（如 readonly variable）不应该影响用户名检测
     * String[] lines = bufferContent.split("\\r?\\n");
     * StringBuilder cleanBuffer = new StringBuilder();
     * for (String line : lines) {
     * // 跳过bash错误信息行
     * if (!line.contains("-bash:") && !line.trim().isEmpty()) {
     * cleanBuffer.append(line).append("\n");
     * }
     * }
     * String cleanContent = cleanBuffer.toString();
     * 
     * // 检测命令提示符模式: [user@hostname ~]$ 或 user@hostname:~$ 或 [user@hostname ~]#
     * (root)
     * // 这是最可靠的方式来检测当前用户
     * // 支持多种常见的提示符格式
     * java.util.regex.Pattern promptPattern = java.util.regex.Pattern.compile(
     * "\\[([a-zA-Z0-9_-]+)@[a-zA-Z0-9_.-]+\\s+[^\\]]*\\][$#]|([a-zA-Z0-9_-]+)@[a-zA-Z0-9_.-]+[:#~].*?[$#]\\s*$",
     * java.util.regex.Pattern.MULTILINE);
     * java.util.regex.Matcher matcher = promptPattern.matcher(cleanContent);
     * 
     * String detectedUser = null;
     * // 找到最后一个匹配的用户名(最新的提示符)
     * // 正则有两个捕获组,需要检查哪个匹配到了
     * while (matcher.find()) {
     * detectedUser = matcher.group(1) != null ? matcher.group(1) :
     * matcher.group(2);
     * }
     * 
     * // 只在检测到用户名变化时才记录日志和处理
     * // 如果检测到用户名且与当前记录的不同,说明发生了切换
     * if (detectedUser != null && !detectedUser.equals(connection.currentUser)) {
     * log.debug("User change detected - Current: {}, New: {}",
     * connection.currentUser, detectedUser);
     * 
     * // 额外验证:确保这是一个完整的提示符(以$或#结尾),而不是su命令执行过程中的输出
     * // 检查是否包含su失败的常见错误信息
     * String lowerBuffer = bufferContent.toLowerCase();
     * if (lowerBuffer.contains("authentication failure") ||
     * lowerBuffer.contains("su: incorrect password") ||
     * lowerBuffer.contains("su: authentication failure") ||
     * lowerBuffer.contains("permission denied")) {
     * log.debug("Detected su command failure, not switching user");
     * return;
     * }
     * 
     * String oldUser = connection.currentUser;
     * connection.currentUser = detectedUser;
     * 
     * log.
     * info("User switch detected in session {}: {} -> {} (su command successful)",
     * session.getId(), oldUser, detectedUser);
     * 
     * // 通过WebSocket通知前端用户已切换
     * notifyUserSwitch(session, detectedUser);
     * 
     * // 清空命令缓冲区
     * connection.commandBuffer.setLength(0);
     * }
     * // 如果没有检测到用户变化，不输出任何日志，避免刷屏
     * } catch (Exception e) {
     * log.debug("Error detecting user switch", e);
     * }
     * }
     * 
     * private void notifyUserSwitch(WebSocketSession session, String newUser) {
     * try {
     * if (session.isOpen()) {
     * // 发送特殊格式的消息通知前端
     * String notification = "\u001b[0m\r\n[系统提示] 检测到用户切换为: " + newUser + "\r\n";
     * synchronized (session) {
     * session.sendMessage(new TextMessage(notification));
     * // 发送JSON格式的用户切换事件
     * session.sendMessage(new TextMessage(
     * "{\"type\":\"user_switch\",\"username\":\"" + newUser + "\"}"));
     * }
     * }
     * } catch (Exception e) {
     * log.error("Failed to notify user switch", e);
     * }
     * }
     */

    /**
     * 发送心跳保持连接活跃
     * 定期向所有活跃的 SSH 连接发送换行符，防止服务器端超时断开
     * 换行符会在终端创建新行，用户可以清楚看到保活活动正在进行
     */
    private void sendKeepAlive() {
        // 安全阈值配置
        final long INPUT_IDLE_THRESHOLD = 10000; // 10秒：刚输入完成后的等待时间
        final long MAX_WAIT_THRESHOLD = 120000; // 2分钟：最大等待时间，超过则强制发送Ctrl+C保活

        try {
            sshMap.forEach((sessionId, connection) -> {
                try {
                    if (connection.channel != null && connection.channel.isOpen()) {
                        long now = System.currentTimeMillis();
                        long timeSinceLastInput = now - connection.lastInputTime;
                        OutputStream inputStream = connection.channel.getInvertedIn();

                        // 检查是否超过最大等待时间（5分钟）
                        boolean exceedMaxWait = timeSinceLastInput > MAX_WAIT_THRESHOLD;

                        // vim模式：绝对不发送任何保活字符
                        if (connection.inVimMode) {
                            connection.updateActivity();
                            log.debug("Keepalive跳过 (vim模式): {}", sessionId);
                        }
                        // 用户正在输入命令（未按回车）
                        else if (!connection.commandCompleted) {
                            if (exceedMaxWait) {
                                // 超过5分钟，发送Ctrl+C取消当前命令，用户可以看到未完成的命令
                                // Ctrl+C (0x03) 取消当前命令但保留在屏幕上
                                inputStream.write(0x03); // Ctrl+C
                                inputStream.flush();
                                connection.commandCompleted = true; // 标记命令已取消
                                connection.updateActivity();
                                log.info("Keepalive强制保活 (命令输入超时{}分钟，已取消命令): {}",
                                        timeSinceLastInput / 60000, sessionId);
                            } else {
                                // 未超时，跳过
                                connection.updateActivity();
                                log.debug("Keepalive跳过 (命令输入中): {}", sessionId);
                            }
                        }
                        // 刚完成输入不久
                        else if (timeSinceLastInput < INPUT_IDLE_THRESHOLD) {
                            connection.updateActivity();
                            log.debug("Keepalive跳过 (刚完成输入): {}", sessionId);
                        }
                        // 用户空闲（命令已完成），发送换行符保活
                        else {
                            inputStream.write('\n');
                            inputStream.flush();
                            connection.updateActivity();
                            log.debug("Keepalive已发送: {}", sessionId);
                        }
                    }
                } catch (Exception e) {
                    log.debug("Failed to send keepalive for session {}: {}", sessionId, e.getMessage());
                }
            });
        } catch (Exception e) {
            log.error("Error in sendKeepAlive task", e);
        }
    }

    /**
     * 调整终端大小
     * 当前端终端窗口大小改变时，动态调整SSH会话的PTY大小
     */
    @Override
    public void resizeTerminal(WebSocketSession session, Integer cols, Integer rows) {
        if (cols == null || rows == null || cols <= 0 || rows <= 0) {
            log.warn("Invalid terminal size: cols={}, rows={}", cols, rows);
            return;
        }

        SshConnection connection = sshMap.get(session.getId());
        if (connection != null && connection.channel != null && connection.channel.isOpen()) {
            try {
                // 动态调整PTY大小
                connection.channel.sendWindowChange(cols, rows);
                log.info("终端尺寸已调整: {} -> {}列 x {}行", session.getId(), cols, rows);
            } catch (Exception e) {
                log.error("Failed to resize terminal for session {}", session.getId(), e);
            }
        }
    }

    private Charset resolveCharset(String charsetName) {
        String normalized = normalizeCharsetName(charsetName);
        try {
            return Charset.forName(normalized);
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }

    private String normalizeCharsetName(String charsetName) {
        if (charsetName == null || charsetName.trim().isEmpty()) {
            return "UTF-8";
        }
        String upper = charsetName.trim().toUpperCase();
        switch (upper) {
            case "GBK":
                return "GBK";
            case "GB2312":
                return "GB2312";
            case "GB18030":
                return "GB18030";
            case "UTF8":
            case "UTF-8":
            default:
                return "UTF-8";
        }
    }

    /** Linux 下常见默认家目录（与真实 HOME 可能不一致，后续靠 cd 纠正） */
    private static String defaultUnixHomeForUser(String user) {
        if (user == null || user.trim().isEmpty()) {
            return "/";
        }
        if ("root".equalsIgnoreCase(user.trim())) {
            return "/root";
        }
        return "/home/" + user.trim();
    }

    private static String unixBasename(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        String p = path.trim();
        int i = p.lastIndexOf('/');
        return i >= 0 ? p.substring(i + 1) : p;
    }

    /**
     * 规范化 Unix 风格路径（处理 .、..、多余斜杠）
     */
    private static String normalizeUnixPath(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        String p = path.trim().replace('\\', '/');
        boolean absolute = p.startsWith("/");
        String[] parts = p.split("/");
        Deque<String> stack = new ArrayDeque<>();
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                if (!stack.isEmpty()) {
                    stack.pollLast();
                }
            } else {
                stack.addLast(part);
            }
        }
        StringBuilder sb = new StringBuilder();
        if (absolute) {
            sb.append('/');
        }
        int i = 0;
        for (String s : stack) {
            if (i++ > 0) {
                sb.append('/');
            }
            sb.append(s);
        }
        if (sb.length() == 0) {
            return absolute ? "/" : ".";
        }
        return sb.toString();
    }

    private static String unixParentDir(String absPath) {
        String n = normalizeUnixPath(absPath);
        if ("/".equals(n)) {
            return "/";
        }
        int i = n.lastIndexOf('/');
        if (i <= 0) {
            return "/";
        }
        return n.substring(0, i);
    }

    /**
     * 根据用户已提交到 SSH 的一行命令更新 {@link SshConnection#shellWorkingDirectory}（仅处理常见 cd 形式）
     */
    private void updateShellWorkingDirectoryFromCd(SshConnection c, String line) {
        if (c == null || c.shellWorkingDirectory == null || line == null) {
            return;
        }
        String t = line.trim();
        if (t.isEmpty()) {
            return;
        }
        if (!t.startsWith("cd")) {
            return;
        }
        if (t.length() > 2 && !Character.isWhitespace(t.charAt(2))) {
            return;
        }
        String home = defaultUnixHomeForUser(c.sshRemoteUsername);
        String rest = t.substring(2).trim();
        if (rest.isEmpty() || "~".equals(rest)) {
            c.shellWorkingDirectory = home;
            return;
        }
        if (rest.startsWith("~/")) {
            c.shellWorkingDirectory = normalizeUnixPath(home + rest.substring(1));
            return;
        }
        if (rest.startsWith("~") && rest.length() > 1) {
            return;
        }
        // 去掉成对引号
        if ((rest.startsWith("\"") && rest.endsWith("\"") && rest.length() >= 2)
                || (rest.startsWith("'") && rest.endsWith("'") && rest.length() >= 2)) {
            rest = rest.substring(1, rest.length() - 1).trim();
        }
        if (rest.isEmpty()) {
            c.shellWorkingDirectory = home;
            return;
        }
        if ("-".equals(rest)) {
            return;
        }
        if (".".equals(rest)) {
            return;
        }
        if ("..".equals(rest)) {
            c.shellWorkingDirectory = unixParentDir(c.shellWorkingDirectory);
            return;
        }
        if (rest.startsWith("/")) {
            c.shellWorkingDirectory = normalizeUnixPath(rest);
            return;
        }
        if (rest.startsWith("./")) {
            rest = rest.substring(2);
        }
        String base = c.shellWorkingDirectory.endsWith("/") ? c.shellWorkingDirectory.substring(0,
                c.shellWorkingDirectory.length() - 1) : c.shellWorkingDirectory;
        c.shellWorkingDirectory = normalizeUnixPath(base + "/" + rest);
    }

    /**
     * 通过SFTP读取远程服务器上的脚本文件内容
     * 用于后端深度扫描脚本内容中的危险操作
     *
     * @param session WebSocket会话
     * @param scriptPath 脚本路径（多为前端根据侧栏目录拼接；可能与 shell 当前目录不一致，将按会话内推断的 cwd 重试）
     * @param charsetName 字符集名称
     * @return 脚本内容与实际读取路径；读取失败返回 null
     */
    public ScriptReadResult readScriptFile(WebSocketSession session, String scriptPath, String charsetName) {
        SshConnection connection = sshMap.get(session.getId());
        if (connection == null || connection.session == null) {
            log.warn("读取脚本文件失败：SSH会话不存在或未认证, sessionId={}", session.getId());
            return null;
        }

        // 安全检查：脚本路径不能包含危险字符
        if (scriptPath == null || scriptPath.trim().isEmpty()) {
            log.warn("读取脚本文件失败：脚本路径为空, sessionId={}", session.getId());
            return null;
        }

        String trimmed = scriptPath.trim();

        // 禁止访问 /dev, /proc, /sys 等虚拟文件系统
        if (trimmed.startsWith("/dev/") || trimmed.startsWith("/proc/") || trimmed.startsWith("/sys/")) {
            log.warn("读取脚本文件失败：禁止访问虚拟文件系统路径: {}", trimmed);
            return null;
        }

        Charset charset = resolveCharset(charsetName);
        ClientSession clientSession = connection.session;

        log.info("通过SFTP读取远程脚本文件: sessionId={}, requestPath={}, shellCwd={}", session.getId(), trimmed,
                connection.shellWorkingDirectory);

        try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(clientSession)) {
            String content = tryReadScriptAtSftpPath(sftp, trimmed, charset, session.getId());
            if (content != null) {
                return new ScriptReadResult(content, trimmed);
            }
            // 侧栏 SFTP 路径与终端里 cd 后的目录常不一致；用会话内跟踪的 shell 当前目录 + 文件名再试
            String name = unixBasename(trimmed);
            if (name != null && !name.isEmpty() && connection.shellWorkingDirectory != null) {
                String alt = normalizeUnixPath(connection.shellWorkingDirectory + "/" + name);
                if (!alt.equals(trimmed) && !alt.startsWith("/dev/") && !alt.startsWith("/proc/")
                        && !alt.startsWith("/sys/")) {
                    log.info("SFTP 首次路径未读到文件，按 shell 推断目录重试: sessionId={}, altPath={}", session.getId(),
                            alt);
                    content = tryReadScriptAtSftpPath(sftp, alt, charset, session.getId());
                    if (content != null) {
                        log.info("SFTP 已按 shell 目录读取成功: requestPath={} -> resolvedPath={}", trimmed, alt);
                        return new ScriptReadResult(content, alt);
                    }
                }
            }
        } catch (Exception e) {
            log.error("通过SFTP读取脚本文件失败: sessionId={}, path={}, error={}",
                    session.getId(), trimmed, e.getMessage());
            return null;
        }
        return null;
    }

    @Override
    public <T> T executeSftpOnExistingSession(WebSocketSession session, String expectedUsername,
                                             SftpOperation<T> operation) throws Exception {
        if (session == null) {
            throw new IllegalStateException("WebSocket session is null");
        }
        SshConnection connection = sshMap.get(session.getId());
        if (connection == null || connection.session == null || !connection.session.isOpen()) {
            throw new IllegalStateException("SSH session is not available");
        }
        if (expectedUsername != null
                && connection.sshRemoteUsername != null
                && !expectedUsername.equals(connection.sshRemoteUsername)) {
            throw new IllegalStateException("SFTP user is different from terminal SSH user");
        }
        try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(connection.session)) {
            connection.updateActivity();
            return operation.apply(sftp);
        }
    }

    /**
     * 从已打开的 SFTP 客户端读取单个脚本文件（失败返回 null，不记 error 级别日志）
     */
    private String tryReadScriptAtSftpPath(SftpClient sftp, String path, Charset charset, String sessionId) {
        try {
            SftpClient.Attributes attrs = sftp.stat(path);
            if (attrs.isDirectory()) {
                log.warn("读取脚本文件失败：目标路径是目录而非文件: {}", path);
                return null;
            }
            final long MAX_SCRIPT_SIZE = 1024 * 1024; // 1MB
            if (attrs.getSize() > MAX_SCRIPT_SIZE) {
                log.warn("读取脚本文件失败：文件过大 ({} 字节)，超过最大限制 {} 字节: {}",
                        attrs.getSize(), MAX_SCRIPT_SIZE, path);
                return null;
            }
            try (InputStream is = sftp.read(path);
                 ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = is.read(buffer)) != -1) {
                    baos.write(buffer, 0, len);
                }
                String content = baos.toString(charset.name());
                log.info("成功读取远程脚本文件: {} ({} 字节), sessionId={}", path, content.length(), sessionId);
                return content;
            }
        } catch (Exception e) {
            log.debug("SFTP 读取路径失败: sessionId={}, path={}, error={}", sessionId, path, e.getMessage());
            return null;
        }
    }

    // ============================================================
    // 脚本执行拦截相关
    // ============================================================

    /**
     * 脚本执行拦截结果
     */
    private static class ScriptBlockResult {
        boolean blocked;       // 是否阻断
        boolean needConfirm;   // 是否需要确认
        String message;        // 提示消息
        String riskLevel;      // 危险等级

        static ScriptBlockResult allow() {
            ScriptBlockResult r = new ScriptBlockResult();
            r.blocked = false;
            r.needConfirm = false;
            return r;
        }

        static ScriptBlockResult block(String msg, String level) {
            ScriptBlockResult r = new ScriptBlockResult();
            r.blocked = true;
            r.message = msg;
            r.riskLevel = level;
            return r;
        }

        static ScriptBlockResult confirm(String msg, String level) {
            ScriptBlockResult r = new ScriptBlockResult();
            r.blocked = true;
            r.needConfirm = true;
            r.message = msg;
            r.riskLevel = level;
            return r;
        }
    }

    /**
     * 检测并处理脚本执行命令
     * 当用户执行 sh/bash/./ 等脚本执行命令时，拦截并扫描脚本内容
     *
     * @return null表示不拦截直接放行，ScriptBlockResult表示拦截结果
     */
    private ScriptBlockResult checkScriptExecution(WebSocketSession session, WebSshData webSshData,
                                                   String commandText, SshConnection connection) {
        // 脚本执行触发命令检测（sh/bash/zsh/./source + 脚本）
        if (!isScriptExecCommand(commandText)) {
            return null; // 非脚本执行命令，不拦截
        }

        // 脚本扫描服务未启用时放行
        if (scriptSecurityService == null || securityConfig == null || !securityConfig.isScriptScanEnabled()) {
            return null;
        }

        log.info("检测到脚本执行命令: {}", commandText);

        // 如果前端已携带了确认Token，说明用户已完成确认流程
        String confirmToken = webSshData.getScriptConfirmToken();
        String confirmText = webSshData.getScriptConfirmText();
        if (confirmToken != null && !confirmToken.isEmpty() && confirmText != null) {
            // 验证确认Token
            String httpSessionId = (String) session.getAttributes().get("HTTP.SESSION.ID");
            String verifySessionId = (httpSessionId != null && !httpSessionId.isEmpty()) ? httpSessionId : session.getId();

            if (tokenService != null && tokenService.validateAndConsumeToken(verifySessionId, confirmText, confirmToken)) {
                log.info("脚本执行确认Token验证通过: {}", commandText);
                return ScriptBlockResult.allow();
            } else {
                // Token验证失败，发送拒绝消息
                String rejectMsg = "\r\n\033[1;31m⚠️  脚本执行被拒绝：确认Token无效或已过期，请重新确认\033[0m\r\n";
                sendMessage(session, rejectMsg.getBytes(connection.charset));
                return ScriptBlockResult.block("脚本确认Token无效", "critical");
            }
        }

        // 执行脚本内容扫描
        com.gxcj.xjtool.dto.ScriptScanResult scanResult = scriptSecurityService.scanCommand(commandText);

        if (scanResult.isPassed()) {
            // 脚本安全，直接放行
            return ScriptBlockResult.allow();
        }

        String riskLevel = scanResult.getRiskLevel();
        String message = scanResult.getMessage();

        // 根据配置决定阻断行为（blockCriticalScriptOps=true 时，high 等级也阻断）
        if (securityConfig.isBlockCriticalScriptOps() && "high".equals(riskLevel)) {
            String blockMsg = String.format(
                    "\r\n\033[1;31m========================================\033[0m\r\n" +
                    "\033[1;31m⚠️  高危操作！脚本执行已被系统阻断！\033[0m\r\n" +
                    "\033[1;31m========================================\033[0m\r\n" +
                    "\r\n检测到危险等级: \033[1;33m%s\033[0m\r\n" +
                    "\r\n%s\r\n\r\n" +
                    "\033[1;31m该操作风险极高，已被系统阻断执行！\033[0m\r\n" +
                    "\r\n如确需执行，请联系管理员授权。\r\n",
                    com.gxcj.xjtool.service.ScriptSecurityService.getRiskLevelLabel(riskLevel),
                    formatDangerousOpsForDisplay(scanResult.getDangerousOperations()));

            sendMessage(session, blockMsg.getBytes(connection.charset));
            log.warn("高危脚本执行被阻断（blockCriticalScriptOps=true）: sessionId={}, command={}",
                    session.getId(), commandText);
            return ScriptBlockResult.block(message, riskLevel);
        }

        // high/medium：需要用户确认
        String confirmMsg = String.format(
                "\r\n\033[1;33m========================================\033[0m\r\n" +
                "\033[1;33m⚠️  检测到脚本包含危险操作！\033[0m\r\n" +
                "\033[1;33m========================================\033[0m\r\n" +
                "\r\n危险等级: \033[1;33m%s\033[0m\r\n" +
                "\r\n%s\r\n" +
                "\r\n如确认脚本安全，请在Web界面点击\"确认执行\"按钮。\r\n" +
                "\r\n脚本命令: \033[1;36m%s\033[0m\r\n",
                com.gxcj.xjtool.service.ScriptSecurityService.getRiskLevelLabel(riskLevel),
                message,
                commandText.length() > 200 ? commandText.substring(0, 200) + "..." : commandText);

        sendMessage(session, confirmMsg.getBytes(connection.charset));

        // 发送特殊消息给前端，触发确认弹窗（带上脚本路径供前端显示）
        String scriptPath = webSshData.getScriptPath();
        sendScriptConfirmRequest(session, scanResult, commandText, scriptPath);

        log.warn("危险脚本执行被阻断，需确认: sessionId={}, riskLevel={}, operations={}",
                session.getId(), riskLevel, scanResult.getDangerousOperations().size());

        return ScriptBlockResult.confirm(message, riskLevel);
    }

    /**
     * 格式化危险操作列表为终端显示格式
     */
    private String formatDangerousOpsForDisplay(List<com.gxcj.xjtool.dto.ScriptScanResult.DangerousOperation> operations) {
        if (operations == null || operations.isEmpty()) {
            return "未知危险操作";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(operations.size(), 5); i++) {
            com.gxcj.xjtool.dto.ScriptScanResult.DangerousOperation op = operations.get(i);
            sb.append(String.format("  %d. [%s] %s\r\n    操作: %s\r\n    建议: %s\r\n",
                    i + 1,
                    op.getRiskLevel().toUpperCase(),
                    op.getDescription(),
                    op.getOriginalText(),
                    op.getSuggestion()));
        }
        if (operations.size() > 5) {
            sb.append("  ... 还有 ").append(operations.size() - 5).append(" 个危险操作\r\n");
        }
        return sb.toString();
    }

    /**
     * 向前端发送脚本确认请求
     * 发送特殊格式的JSON消息，前端收到后弹出确认对话框
     */
    private void sendScriptConfirmRequest(WebSocketSession session,
                                          com.gxcj.xjtool.dto.ScriptScanResult scanResult,
                                          String commandText, String scriptPath) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

            Map<String, Object> confirmRequest = new java.util.HashMap<>();
            confirmRequest.put("type", "script_confirm_required");
            confirmRequest.put("riskLevel", scanResult.getRiskLevel());
            confirmRequest.put("riskLevelLabel", com.gxcj.xjtool.service.ScriptSecurityService.getRiskLevelLabel(scanResult.getRiskLevel()));
            confirmRequest.put("riskLevelColor", com.gxcj.xjtool.service.ScriptSecurityService.getRiskLevelColor(scanResult.getRiskLevel()));
            confirmRequest.put("message", scanResult.getMessage());
            confirmRequest.put("commandText", commandText.length() > 200 ? commandText.substring(0, 200) + "..." : commandText);
            confirmRequest.put("operations", scanResult.getDangerousOperations());
            confirmRequest.put("needConfirm", scanResult.isNeedConfirm());
            if (scriptPath != null && !scriptPath.isEmpty()) {
                confirmRequest.put("scriptPath", scriptPath);
            }

            String json = mapper.writeValueAsString(confirmRequest);
            session.sendMessage(new TextMessage(json));

            log.debug("已向前端发送脚本确认请求: sessionId={}", session.getId());
        } catch (Exception e) {
            log.error("发送脚本确认请求失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 判断是否是脚本执行命令
     */
    private boolean isScriptExecCommand(String command) {
        if (command == null || command.trim().isEmpty()) {
            return false;
        }
        String trimmed = command.trim();
        // 检测常见的脚本执行格式
        // sh -c "..."  bash -c "..."  ./script.sh  bash script.sh  source script.sh
        return trimmed.matches("(?i)^(sh|bash|zsh|dash|ksh|source|\\.)\\s+.*") ||
               trimmed.matches("(?i)^\\./.*") ||
               trimmed.matches("(?i)^bash\\s+.*") ||
               trimmed.matches("(?i)^sh\\s+.*") ||
               trimmed.matches("(?i)^zsh\\s+.*") ||
               trimmed.matches("(?i)^source\\s+.*");
    }
}
