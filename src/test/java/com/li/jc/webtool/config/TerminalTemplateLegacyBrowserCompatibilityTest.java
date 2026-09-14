package com.li.jc.webtool.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TerminalTemplateLegacyBrowserCompatibilityTest {

    @Test
    void terminalScriptAvoidsUnsupportedOptionalChaining() throws IOException {
        InputStream input = getClass().getClassLoader().getResourceAsStream("templates/terminal.html");
        assertNotNull(input, "terminal template must be available on the classpath");

        try (InputStream stream = input) {
            String template = new String(readAllBytes(stream), StandardCharsets.UTF_8);
            assertFalse(template.contains("?."),
                    "terminal.html must remain compatible with browsers that do not support optional chaining");
        }
    }

    private byte[] readAllBytes(InputStream input) throws IOException {
        byte[] buffer = new byte[8192];
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
}
