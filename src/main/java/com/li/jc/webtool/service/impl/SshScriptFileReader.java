package com.li.jc.webtool.service.impl;

import com.li.jc.webtool.dto.ScriptReadResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;

/** Reads bounded script files over an already authenticated SSH session. */
@Slf4j
@Component
public class SshScriptFileReader {

    private static final long MAX_SCRIPT_SIZE = 1024L * 1024L;

    public ScriptReadResult read(ClientSession clientSession, String sessionId, String scriptPath,
            String shellWorkingDirectory, Charset charset) {
        if (scriptPath == null || scriptPath.trim().isEmpty()) {
            log.warn("读取脚本文件失败：脚本路径为空, sessionId={}", sessionId);
            return null;
        }
        String requestedPath = scriptPath.trim();
        if (isVirtualFileSystem(requestedPath)) {
            log.warn("读取脚本文件失败：禁止访问虚拟文件系统路径: {}", requestedPath);
            return null;
        }

        log.info("通过SFTP读取远程脚本文件: sessionId={}, requestPath={}, shellCwd={}",
                sessionId, requestedPath, shellWorkingDirectory);
        try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(clientSession)) {
            String content = readPath(sftp, requestedPath, charset, sessionId);
            if (content != null) {
                return new ScriptReadResult(content, requestedPath);
            }
            String name = UnixPathSupport.basename(requestedPath);
            if (name != null && !name.isEmpty() && shellWorkingDirectory != null) {
                String alternative = UnixPathSupport.normalize(shellWorkingDirectory + "/" + name);
                if (!alternative.equals(requestedPath) && !isVirtualFileSystem(alternative)) {
                    log.info("SFTP 首次路径未读到文件，按 shell 推断目录重试: sessionId={}, altPath={}",
                            sessionId, alternative);
                    content = readPath(sftp, alternative, charset, sessionId);
                    if (content != null) {
                        return new ScriptReadResult(content, alternative);
                    }
                }
            }
        } catch (Exception e) {
            log.error("通过SFTP读取脚本文件失败: sessionId={}, path={}, error={}",
                    sessionId, requestedPath, e.getMessage());
        }
        return null;
    }

    private String readPath(SftpClient sftp, String path, Charset charset, String sessionId) {
        try {
            SftpClient.Attributes attributes = sftp.stat(path);
            if (attributes.isDirectory()) {
                log.warn("读取脚本文件失败：目标路径是目录而非文件: {}", path);
                return null;
            }
            if (attributes.getSize() > MAX_SCRIPT_SIZE) {
                log.warn("读取脚本文件失败：文件过大 ({} 字节)，超过最大限制 {} 字节: {}",
                        attributes.getSize(), MAX_SCRIPT_SIZE, path);
                return null;
            }
            try (InputStream input = sftp.read(path); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int length;
                while ((length = input.read(buffer)) != -1) {
                    output.write(buffer, 0, length);
                }
                String content = output.toString(charset.name());
                log.info("成功读取远程脚本文件: {} ({} 字节), sessionId={}", path, content.length(), sessionId);
                return content;
            }
        } catch (Exception e) {
            log.debug("SFTP 读取路径失败: sessionId={}, path={}, error={}", sessionId, path, e.getMessage());
            return null;
        }
    }

    private boolean isVirtualFileSystem(String path) {
        return path.startsWith("/dev/") || path.startsWith("/proc/") || path.startsWith("/sys/");
    }
}
