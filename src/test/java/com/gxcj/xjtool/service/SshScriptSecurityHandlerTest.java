package com.gxcj.xjtool.service;

import com.gxcj.xjtool.model.WebSshData;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

class SshScriptSecurityHandlerTest {

    @Test
    void ignoresOrdinaryInteractiveCommands() {
        SshScriptSecurityHandler handler = new SshScriptSecurityHandler();

        SshScriptSecurityHandler.Decision decision = handler.check(
                mock(org.springframework.web.socket.WebSocketSession.class),
                new WebSshData(), "pwd", StandardCharsets.UTF_8, bytes -> { });

        assertNull(decision);
    }
}
