package com.gxcj.xjtool.service;

import com.gxcj.xjtool.config.ServerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class SftpSessionManagerTest {

    @Test
    void preservesAgentModeSftpRestriction() {
        ServerConfig config = new ServerConfig();
        SftpSessionManager manager = new SftpSessionManager(config, mock(SshService.class));

        assertDoesNotThrow(manager::assertSftpAllowed);

        config.getAgent().setEnabled(true);
        assertThrows(ResponseStatusException.class, manager::assertSftpAllowed);
    }
}
