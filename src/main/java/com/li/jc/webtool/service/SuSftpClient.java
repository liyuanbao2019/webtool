package com.li.jc.webtool.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.channel.ChannelSubsystem;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.io.IoWriteFuture;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpErrorDataHandler;
import org.apache.sshd.sftp.client.SftpVersionSelector;
import org.apache.sshd.sftp.client.impl.DefaultSftpClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * SFTP 客户端：用已登录的 SSH 会话（如 tmn），通过 "su - 目标用户" 启动 sftp-server。
 * <p>
 * 适用于目标用户（如 root）禁止 SSH 直接登录、但可以 su 切换的服务器，做法与终端里手动 su 一致：
 * <ol>
 *   <li>在带 PTY 的 exec 通道里执行 su（su 需要终端才能读密码）；</li>
 *   <li>看到密码提示后写入密码；</li>
 *   <li>切换成功后把终端设为 raw 模式（保证二进制数据不被改写），输出就绪标记，再 exec sftp-server；</li>
 *   <li>收到就绪标记后，之后的数据才交给标准 SFTP 协议处理。</li>
 * </ol>
 */
@Slf4j
public class SuSftpClient extends DefaultSftpClient {

    private static final Pattern USERNAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]{0,31}");
    private static final int MAX_HANDSHAKE_OUTPUT = 256 * 1024;
    private static final String NO_SFTP_SERVER = "WEBTOOL_NO_SFTP_SERVER";
    private static final String[] SFTP_SERVER_PATHS = {
            "/usr/libexec/openssh/sftp-server",
            "/usr/lib/openssh/sftp-server",
            "/usr/lib/ssh/sftp-server",
            "/usr/libexec/sftp-server",
            "/usr/lib/sftp-server"
    };

    /** 父类构造函数里就会创建通道，此时子类字段还未初始化，只能借助 ThreadLocal 传参。 */
    private static final ThreadLocal<Handshake> PENDING = new ThreadLocal<>();

    public static SftpClient open(ClientSession session, String suUser, String suPassword, Duration timeout)
            throws IOException {
        if (suUser == null || !USERNAME.matcher(suUser).matches()) {
            throw new IOException("非法的切换用户名: " + suUser);
        }
        if (suPassword == null || suPassword.isEmpty()) {
            throw new IOException("缺少用户 " + suUser + " 的 su 密码");
        }
        PENDING.set(new Handshake(suUser, suPassword, timeout));
        try {
            return new SuSftpClient(session);
        } finally {
            PENDING.remove();
        }
    }

    private SuSftpClient(ClientSession session) throws IOException {
        super(session, SftpVersionSelector.CURRENT, SftpErrorDataHandler.EMPTY);
    }

    @Override
    protected ChannelSubsystem createSftpChannelSubsystem(ClientSession clientSession) {
        Handshake handshake = PENDING.get();
        if (handshake == null) {
            throw new IllegalStateException("SuSftpClient must be created through SuSftpClient.open");
        }
        return new SuChannel(handshake);
    }

    private SuChannel suChannel() {
        return (SuChannel) getClientChannel();
    }

    @Override
    protected int data(byte[] buf, int start, int len) throws IOException {
        SuChannel channel = suChannel();
        if (channel == null || channel.handshake.isReady()) {
            return super.data(buf, start, len);
        }
        int sftpStart = channel.handshake.feed(buf, start, len, channel);
        if (sftpStart >= 0 && sftpStart < start + len) {
            super.data(buf, sftpStart, start + len - sftpStart);
        }
        return len;
    }

    @Override
    protected void init(ClientSession session, SftpVersionSelector selector, Duration initializationTimeout)
            throws IOException {
        // 等 su 切换完成、sftp-server 就绪后，才发送 SFTP 初始化报文
        SuChannel channel = suChannel();
        channel.handshake.await(channel);
        super.init(session, selector, initializationTimeout);
    }

    /** 把 SFTP 子系统请求替换成 "pty-req + exec su ..."。 */
    private final class SuChannel extends SftpChannelSubsystem {
        private final Handshake handshake;
        private boolean replaceSubsystemRequest;

        private SuChannel(Handshake handshake) {
            this.handshake = handshake;
        }

        @Override
        protected void doOpen() throws IOException {
            replaceSubsystemRequest = true;
            try {
                super.doOpen();
            } finally {
                replaceSubsystemRequest = false;
            }
        }

        @Override
        public IoWriteFuture writePacket(Buffer buffer) throws IOException {
            if (replaceSubsystemRequest) {
                Boolean wantReply = subsystemRequestWantReply(buffer);
                if (wantReply != null) {
                    replaceSubsystemRequest = false;
                    Session session = getSession();
                    super.writePacket(ptyRequest(session));
                    return super.writePacket(execRequest(session, wantReply));
                }
            }
            return super.writePacket(buffer);
        }

        private Buffer ptyRequest(Session session) {
            Buffer buffer = session.createBuffer(SshConstants.SSH_MSG_CHANNEL_REQUEST, 64);
            buffer.putUInt(getRecipient());
            buffer.putString("pty-req");
            buffer.putBoolean(false);
            buffer.putString("dumb");
            buffer.putUInt(200);   // columns
            buffer.putUInt(50);    // rows
            buffer.putUInt(0);
            buffer.putUInt(0);
            buffer.putBytes(new byte[] { 0 }); // 空的终端模式列表（TTY_OP_END）
            return buffer;
        }

        private Buffer execRequest(Session session, boolean wantReply) {
            String command = handshake.command();
            Buffer buffer = session.createBuffer(SshConstants.SSH_MSG_CHANNEL_REQUEST, command.length() + 32);
            buffer.putUInt(getRecipient());
            buffer.putString("exec");
            buffer.putBoolean(wantReply);
            buffer.putString(command);
            return buffer;
        }

        private void writeRaw(String text) throws IOException {
            getAsyncIn().writeBuffer(new ByteArrayBuffer(text.getBytes(StandardCharsets.UTF_8)));
        }
    }

    /** 在报文里查找 string "subsystem"，找到则返回其后的 want-reply 标志，否则返回 null。 */
    static Boolean subsystemRequestWantReply(Buffer buffer) {
        byte[] data = buffer.array();
        byte[] name = "subsystem".getBytes(StandardCharsets.US_ASCII);
        int end = buffer.wpos();
        for (int i = buffer.rpos(); i + 4 + name.length < end; i++) {
            if (data[i] != 0 || data[i + 1] != 0 || data[i + 2] != 0 || data[i + 3] != name.length) {
                continue;
            }
            boolean match = true;
            for (int j = 0; j < name.length && match; j++) {
                match = data[i + 4 + j] == name[j];
            }
            if (match) {
                return data[i + 4 + name.length] != 0;
            }
        }
        return null;
    }

    /** su 切换过程：识别密码提示、写入密码、等待就绪标记。 */
    static final class Handshake {
        private static final String UTF8_PASSWORD_PROMPT =
                new String("密码".getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);

        private final String suUser;
        private final String suPassword;
        private final Duration timeout;
        private final String marker = "WEBTOOL_SFTP_READY_" + UUID.randomUUID().toString().replace("-", "");
        private final StringBuilder output = new StringBuilder();
        private final CountDownLatch done = new CountDownLatch(1);
        private boolean passwordSent;
        private volatile boolean ready;
        private volatile String error;

        Handshake(String suUser, String suPassword, Duration timeout) {
            this.suUser = suUser;
            this.suPassword = suPassword;
            this.timeout = timeout;
        }

        String command() {
            StringBuilder script = new StringBuilder("stty raw -echo -iexten cs8 -parenb 2>/dev/null; for p in");
            for (String path : SFTP_SERVER_PATHS) {
                script.append(' ').append(path);
            }
            script.append("; do if [ -x \"$p\" ]; then printf \"%s\\n\" ").append(marker)
                    .append("; exec \"$p\"; fi; done; echo ").append(NO_SFTP_SERVER);
            return "su - " + suUser + " -c '" + script + "'";
        }

        boolean isReady() {
            return ready;
        }

        /**
         * 处理 su 阶段的输出。返回 SFTP 数据在 buf 中的起始位置；尚未就绪时返回 -1。
         * 数据按 ISO-8859-1 解码，字符位置与字节位置一一对应。
         */
        synchronized int feed(byte[] buf, int start, int len, SuChannel channel) throws IOException {
            if (done.getCount() == 0) {
                return -1;
            }
            int before = output.length();
            output.append(new String(buf, start, len, StandardCharsets.ISO_8859_1));

            int markerAt = output.indexOf(marker);
            if (markerAt >= 0) {
                int lineEnd = output.indexOf("\n", markerAt + marker.length());
                if (lineEnd < 0) {
                    return -1; // 等标记这一行收完整
                }
                ready = true;
                done.countDown();
                return start + Math.max(0, lineEnd + 1 - before);
            }
            if (output.indexOf(NO_SFTP_SERVER) >= 0) {
                fail("服务器上未找到 sftp-server，无法以 " + suUser + " 身份浏览文件");
                return -1;
            }
            if (!passwordSent) {
                String lower = output.toString().toLowerCase(Locale.ROOT);
                if (lower.contains("password") || lower.contains("passwd") || output.indexOf(UTF8_PASSWORD_PROMPT) >= 0) {
                    passwordSent = true;
                    channel.writeRaw(suPassword + "\n");
                }
            }
            if (output.length() > MAX_HANDSHAKE_OUTPUT) {
                fail("su 切换输出异常: " + readableOutput());
            }
            return -1;
        }

        void await(SuChannel channel) throws IOException {
            long deadline = System.nanoTime() + timeout.toNanos();
            try {
                while (!done.await(100, TimeUnit.MILLISECONDS)) {
                    if (channel.isClosing() || channel.isClosed()) {
                        fail("su 切换到 " + suUser + " 失败: " + readableOutput());
                    } else if (System.nanoTime() > deadline) {
                        fail("su 切换到 " + suUser + " 超时: " + readableOutput());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("su 切换被中断", e);
            }
            if (error != null) {
                throw new IOException(error);
            }
        }

        private synchronized void fail(String message) {
            if (done.getCount() > 0) {
                error = message;
                log.warn("SFTP su handshake failed: user={}, reason={}", suUser, message);
                done.countDown();
            }
        }

        /** 取 su 输出中密码提示之后的内容（如 "su: Authentication failure"），去掉控制字符。 */
        private synchronized String readableOutput() {
            String text = new String(output.toString().getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
            int prompt = Math.max(text.toLowerCase(Locale.ROOT).lastIndexOf("password"), text.lastIndexOf("密码"));
            if (prompt >= 0) {
                int lineEnd = text.indexOf('\n', prompt);
                text = lineEnd >= 0 ? text.substring(lineEnd + 1) : "";
            }
            text = text.replaceAll("\u001B\\[[0-9;?]*[A-Za-z]", "")
                    .replaceAll("[\\p{Cntrl}&&[^\n]]", "")
                    .trim();
            if (text.isEmpty()) {
                return passwordSent ? "密码错误或该用户不允许 su 切换" : "未收到 su 密码提示";
            }
            return text.length() > 200 ? text.substring(text.length() - 200) : text;
        }
    }
}
