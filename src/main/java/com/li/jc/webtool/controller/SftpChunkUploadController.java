package com.li.jc.webtool.controller;

import com.li.jc.webtool.service.SftpChunkUploadService;
import com.li.jc.webtool.service.SftpChunkUploadService.UploadException;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.servlet.http.HttpServletRequest;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SFTP 分片上传：init → chunk（按顺序，每片原始字节作为请求体）→ complete；取消时调用 abort。
 */
@Slf4j
@RestController
@RequestMapping("/api/sftp/chunk-upload")
@RequiredArgsConstructor
public class SftpChunkUploadController {

    /** 出错时最多读掉这么多未读的请求体，让浏览器能收到错误响应而不是连接被重置。 */
    private static final long MAX_DRAIN_BYTES = SftpChunkUploadService.CHUNK_SIZE + 1024 * 1024L;

    private final SftpChunkUploadService uploadService;

    @Data
    @lombok.EqualsAndHashCode(callSuper = true)
    public static class InitRequest extends SftpController.SftpRequest {
        /** 相对目标目录的文件路径，上传文件夹时包含子目录。 */
        private String relativePath;
        private long size;
        private long lastModified;
    }

    @Data
    public static class UploadIdRequest {
        private String uploadId;
    }

    @PostMapping("/init")
    public ResponseEntity<Map<String, Object>> init(@RequestBody InitRequest request, HttpServletRequest http) {
        try {
            SftpChunkUploadService.Target target = new SftpChunkUploadService.Target(request,
                    request.getSuUser(), request.getSuPassword(), request.getWebSocketSessionId(),
                    request.getPath(), request.getRelativePath(), request.getSize(), request.getLastModified());
            return ResponseEntity.ok(ok(uploadService.init(owner(http), target)));
        } catch (Exception e) {
            return failure(e, "init", request.getRelativePath());
        }
    }

    @PostMapping(value = "/chunk")
    public ResponseEntity<Map<String, Object>> chunk(@RequestParam("uploadId") String uploadId,
                                                     @RequestParam("offset") long offset,
                                                     HttpServletRequest http) {
        try {
            long received = uploadService.writeChunk(owner(http), uploadId, offset,
                    http.getContentLengthLong(), http.getInputStream());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("received", received);
            return ResponseEntity.ok(ok(body));
        } catch (Exception e) {
            drainQuietly(http);
            return failure(e, "chunk", uploadId + "@" + offset);
        }
    }

    @PostMapping("/complete")
    public ResponseEntity<Map<String, Object>> complete(@RequestBody UploadIdRequest request, HttpServletRequest http) {
        try {
            return ResponseEntity.ok(ok(uploadService.complete(owner(http), request.getUploadId())));
        } catch (Exception e) {
            return failure(e, "complete", request.getUploadId());
        }
    }

    @PostMapping("/abort")
    public ResponseEntity<Map<String, Object>> abort(@RequestBody UploadIdRequest request, HttpServletRequest http) {
        uploadService.abort(owner(http), request.getUploadId());
        return ResponseEntity.ok(ok(new LinkedHashMap<>()));
    }

    private static String owner(HttpServletRequest http) {
        return http.getSession(true).getId();
    }

    private static Map<String, Object> ok(Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.putAll(body);
        return result;
    }

    private ResponseEntity<Map<String, Object>> failure(Exception e, String step, String what) {
        int status;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        if (e instanceof UploadException) {
            UploadException ue = (UploadException) e;
            status = ue.getStatus();
            if (ue.getExpectedOffset() != null) {
                body.put("expectedOffset", ue.getExpectedOffset());
            }
            log.info("SFTP chunk upload {} rejected ({}): {} - {}", step, status, what, e.getMessage());
        } else if (e instanceof ResponseStatusException) {
            status = ((ResponseStatusException) e).getRawStatusCode();
        } else {
            status = 500;
            log.warn("SFTP chunk upload {} failed: {}", step, what, e);
        }
        body.put("message", "上传失败: " + SftpChunkUploadService.describe(e));
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private static void drainQuietly(HttpServletRequest http) {
        try {
            long length = http.getContentLengthLong();
            if (length > MAX_DRAIN_BYTES) {
                return;
            }
            InputStream in = http.getInputStream();
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int n;
            while (total < MAX_DRAIN_BYTES && (n = in.read(buffer)) > 0) {
                total += n;
            }
        } catch (Exception ignored) {
            // 连接已断开时没有可读的数据
        }
    }
}
