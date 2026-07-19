package com.gxcj.xjtool.service;

import com.gxcj.xjtool.dto.NodeDeployResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeployExecutionServiceTest {

    @Test
    void reportsMissingServerWithoutOpeningSshConnection() {
        DeployExecutionService service = new DeployExecutionService();

        NodeDeployResult result = service.runOne("missing", null, "/tmp", null,
                false, false, "backup", false, null, false, null);

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("missing"));
    }
}
