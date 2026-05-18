package com.gxcj.xjtool.controller;

import com.gxcj.xjtool.config.SshSecurityConfig;
import com.gxcj.xjtool.dto.ScriptReadResult;
import com.gxcj.xjtool.dto.ScriptScanResult;
import com.gxcj.xjtool.service.DangerousCommandTokenService;
import com.gxcj.xjtool.service.ScriptSecurityService;
import com.gxcj.xjtool.service.SshService;
import com.gxcj.xjtool.websocket.WebSshWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;

/**
 * SSH安全配置API
 * 提供SSH终端安全配置的查询接口、脚本安全扫描和危险命令确认接口
 */
@RestController
@RequestMapping("/api/ssh/security")
public class SshSecurityController {

    private static final Logger log = LoggerFactory.getLogger(SshSecurityController.class);

    @Autowired
    private SshSecurityConfig sshSecurityConfig;

    @Autowired
    private DangerousCommandTokenService tokenService;

    @Autowired
    private ScriptSecurityService scriptSecurityService;

    @Autowired
    private SshService sshService;

    /**
     * 获取危险命令配置
     *
     * @return 包含危险命令列表和确认开关的配置
     */
    @GetMapping("/dangerous-commands")
    public Map<String, Object> getDangerousCommands() {
        Map<String, Object> result = new HashMap<>();
        result.put("enabled", sshSecurityConfig.isDangerousCommandConfirm());
        result.put("commands", sshSecurityConfig.getDangerousCommands());
        return result;
    }

    /**
     * 生成危险命令确认Token
     *
     * @param sessionId WebSocket会话ID
     * @param command   待执行的命令
     * @return Token信息
     */
    @PostMapping("/generate-token")
    public Map<String, Object> generateToken(@RequestParam(required = false) String sessionId,
            @RequestParam String command,
            javax.servlet.http.HttpServletRequest request) {
        // 强制使用全局统一的 HTTP Session ID 为安全令牌的唯一绑定对象（废除前端的动态传入覆写）
        String finalSessionId = request.getSession().getId();
        String token = tokenService.generateToken(finalSessionId, command);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("token", token);
        return result;
    }

    /**
     * 扫描脚本内容是否包含危险操作
     * 前端在用户执行脚本命令时，先调用此接口进行预扫描。
     * 当传入 sessionId 和 scriptPath 时，后端会通过SFTP读取远程脚本文件进行深度扫描。
     *
     * @param scriptContent    脚本内容（可以是完整的命令，如 "sh -c 'rm -rf /'"）
     * @param scriptPreviewContent 脚本预览内容（同 scriptContent，默认相同）
     * @param sessionId        WebSocket会话ID（用于通过SFTP读取远程脚本文件）
     * @param scriptPath       脚本文件路径（当检测到是脚本文件时，通过SFTP读取内容）
     * @return 扫描结果
     */
    @PostMapping("/scan-script")
    public Map<String, Object> scanScript(@RequestParam String scriptContent,
                                          @RequestParam(required = false) String scriptPreviewContent,
                                          @RequestParam(required = false) String sessionId,
                                          @RequestParam(required = false) String scriptPath,
                                          javax.servlet.http.HttpServletRequest request) {
        log.info("========================================");
        log.info("【脚本安全扫描】收到扫描请求");
        log.info("  命令: {}", scriptContent);
        log.info("  脚本路径: {}", scriptPath);
        log.info("  WebSocket会话ID: {}", sessionId);
        log.info("========================================");

        // Step 1: 尝试通过SFTP读取远程脚本文件内容（如果提供了 scriptPath）
        String contentToScan = scriptContent;
        boolean remoteContentLoaded = false;
        /** SFTP 实际读取成功的绝对路径（可能与请求中的 scriptPath 不同，例如按 shell cwd 回退） */
        String resolvedScriptRemotePath = null;

        if (scriptPath != null && !scriptPath.trim().isEmpty() && sessionId != null && !sessionId.trim().isEmpty()) {
            // 通过 sessionId 查找对应的 WebSocketSession
            WebSocketSession wsSession = findWebSocketSession(sessionId);
            if (wsSession != null && wsSession.isOpen()) {
                // 获取终端字符集
                String charsetName = "UTF-8";
                Object charsetAttr = wsSession.getAttributes().get("charset");
                if (charsetAttr != null) {
                    charsetName = charsetAttr.toString();
                }

                log.info("【SFTP读取】正在读取远程脚本文件: sessionId={}, requestPath={}, charset={}",
                        sessionId, scriptPath, charsetName);

                ScriptReadResult readResult = sshService.readScriptFile(wsSession, scriptPath, charsetName);
                if (readResult != null && readResult.getContent() != null && !readResult.getContent().trim().isEmpty()) {
                    String remoteContent = readResult.getContent();
                    resolvedScriptRemotePath = readResult.getResolvedRemotePath();
                    contentToScan = remoteContent;
                    remoteContentLoaded = true;

                    // 计算内容行数（Java 8 无 String#lines，使用 split 统计行）
                    long lineCount = remoteContent.split("\\R", -1).length;
                    log.info("【SFTP读取】成功读取脚本文件内容: resolvedPath={}, requestPath={}, {} 字节, {} 行",
                            resolvedScriptRemotePath, scriptPath, remoteContent.length(), lineCount);

                    // 打印脚本内容前5行用于调试追踪
                    String[] lines = remoteContent.split("\n", 6);
                    log.info("【脚本内容预览】(前5行):");
                    for (int i = 0; i < Math.min(lines.length, 5); i++) {
                        log.info("  Line {}: {}", i + 1, lines[i]);
                    }
                    if (lines.length > 5) {
                        log.info("  ... (共 {} 行，以上仅显示前5行)", lineCount);
                    }
                } else {
                    log.warn("【SFTP读取】未能读取到脚本内容，回退到命令本身: {}", scriptContent);
                }
            } else {
                log.warn("【SFTP读取】WebSocket会话不存在或已关闭: sessionId={}", sessionId);
            }
        } else {
            log.info("【扫描模式】直接扫描命令本身（未提供脚本路径或会话ID）");
        }

        // Step 2: 根据扫描内容类型选择扫描策略
        ScriptScanResult result;
        if (remoteContentLoaded) {
            // 深度扫描脚本文件内容（日志与 ScriptSecurityService 使用实际读取路径）
            String pathForDeepScan = resolvedScriptRemotePath != null ? resolvedScriptRemotePath : scriptPath;
            log.info("【深度扫描】对脚本文件内容进行安全扫描: resolvedPath={}, requestPath={}",
                    pathForDeepScan, scriptPath);
            result = scriptSecurityService.deepScanScriptContent(pathForDeepScan, contentToScan);
        } else {
            // 扫描命令本身（内联脚本或命令行）
            log.info("【命令扫描】对命令本身进行安全扫描");
            result = scriptSecurityService.scanCommand(contentToScan);
        }

        // Step 3: 打印扫描结果
        log.info("========================================");
        log.info("【扫描结果】风险等级: {}, 是否通过: {}, 是否需要确认: {}",
                result.getRiskLevel(), result.isPassed(), result.isNeedConfirm());
        log.info("【扫描结果】消息: {}", result.getMessage());
        if (!result.getDangerousOperations().isEmpty()) {
            log.info("【危险操作列表】共检测到 {} 个危险操作:", result.getDangerousOperations().size());
            for (int i = 0; i < Math.min(result.getDangerousOperations().size(), 10); i++) {
                ScriptScanResult.DangerousOperation op = result.getDangerousOperations().get(i);
                log.info("  {}. [{}] {} - {}", i + 1, op.getRiskLevel().toUpperCase(), op.getType(), op.getDescription());
            }
        } else {
            log.info("【危险操作列表】未检测到危险操作");
        }
        log.info("========================================");

        // Step 4: 构建响应
        Map<String, Object> response = new HashMap<>();
        response.put("passed", result.isPassed());
        response.put("riskLevel", result.getRiskLevel());
        response.put("needConfirm", result.isNeedConfirm());
        response.put("message", result.getMessage());
        response.put("dangerousOperations", result.getDangerousOperations());
        response.put("remoteContentLoaded", remoteContentLoaded);
        if (remoteContentLoaded) {
            response.put("scriptPath", scriptPath);
            if (resolvedScriptRemotePath != null) {
                response.put("resolvedScriptPath", resolvedScriptRemotePath);
            }
        }

        // 如果需要确认，生成一次性的确认Token
        if (result.isNeedConfirm()) {
            String httpSessionId = request.getSession().getId();
            String confirmToken = tokenService.generateToken(httpSessionId, scriptContent);
            response.put("confirmToken", confirmToken);
            log.info("【后续流程】发现危险操作，生成确认Token, riskLevel={}", result.getRiskLevel());
        }

        return response;
    }

