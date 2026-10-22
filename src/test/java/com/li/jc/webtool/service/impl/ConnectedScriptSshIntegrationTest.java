package com.li.jc.webtool.service.impl;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.WebSocketSession;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConnectedScriptSshIntegrationTest {
    @TempDir Path temporary;

    @Test void reusesAuthenticatedConnectionAndStreamsLongScriptWithoutPtyTruncation() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        try (SshServer server = server(false, received); SshClient client = client()) {
            try (ClientSession connection = client.connect("test", "127.0.0.1", server.getPort()).verify(5000).getSession()) {
                connection.addPasswordIdentity("test"); connection.auth().verify(5000);
                WebSocketSession ws = mock(WebSocketSession.class);
                when(ws.getId()).thenReturn("local-test"); when(ws.isOpen()).thenReturn(true);
                SshServiceImpl service = service(connection, ws);
                StringBuilder script = new StringBuilder("echo 中文\n");
                for (int i = 0; i < 10000; i++) script.append("# long script\n");
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                assertTrue(service.isSshConnected(ws));
                assertEquals(7, service.executeScript(ws, script.toString(), output, 5000));
                assertEquals(script.toString(), received.get());
                String result = new String(output.toByteArray(), StandardCharsets.UTF_8);
                assertTrue(result.contains("中文输出")); assertTrue(result.contains("stderr"));
                assertTrue(connection.isOpen(), "The shared SSH connection must remain usable");
            }
        }
    }

    @Test void enforcesExecutionTimeoutWithoutClosingSharedSshConnection() throws Exception {
        try (SshServer server = server(true, new AtomicReference<>()); SshClient client = client()) {
            try (ClientSession connection = client.connect("test", "127.0.0.1", server.getPort()).verify(5000).getSession()) {
                connection.addPasswordIdentity("test"); connection.auth().verify(5000);
                WebSocketSession ws = mock(WebSocketSession.class);
                when(ws.getId()).thenReturn("timeout-test"); when(ws.isOpen()).thenReturn(true);
                SshServiceImpl service = service(connection, ws);
                assertThrows(TimeoutException.class, () -> service.executeScript(ws, "echo test\n", new ByteArrayOutputStream(), 500));
                assertTrue(connection.isOpen());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private SshServiceImpl service(ClientSession client, WebSocketSession ws) throws Exception {
        SshServiceImpl service = new SshServiceImpl();
        Class<?> type = Class.forName(SshServiceImpl.class.getName() + "$SshConnection");
        Constructor<?> constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
        Object connection = constructor.newInstance(); ReflectionTestUtils.setField(connection, "session", client);
        ((Map<String, Object>) ReflectionTestUtils.getField(service, "sshMap")).put(ws.getId(), connection);
        return service;
    }

    private SshClient client() {
        SshClient client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE); client.start(); return client;
    }

    private SshServer server(boolean hang, AtomicReference<String> received) throws Exception {
        SshServer server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1"); server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(temporary.resolve("hostkey")));
        server.setPasswordAuthenticator((user, pass, session) -> "test".equals(user) && "test".equals(pass));
        server.setCommandFactory((channel, command) -> {
            assertEquals("bash -s", command);
            return new Command() {
                InputStream input; OutputStream output; OutputStream error; ExitCallback exit; Thread worker;
                @Override public void setInputStream(InputStream stream) { input = stream; }
                @Override public void setOutputStream(OutputStream stream) { output = stream; }
                @Override public void setErrorStream(OutputStream stream) { error = stream; }
                @Override public void setExitCallback(ExitCallback callback) { exit = callback; }
                @Override public void start(ChannelSession channel, Environment environment) {
                    worker = new Thread(() -> {
                        try {
                            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                            byte[] buffer = new byte[8192]; int count;
                            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
                            received.set(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
                            if (hang) Thread.sleep(10000);
                            output.write("中文输出\n".getBytes(StandardCharsets.UTF_8)); output.flush();
                            error.write("stderr\n".getBytes(StandardCharsets.UTF_8)); error.flush();
                            exit.onExit(7);
                        } catch (Exception e) { exit.onExit(1); }
                    }, "script-test-command");
                    worker.setDaemon(true); worker.start();
                }
                @Override public void destroy(ChannelSession channel) { if (worker != null) worker.interrupt(); }
            };
        });
        server.start(); return server;
    }
}
