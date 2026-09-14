package com.li.jc.webtool.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ServerConfigPackagedPathTest {
    @TempDir Path directory;

    @Test
    void packagedAppResolvesConfigBesideJarEvenWhenStartedFromAnotherDirectory() throws Exception {
        Path installation = Files.createDirectory(directory.resolve("install with spaces"));
        Path jar = installation.resolve("webtool.jar");
        String resource = ExcelServerConfigLoader.class.getName().replace('.', '/') + ".class";
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar));
             InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            out.putNextEntry(new JarEntry(resource));
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) out.write(buffer, 0, count);
            out.closeEntry();
        }
        Path cwd = Files.createDirectory(directory.resolve("unrelated-working-directory"));
        Path result = directory.resolve("resolved-path.txt");
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(java, "-cp", jar + File.pathSeparator + System.getProperty("java.class.path"),
                PathProbe.class.getName(), result.toString()).directory(cwd.toFile())
                .redirectErrorStream(true).redirectOutput(directory.resolve("probe.log").toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "path probe timed out");
            assertEquals(0, process.exitValue(), new String(Files.readAllBytes(directory.resolve("probe.log")), StandardCharsets.UTF_8));
            assertEquals(installation.resolve("config/servers.xlsx"),
                    Paths.get(new String(Files.readAllBytes(result), StandardCharsets.UTF_8)));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    public static class PathProbe {
        public static void main(String[] args) throws Exception {
            ExcelServerConfigLoader loader = new ExcelServerConfigLoader();
            java.lang.reflect.Method method = ExcelServerConfigLoader.class.getDeclaredMethod("getOutputFile", String.class);
            method.setAccessible(true);
            Path path = (Path) method.invoke(loader, "servers.xlsx");
            Files.write(Paths.get(args[0]), path.toString().getBytes(StandardCharsets.UTF_8));
        }
    }
}
