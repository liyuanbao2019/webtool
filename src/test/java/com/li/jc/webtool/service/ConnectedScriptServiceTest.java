package com.li.jc.webtool.service;

import com.li.jc.webtool.config.SshSecurityConfig;
import com.li.jc.webtool.dto.ScriptScanResult;
import com.li.jc.webtool.service.impl.DangerousCommandTokenServiceImpl;
import com.li.jc.webtool.websocket.WebSshWebSocketHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.WebSocketSession;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConnectedScriptServiceTest {
    private SshService ssh;
    private ScriptSecurityService scanner;
    private SshSecurityConfig config;
    private ConnectedScriptService service;
    private WebSocketSession session;

    @BeforeEach void setup() {
        ssh = mock(SshService.class);
        scanner = mock(ScriptSecurityService.class);
        config = new SshSecurityConfig();
        service = new ConnectedScriptService(ssh, new ScriptImportService(), scanner, config,
                new DangerousCommandTokenServiceImpl(), mock(SshAuditService.class));
        session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("ws-script-test");
        when(session.isOpen()).thenReturn(true);
        Map<String, Object> attributes = new HashMap<>(); attributes.put("HTTP.SESSION.ID", "owner");
        when(session.getAttributes()).thenReturn(attributes);
        when(ssh.isSshConnected(session)).thenReturn(true);
        WebSshWebSocketHandler.registerSession(session);
        when(scanner.deepScanScriptContent(anyString(), anyString())).thenReturn(ScriptScanResult.safe());
    }

    @AfterEach void cleanup() {
        service.close(); WebSshWebSocketHandler.unregisterSession(session.getId());
    }

    private ConnectedScriptService.RunRequest request(String content) {
        ConnectedScriptService.RunRequest request = new ConnectedScriptService.RunRequest();
        request.setSessionId(session.getId()); request.setContent(content);
        return request;
    }

    @Test void refusesAnotherHttpSessionAndInvalidConnection() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.start("intruder", request("echo ok")));
        assertEquals(403, error.getStatus().value());
        assertThrows(IllegalArgumentException.class, () -> service.start("owner", request(null)));
        when(ssh.isSshConnected(session)).thenReturn(false);
        assertThrows(IllegalArgumentException.class, () -> service.start("owner", request("echo ok")));
    }

    @Test void returnsStreamingOutputExitStatusAndPrivateResults() throws Exception {
        when(ssh.executeScript(eq(session), anyString(), any(), anyLong())).thenAnswer(call -> {
            OutputStream output = call.getArgument(2); output.write("中文结果\n".getBytes(StandardCharsets.UTF_8)); return 7;
        });
        String id = (String) service.start("owner", request("echo ok")).get("taskId");
        Map<String, Object> result = awaitFinished(id);
        assertEquals("failed", result.get("status"));
        assertEquals(7, result.get("exitCode"));
        assertEquals("中文结果\n", result.get("output"));
        assertThrows(ResponseStatusException.class, () -> service.status("intruder", id, 0));
        assertThrows(ResponseStatusException.class, () -> service.cancel("intruder", id));
    }

    @Test void blocksHighRiskEvenWithSuppliedToken() throws Exception {
        when(scanner.deepScanScriptContent(anyString(), anyString())).thenReturn(
                ScriptScanResult.needConfirm(Collections.emptyList(), "high", "danger"));
        ConnectedScriptService.RunRequest request = request("rm -rf /data");
        request.setConfirmationToken("invented");
        assertEquals(true, service.start("owner", request).get("blocked"));
        verify(ssh, never()).executeScript(any(), anyString(), any(), anyLong());
    }

    @Test void bindsConfirmationToExactContentAndConsumesOnce() throws Exception {
        config.setBlockCriticalScriptOps(false);
        when(scanner.deepScanScriptContent(anyString(), anyString())).thenReturn(
                ScriptScanResult.needConfirm(Collections.emptyList(), "medium", "confirm"));
        ConnectedScriptService.RunRequest request = request("echo original");
        String token = (String) service.start("owner", request).get("confirmationToken");
        request.setConfirmationToken(token); request.setContent("echo tampered");
        assertThrows(IllegalArgumentException.class, () -> service.start("owner", request));
        ConnectedScriptService.RunRequest confirmed = request("echo original");
        confirmed.setConfirmationToken((String) service.start("owner", confirmed).get("confirmationToken"));
        String id = (String) service.start("owner", confirmed).get("taskId");
        assertNotNull(id); awaitFinished(id);
        ConnectedScriptService.RunRequest replay = confirmed;
        assertThrows(IllegalArgumentException.class, () -> service.start("owner", replay));
    }

    @Test void preventsOverlappingTasksAndCancellationReleasesConnection() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        when(ssh.executeScript(any(), anyString(), any(), anyLong())).thenAnswer(call -> {
            entered.countDown(); new CountDownLatch(1).await(5, TimeUnit.SECONDS); return 0;
        });
        String id = (String) service.start("owner", request("sleep 10")).get("taskId");
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertThrows(ResponseStatusException.class, () -> service.start("owner", request("echo overlap")));
        service.cancel("owner", id);
        assertEquals("cancelled", awaitFinished(id).get("status"));
    }

    @Test void boundsOutputAndPreservesUtf8AcrossPackets() throws Exception {
        CountDownLatch partial = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        when(ssh.executeScript(any(), anyString(), any(), anyLong())).thenAnswer(call -> {
            OutputStream output = call.getArgument(2);
            byte[] text = "中".getBytes(StandardCharsets.UTF_8);
            output.write(text, 0, 1); partial.countDown();
            assertTrue(proceed.await(2, TimeUnit.SECONDS));
            output.write(text, 1, 2);
            output.write(new byte[2 * 1024 * 1024]);
            return 0;
        });
        String id = (String) service.start("owner", request("echo test")).get("taskId");
        assertTrue(partial.await(2, TimeUnit.SECONDS));
        assertEquals("", service.status("owner", id, 0).get("output"));
        proceed.countDown();
        Map<String, Object> result = awaitFinished(id);
        assertTrue(((String) result.get("output")).startsWith("中"));
        assertEquals(true, result.get("truncated"));
    }

    private Map<String, Object> awaitFinished(String id) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        Map<String, Object> result;
        do {
            result = service.status("owner", id, 0);
            if (!"queued".equals(result.get("status")) && !"running".equals(result.get("status"))) return result;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        fail("Task did not finish in time"); return result;
    }
}
