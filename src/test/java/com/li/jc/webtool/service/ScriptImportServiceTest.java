package com.li.jc.webtool.service;

import com.li.jc.webtool.config.SshSecurityConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ScriptImportServiceTest {
    @TempDir Path temporary;
    private final ScriptImportService importer = new ScriptImportService();
    private static final String MARKER = "%,#@,%%0123456789abcdef0123456789abcdef%";

    @Test void importsBothActualAttachmentsAndPreservesCommandBlocks() throws Exception {
        for (String name : Arrays.asList("baseline", "weak-password")) {
            ScriptImportService.ImportedScript result = importer.importFile(name + ".bms",
                    getClass().getResourceAsStream("/scripts/" + name + ".bms"));
            assertEquals("bms", result.getFormat());
            assertEquals("baseline".equals(name) ? 57 : 5, result.getBlockCount());
            assertTrue(result.getContent().contains("bms_offline_start"));
            assertTrue(result.getContent().contains("bms_offline_end"));
            assertTrue(result.getContent().contains("cat /etc/shadow"));
            assertFalse(result.getContent().contains("\r"));
        }
    }

    @Test void keepsBmsMetadataAsLiteralTextAndCommandsAsExecutableText() {
        String bms = "%,#@,%topic_name=$(touch /should-not-run)\n" + MARKER + "\necho ok\n" + MARKER;
        String script = importer.parse("test.bms", bms).getContent();
        assertTrue(script.contains("printf '%s\\n' '%,#@,%topic_name=$(touch /should-not-run)'"));
        assertTrue(script.contains("\necho ok\n"));
        assertThrows(IllegalArgumentException.class, () -> importer.parse("test.bms", MARKER + "\necho missing-end"));
        assertThrows(IllegalArgumentException.class, () -> importer.parse("test.bms", "echo outside-block"));
    }

    @Test void readsNestedZipScriptWithoutRunningOrExtractingCompanionFiles() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("目录/script.bms"));
            zip.write((MARKER + "\necho ok\n" + MARKER).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("../../verify.exe"));
            zip.write(new byte[]{0, 1, 2}); zip.closeEntry();
        }
        assertEquals(1, importer.importFile("test.zip", new ByteArrayInputStream(bytes.toByteArray())).getBlockCount());
    }

    @Test void rejectsAmbiguousZipAndOversizedScript() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (String name : Arrays.asList("a.sh", "b.sh")) {
                zip.putNextEntry(new ZipEntry(name)); zip.write("echo ok".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            }
        }
        assertThrows(IllegalArgumentException.class, () -> importer.importFile("test.zip", new ByteArrayInputStream(bytes.toByteArray())));
        assertThrows(IllegalArgumentException.class, () -> importer.importFile("large.sh", new ByteArrayInputStream(new byte[ScriptImportService.MAX_BYTES + 1])));
        assertThrows(IllegalArgumentException.class, () -> importer.importFile("os.vbs", new ByteArrayInputStream(new byte[0])));
    }

    @Test void readsGbkShellAndNormalizesWindowsNewlines() throws Exception {
        byte[] bytes = "echo 中文\r\n".getBytes("GBK");
        assertEquals("echo 中文\n", importer.importFile("test.sh", new ByteArrayInputStream(bytes)).getContent());
    }

    @Test void convertedPresetsHaveValidBashSyntax() throws Exception {
        String bash = System.getProperty("script.test.bash");
        Assumptions.assumeTrue(bash != null, "Set script.test.bash to a local Bash executable for syntax validation");
        for (String name : Arrays.asList("baseline", "weak-password")) {
            String script = importer.importFile(name + ".bms", getClass().getResourceAsStream("/scripts/" + name + ".bms")).getContent();
            Path file = temporary.resolve(name + ".sh");
            Files.write(file, script.getBytes(StandardCharsets.UTF_8));
            Process process = new ProcessBuilder(bash, "-n", file.toString().replace('\\', '/')).redirectErrorStream(true).start();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024]; int count;
            while ((count = process.getInputStream().read(buffer)) != -1) output.write(buffer, 0, count);
            assertEquals(0, process.waitFor(), name + ": " + new String(output.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    @Test void presetsRemainSubjectToExistingSafetyPolicy() throws Exception {
        SshSecurityConfig config = new SshSecurityConfig();
        config.setDangerousCommands(Arrays.asList("rm", "rmdir", "dd", "mkfs", "fdisk", "parted", "shutdown", "reboot", "halt", "poweroff", "init"));
        ScriptSecurityService scanner = new ScriptSecurityService();
        ReflectionTestUtils.setField(scanner, "securityConfig", config);
        for (String name : Arrays.asList("baseline", "weak-password")) {
            String script = importer.importFile(name + ".bms", getClass().getResourceAsStream("/scripts/" + name + ".bms")).getContent();
            com.li.jc.webtool.dto.ScriptScanResult scan = scanner.deepScanScriptContent(name, script);
            assertFalse(scan.isPassed(), "Bundled presets must not bypass configured dangerous-command checks");
            assertEquals("high", scan.getRiskLevel());
        }
    }
}
