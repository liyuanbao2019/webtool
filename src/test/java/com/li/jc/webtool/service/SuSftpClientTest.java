package com.li.jc.webtool.service;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelSubsystem;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用内嵌 SSH 服务器模拟 "su - root -c ..."：先要求输入密码、打印登录横幅，
 * 然后把通道数据转发给真实的 SFTP 子系统，验证握手、密码校验和二进制传输。
 */
class SuSftpClientTest {

    private static final Pattern MARKER = Pattern.compile("(WEBTOOL_SFTP_READY_[0-9a-f]+)");

    @TempDir
    Path root;

    private SshServer server;
    private SshClient client;
    private ClientSession session;

    @BeforeEach
    void setUp() throws Exception {
        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider());
        server.setPasswordAuthenticator((user, password, s) -> "tmn".equals(user) && "loginpw".equals(password));
        server.setSubsystemFactories(Collections.singletonList(new SftpSubsystemFactory()));
        server.setFileSystemFactory(new VirtualFileSystemFactory(root));
        server.setCommandFactory((channel, command) -> new FakeSuCommand(command));
        server.start();

        client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        client.start();
        session = login();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (session != null) session.close();
        if (client != null) client.stop();
        if (server != null) server.stop(true);
    }

    @Test
    void switchesUserThroughSuAndTransfersBinaryData() throws Exception {
        byte[] payload = new byte[300_000];
        new Random(42).nextBytes(payload);

        try (SftpClient sftp = SuSftpClient.open(session, "root", "rootpw", Duration.ofSeconds(10))) {
            assertEquals("/", sftp.canonicalPath("."));
            try (OutputStream os = sftp.write("/data.bin")) {
                os.write(payload);
            }
            ByteArrayOutputStream read = new ByteArrayOutputStream();
            try (InputStream is = sftp.read("/data.bin")) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) read.write(buf, 0, n);
            }
            assertArrayEquals(payload, read.toByteArray());
        }
        assertArrayEquals(payload, Files.readAllBytes(root.resolve("data.bin")));
    }

    @Test
    void reportsWrongSuPassword() {
        IOException error = assertThrows(IOException.class,
                () -> SuSftpClient.open(session, "root", "bad", Duration.ofSeconds(10)));
        assertTrue(error.getMessage().contains("Authentication failure"), error.getMessage());
    }

    @Test
    void rejectsUnsafeUsername() {
        assertThrows(IOException.class,
                () -> SuSftpClient.open(session, "root;reboot", "rootpw", Duration.ofSeconds(10)));
    }

    private ClientSession login() throws IOException {
        ClientSession s = client.connect("tmn", "127.0.0.1", server.getPort())
                .verify(Duration.ofSeconds(10)).getSession();
        s.addPasswordIdentity("loginpw");
        s.auth().verify(Duration.ofSeconds(10));
        return s;
    }

    /** 模拟 su：读密码、打印横幅和就绪标记，然后充当 sftp-server。 */
    private final class FakeSuCommand implements Command {
        private final String command;
        private InputStream in;
        private OutputStream out;
        private OutputStream err;
        private ExitCallback exit;

        FakeSuCommand(String command) {
            this.command = command;
        }

        @Override public void setInputStream(InputStream in) { this.in = in; }
        @Override public void setOutputStream(OutputStream out) { this.out = out; }
        @Override public void setErrorStream(OutputStream err) { this.err = err; }
        @Override public void setExitCallback(ExitCallback callback) { this.exit = callback; }
        @Override public void destroy(ChannelSession channel) { }

        @Override
        public void start(ChannelSession channel, Environment env) {
            Thread thread = new Thread(this::run, "fake-su");
            thread.setDaemon(true);
            thread.start();
        }

        private void run() {
            try {
                Matcher marker = MARKER.matcher(command);
                if (!command.startsWith("su - root -c '") || !marker.find()) {
                    print("su: unexpected command\r\n");
                    exit.onExit(1);
                    return;
                }
                print("Password: ");
                if (!"rootpw".equals(readLine())) {
                    print("\r\nsu: Authentication failure\r\n");
                    exit.onExit(1);
                    return;
                }
                print("\r\nWelcome to Fake Linux - password policy banner\r\n");
                print(marker.group(1) + "\n");

                try (ClientSession inner = login()) {
                    ChannelSubsystem sftp = inner.createSubsystemChannel("sftp");
                    sftp.setIn(in);
                    sftp.setOut(new FlushingOutputStream(out));
                    sftp.setErr(err);
                    sftp.open().verify(Duration.ofSeconds(10));
                    sftp.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), 0L);
                }
                exit.onExit(0);
            } catch (Exception e) {
                exit.onExit(1, e.getMessage());
            }
        }

        private void print(String text) throws IOException {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        private String readLine() throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) >= 0 && b != '\n') line.write(b);
            return new String(line.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static final class FlushingOutputStream extends OutputStream {
        private final OutputStream target;

        FlushingOutputStream(OutputStream target) {
            this.target = target;
        }

        @Override public void write(int b) throws IOException { target.write(b); target.flush(); }
        @Override public void write(byte[] b, int off, int len) throws IOException { target.write(b, off, len); target.flush(); }
        @Override public void flush() throws IOException { target.flush(); }
    }
}