    /**
     * 根据WebSocket会话ID查找对应的WebSocketSession
     */
    private WebSocketSession findWebSocketSession(String sessionId) {
        WebSocketSession session = WebSshWebSocketHandler.findSession(sessionId);
        if (session == null) {
            log.warn("【会话查找】WebSocket会话不存在: {}", sessionId);
        } else if (!session.isOpen()) {
            log.warn("【会话查找】WebSocket会话已关闭: {}", sessionId);
            session = null;
        } else {
            log.info("【会话查找】成功找到WebSocket会话: {}", sessionId);
        }
        return session;
    }

    /**
     * 验证脚本确认Token并执行脚本
     * 验证通过后返回执行令牌
     *
     * @param scriptConfirmToken 确认Token
     * @param scriptConfirmText  脚本文本（用于Token验证）
     * @param httpRequest        HTTP请求
     * @return 验证结果
     */
    @PostMapping("/confirm-script")
    public Map<String, Object> confirmScript(@RequestParam String scriptConfirmToken,
                                              @RequestParam String scriptConfirmText,
                                              javax.servlet.http.HttpServletRequest httpRequest) {
        String httpSessionId = httpRequest.getSession().getId();

        boolean valid = tokenService.validateAndConsumeToken(httpSessionId, scriptConfirmText, scriptConfirmToken);

        Map<String, Object> result = new HashMap<>();
        if (valid) {
            // Token验证成功，生成脚本执行令牌
            String execToken = tokenService.generateToken(httpSessionId, scriptConfirmText + "_EXEC");
            result.put("success", true);
            result.put("execToken", execToken);
            log.info("脚本确认Token验证成功，允许执行: {}", scriptConfirmText);
        } else {
            result.put("success", false);
            result.put("message", "脚本确认Token无效或已过期，请重新扫描脚本");
            log.warn("脚本确认Token验证失败: {}", scriptConfirmText);
        }
        return result;
    }

    /**
     * 获取脚本扫描规则配置
     *
     * @return 脚本扫描配置
     */
    @GetMapping("/script-config")
    public Map<String, Object> getScriptConfig() {
        Map<String, Object> result = new HashMap<>();
        result.put("enabled", sshSecurityConfig.isScriptScanEnabled());
        result.put("blockCritical", sshSecurityConfig.isBlockCriticalScriptOps());
        result.put("triggers", sshSecurityConfig.getScriptExecTriggers());
        return result;
    }
}
