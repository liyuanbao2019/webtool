package com.gxcj.xjtool.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OracleHealthCheckServiceSystemMessageTest {

    @Test
    void retainsActionableErrorOrFailMessages() {
        String output = "Jul 17 kernel: disk I/O error on device sdb";
        assertEquals(output, OracleHealthCheckService.actionableSystemMessageOutput(output));
    }

    @Test
    void suppressesKnownNonActionableMessages() {
        String[] ignoredMessages = {
                "FAILED SU (to root) oracle on pts/0",
                "sd_journal_get_cursor failed",
                "org.bluez error",
                "dbus-daemon fail",
                "connection to 10.235.107.225 failed",
                "download https://extensions.gnome.org/ failed",
                "and filing a bug with the additional information"
        };
        for (String message : ignoredMessages) {
            assertEquals("", OracleHealthCheckService.actionableSystemMessageOutput(message));
        }
    }
}
