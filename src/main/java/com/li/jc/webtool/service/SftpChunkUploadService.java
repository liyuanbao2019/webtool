package com.li.jc.webtool.service;

import com.li.jc.webtool.model.ServerInfo;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.extensions.openssh.OpenSSHPosixRenameExtension;
import org.apache.sshd.sftp.client.extensions.openssh.OpenSSHStatExtensionInfo;
import org.apache.sshd.sftp.client.extensions.openssh.OpenSSHStatPathExtension;
import org.apache.sshd.sftp.client.impl.SftpOutputStreamAsync;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 分片上传：浏览器把文件切成固定大小的分片依次提交，每个分片直接写入远程临时文件的对应偏移，
 * 全部写完后改名为目标文件。
 *
 * <ul>
 *   <li>服务器不落盘，也不受 multipart 大小限制；单个请求只有一个分片，不会因为代理或令牌超时而整体失败。</li>
 *   <li>分片失败只重传这一片；中途断开后再次上传同一文件（名称、大小、修改时间相同）会从已写入的位置继续。</li>
 *   <li>目标文件在全部写完前保持原样，不会留下写了一半的同名文件。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SftpChunkUploadService {

    public static final int CHUNK_SIZE = 16 * 1024 * 1024;
    private static final int WRITE_BUFFER_SIZE = 64 * 1024;
    private static final long STATE_IDLE_TIMEOUT = 12 * 60 * 60 * 1000L;
    private static final int MAX_UPLOADS_PER_OWNER = 50;
    static final String PART_SUFFIX = ".webtool-part";
    private static final int MAX_NAME_BYTES = 255;

    private final SftpSessionManager sessionManager;
    private final ConcurrentMap<String, Upload> uploads = new ConcurrentHashMap<>();

    /** 连接信息与目标位置。 */
    @Getter
    public static final class Target {
        private final ServerInfo server;
        private final String suUser;
        private final String suPassword;
        private final String webSocketSessionId;
        private final String directory;
        private final String relativePath;
        private final long size;
        private final long lastModified;

        public Target(ServerInfo server, String suUser, String suPassword, String webSocketSessionId,
                      String directory, String relativePath, long size, long lastModified) {
            this.server = server;
            this.suUser = suUser;
            this.suPassword = suPassword;
            this.webSocketSessionId = webSocketSessionId;
            this.directory = directory;
            this.relativePath = relativePath;
            this.size = size;
            this.lastModified = lastModified;
        }
    }

    /** 带 HTTP 状态的业务错误；offset 不一致时附带服务器端已写入的位置。 */
    @Getter
    public static final class UploadException extends RuntimeException {
        private final int status;
        private final Long expectedOffset;

        UploadException(int status, String message) {
            this(status, message, null);
        }

        UploadException(int status, String message, Long expectedOffset) {
            super(message);
            this.status = status;
            this.expectedOffset = expectedOffset;
        }
    }

    private static final class Upload {
        private final String id;
        private final String owner;
        private final Target target;
        private final String targetPath;
        private final String partPath;
        private final AtomicBoolean busy = new AtomicBoolean();
        private volatile long received;
        private volatile long lastAccessAt = System.currentTimeMillis();

        private Upload(String id, String owner, Target target, String targetPath, String partPath, long received) {
            this.id = id;
            this.owner = owner;
            this.target = target;
            this.targetPath = targetPath;
            this.partPath = partPath;
            this.received = received;
        }
    }

    public Map<String, Object> init(String owner, Target target) throws Exception {
        if (target.getSize() < 0) {
            throw new UploadException(400, "文件大小不合法");
        }
        String relative = normalizeRelativePath(target.getRelativePath());
        String directory = normalizeDirectory(target.getDirectory());
        String targetPath = join(directory, relative);
        String parent = parentOf(targetPath);
        String fileName = targetPath.substring(targetPath.lastIndexOf('/') + 1);
        String partPath = join(parent, partName(fileName, target.getSize(), target.getLastModified()));

        long offset = execute(target, true, sftp -> {
            ensureDirectoryExists(sftp, parent);
            SftpClient.Attributes parentAttrs = statOrNull(sftp, parent);
            if (parentAttrs == null || !parentAttrs.isDirectory()) {
                throw new UploadException(400, "目标目录不存在或不是目录: " + parent);
            }
            SftpClient.Attributes existing = statOrNull(sftp, targetPath);
            if (existing != null && existing.isDirectory()) {
                throw new UploadException(409, "目标位置已存在同名目录: " + targetPath);
            }
            long resumeFrom = 0;
            SftpClient.Attributes part = statOrNull(sftp, partPath);
            if (part != null && part.isRegularFile() && part.getSize() <= target.getSize()) {
                // 分片按顺序写，一片全部确认后才写下一片；只有最后一片可能不完整，从它的起点重传
                resumeFrom = part.getSize() / CHUNK_SIZE * CHUNK_SIZE;
            }
            checkFreeSpace(sftp, parent, target.getSize() - resumeFrom);
            if (target.getSize() == 0) {
                try (OutputStream ignored = sftp.write(targetPath, SftpClient.OpenMode.Write,
                        SftpClient.OpenMode.Create, SftpClient.OpenMode.Truncate)) {
                    // 空文件直接创建
                }
                removeQuietly(sftp, partPath);
            }
            return resumeFrom;
        });

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("chunkSize", CHUNK_SIZE);
        result.put("size", target.getSize());
        result.put("targetPath", targetPath);
        if (target.getSize() == 0) {
            result.put("completed", true);
            result.put("offset", 0L);
            return result;
        }
        cleanupStale(owner);
        String id = UUID.randomUUID().toString().replace("-", "");
        uploads.put(id, new Upload(id, owner, target, targetPath, partPath, offset));
        if (offset > 0) {
            log.info("SFTP upload resumed: {} at {} / {}", targetPath, offset, target.getSize());
        }
        result.put("uploadId", id);
        result.put("offset", offset);
        result.put("resumed", offset > 0);
        return result;
    }

    /** 写入一个分片，返回服务器端已确认写入的字节数。 */
    public long writeChunk(String owner, String uploadId, long offset, long contentLength, InputStream body)
            throws Exception {
        Upload upload = require(owner, uploadId);
        if (!upload.busy.compareAndSet(false, true)) {
            throw new UploadException(409, "该文件的上一个分片仍在写入", upload.received);
        }
        try {
            upload.lastAccessAt = System.currentTimeMillis();
            long size = upload.target.getSize();
            if (offset != upload.received) {
                throw new UploadException(409, "分片位置不一致", upload.received);
            }
            long expected = Math.min(CHUNK_SIZE, size - offset);
            if (expected <= 0) {
                throw new UploadException(409, "文件已全部上传", upload.received);
            }
            if (contentLength >= 0 && contentLength != expected) {
                throw new UploadException(400, "分片大小不正确：应为 " + expected + " 字节，实际 " + contentLength);
            }
            execute(upload.target, false, sftp -> {
                writeAt(sftp, upload.partPath, offset, expected, body);
                return null;
            });
            upload.received = offset + expected;
            upload.lastAccessAt = System.currentTimeMillis();
            return upload.received;
        } finally {
            upload.busy.set(false);
        }
    }

    public Map<String, Object> complete(String owner, String uploadId) throws Exception {
        Upload upload = require(owner, uploadId);
        long size = upload.target.getSize();
        if (upload.received != size) {
            throw new UploadException(409, "文件还未全部上传", upload.received);
        }
        if (!upload.busy.compareAndSet(false, true)) {
            throw new UploadException(409, "该文件正在处理", upload.received);
        }
        try {
            execute(upload.target, true, sftp -> {
                SftpClient.Attributes part = statOrNull(sftp, upload.partPath);
                if (part == null) {
                    // 上一次 complete 已改名成功但响应丢失时，重试会走到这里
                    SftpClient.Attributes done = statOrNull(sftp, upload.targetPath);
                    if (done != null && done.isRegularFile() && done.getSize() == size) {
                        return null;
                    }
                    throw new UploadException(410, "服务器上的临时文件已不存在，请重新上传");
                }
                if (part.getSize() != size) {
                    removeQuietly(sftp, upload.partPath);
                    throw new UploadException(410, "服务器上的临时文件大小为 " + part.getSize()
                            + " 字节，与原文件 " + size + " 字节不一致，已删除，请重新上传");
                }
                moveIntoPlace(sftp, upload.partPath, upload.targetPath);
                return null;
            });
        } catch (UploadException e) {
            if (e.getStatus() == 410) {
                uploads.remove(uploadId, upload);
            }
            throw e;
        } finally {
            upload.busy.set(false);
        }
        uploads.remove(uploadId, upload);
        log.info("SFTP upload completed: {} ({} bytes)", upload.targetPath, size);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", upload.targetPath);
        result.put("size", size);
        return result;
    }

    /** 取消上传并删除临时文件。 */
    public void abort(String owner, String uploadId) {
        Upload upload = uploads.get(uploadId);
        if (upload == null || !upload.owner.equals(owner)) {
            return;
        }
        uploads.remove(uploadId, upload);
        try {
            execute(upload.target, true, sftp -> {
                removeQuietly(sftp, upload.partPath);
                return null;
            });
        } catch (Exception e) {
            log.warn("Remove SFTP upload temp file failed: {}", upload.partPath, e);
        }
    }

    /** 把 SFTP 异常转换成用户能看懂的原因。 */
    public static String describe(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof UploadException) {
                return t.getMessage();
            }
            if (t instanceof SftpException) {
                int status = ((SftpException) t).getStatus();
                switch (status) {
                    case SftpConstants.SSH_FX_PERMISSION_DENIED:
                        return "没有写入权限（" + t.getMessage() + "）";
                    case SftpConstants.SSH_FX_NO_SUCH_FILE:
                    case SftpConstants.SSH_FX_NO_SUCH_PATH:
                        return "目录不存在（" + t.getMessage() + "）";
                    case SftpConstants.SSH_FX_NO_SPACE_ON_FILESYSTEM:
                    case SftpConstants.SSH_FX_QUOTA_EXCEEDED:
                        return "服务器磁盘空间或配额不足（" + t.getMessage() + "）";
                    case SftpConstants.SSH_FX_FAILURE:
                        return "服务器写入失败，常见原因是磁盘已满或文件被占用（" + t.getMessage() + "）";
                    default:
                        return t.getMessage();
                }
            }
        }
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
    }

    // ---------------------------------------------------------------- 内部实现

    private <T> T execute(Target target, boolean retry, SftpSessionManager.SftpOperation<T> operation)
            throws Exception {
        return sessionManager.execute(target.getServer(), target.getSuUser(), target.getSuPassword(),
                target.getWebSocketSessionId(), retry, operation);
    }

    private Upload require(String owner, String uploadId) {
        Upload upload = uploadId == null ? null : uploads.get(uploadId);
        if (upload == null || !upload.owner.equals(owner)) {
            throw new UploadException(404, "上传任务不存在或已过期");
        }
        return upload;
    }

    private void cleanupStale(String owner) {
        long now = System.currentTimeMillis();
        uploads.values().removeIf(u -> !u.busy.get() && now - u.lastAccessAt > STATE_IDLE_TIMEOUT);
        List<Upload> mine = new ArrayList<>();
        uploads.values().forEach(u -> {
            if (u.owner.equals(owner)) {
                mine.add(u);
            }
        });
        if (mine.size() >= MAX_UPLOADS_PER_OWNER) {
            mine.sort(Comparator.comparingLong(u -> u.lastAccessAt));
            for (int i = 0; i <= mine.size() - MAX_UPLOADS_PER_OWNER; i++) {
                if (!mine.get(i).busy.get()) {
                    uploads.remove(mine.get(i).id, mine.get(i));
                }
            }
        }
    }

    /** 从 offset 起写入 length 个字节；数据不足或多出都视为失败，不推进进度。 */
    static void writeAt(SftpClient sftp, String path, long offset, long length, InputStream body) throws IOException {
        SftpClient.OpenMode[] modes = offset == 0
                ? new SftpClient.OpenMode[]{SftpClient.OpenMode.Write, SftpClient.OpenMode.Create,
                SftpClient.OpenMode.Truncate}
                : new SftpClient.OpenMode[]{SftpClient.OpenMode.Write, SftpClient.OpenMode.Create};
        try (OutputStream os = sftp.write(path, WRITE_BUFFER_SIZE, modes)) {
            if (offset > 0) {
                if (!(os instanceof SftpOutputStreamAsync)) {
                    throw new IOException("当前 SFTP 客户端不支持按偏移写入");
                }
                ((SftpOutputStreamAsync) os).setOffset(offset);
            }
            byte[] buffer = new byte[WRITE_BUFFER_SIZE];
            long remaining = length;
            while (remaining > 0) {
                int n = body.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (n < 0) {
                    throw new IOException("分片数据不完整：收到 " + (length - remaining) + " / " + length + " 字节，连接可能已中断");
                }
                os.write(buffer, 0, n);
                remaining -= n;
            }
            if (body.read() >= 0) {
                throw new IOException("分片数据超出预期长度 " + length + " 字节");
            }
        }
    }

    private static void moveIntoPlace(SftpClient sftp, String from, String to) throws IOException {
        OpenSSHPosixRenameExtension posix = sftp.getExtension(OpenSSHPosixRenameExtension.class);
        if (posix != null && posix.isSupported()) {
            posix.posixRename(from, to); // 原子替换已有文件
            return;
        }
        try {
            sftp.rename(from, to);
        } catch (IOException e) {
            SftpClient.Attributes existing = statOrNull(sftp, to);
            if (existing == null || existing.isDirectory()) {
                throw e;
            }
            sftp.remove(to);
            sftp.rename(from, to);
        }
    }

    private static void checkFreeSpace(SftpClient sftp, String directory, long needed) {
        if (needed <= 0) {
            return;
        }
        try {
            OpenSSHStatPathExtension ext = sftp.getExtension(OpenSSHStatPathExtension.class);
            if (ext == null || !ext.isSupported()) {
                return;
            }
            OpenSSHStatExtensionInfo info = ext.stat(directory);
            long unit = info.f_frsize > 0 ? info.f_frsize : info.f_bsize;
            if (unit <= 0 || info.f_blocks <= 0) {
                return;
            }
            long free = info.f_bavail > Long.MAX_VALUE / unit ? Long.MAX_VALUE : info.f_bavail * unit;
            if (free < needed) {
                throw new UploadException(507, "服务器目录 " + directory + " 所在磁盘剩余 " + humanSize(free)
                        + "，还需要写入 " + humanSize(needed) + "，空间不足");
            }
        } catch (UploadException e) {
            throw e;
        } catch (Exception e) {
            log.debug("statvfs not available for {}: {}", directory, e.getMessage());
        }
    }

    static void ensureDirectoryExists(SftpClient sftp, String path) throws IOException {
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return;
        }
        if (statOrNull(sftp, path) != null) {
            return;
        }
        int slash = path.lastIndexOf('/');
        if (slash > 0) {
            ensureDirectoryExists(sftp, path.substring(0, slash));
        }
        try {
            sftp.mkdir(path);
        } catch (IOException e) {
            if (statOrNull(sftp, path) == null) { // 并发创建时目录已存在，忽略
                throw e;
            }
        }
    }

    private static SftpClient.Attributes statOrNull(SftpClient sftp, String path) throws IOException {
        try {
            return sftp.stat(path);
        } catch (SftpException e) {
            if (e.getStatus() == SftpConstants.SSH_FX_NO_SUCH_FILE || e.getStatus() == SftpConstants.SSH_FX_NO_SUCH_PATH) {
                return null;
            }
            throw e;
        }
    }

    private static void removeQuietly(SftpClient sftp, String path) {
        try {
            sftp.remove(path);
        } catch (IOException ignored) {
        }
    }

    static String normalizeRelativePath(String relativePath) {
        if (relativePath == null) {
            throw new UploadException(400, "缺少文件名");
        }
        String[] parts = relativePath.replace('\\', '/').split("/");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (".".equals(part) || "..".equals(part) || part.indexOf('\0') >= 0) {
                throw new UploadException(400, "文件路径不合法: " + relativePath);
            }
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(part);
        }
        if (sb.length() == 0) {
            throw new UploadException(400, "缺少文件名");
        }
        return sb.toString();
    }

    static String normalizeDirectory(String directory) {
        if (directory == null || directory.trim().isEmpty()) {
            throw new UploadException(400, "缺少目标目录");
        }
        String dir = directory.trim().replace('\\', '/');
        while (dir.length() > 1 && dir.endsWith("/")) {
            dir = dir.substring(0, dir.length() - 1);
        }
        return dir;
    }

    private static String join(String directory, String name) {
        return directory.endsWith("/") ? directory + name : directory + "/" + name;
    }

    private static String parentOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash <= 0 ? "/" : path.substring(0, slash);
    }

    /** 临时文件名包含大小、修改时间和分片大小，只有同一个文件再次上传才会续传。 */
    static String partName(String fileName, long size, long lastModified) {
        String fingerprint = Long.toHexString(size) + "-" + Long.toHexString(lastModified) + "-"
                + Integer.toHexString(CHUNK_SIZE);
        String name = "." + fileName + "." + fingerprint + PART_SUFFIX;
        if (name.getBytes(StandardCharsets.UTF_8).length <= MAX_NAME_BYTES) {
            return name;
        }
        return ".webtool-upload-" + sha256(fileName + "|" + fingerprint).substring(0, 32) + PART_SUFFIX;
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB", "PB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }
}
