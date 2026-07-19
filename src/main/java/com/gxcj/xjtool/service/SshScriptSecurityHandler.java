package com.gxcj.xjtool.service;

import com.gxcj.xjtool.config.SshSecurityConfig;
import com.gxcj.xjtool.dto.ScriptScanResult;
import com.gxcj.xjtool.model.WebSshData;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Applies script-content scanning and confirmation rules to terminal commands. */
@Slf4j
@Component
public class SshScriptSecurityHandler {

    @Autowired(required = false)
    private DangerousCommandTokenService tokenService;

    @Autowired
    private SshSecurityConfig securityConfig;

    @Autowired(required = false)
    private ScriptSecurityService scriptSecurityService;

    public static class Decision {
        boolean blocked;       // 是否阻断
        boolean needConfirm;   // 是否需要确认
        String message;        // 提示消息
        String riskLevel;      // 危险等级

        public boolean isBlocked() {
            return blocked;
        }

        static Decision allow() {
            Decision r = new Decision();
            r.blocked = false;
            r.needConfirm = false;
            return r;
        }

        static Decision block(String msg, String level) {
            Decision r = new Decision();
            r.blocked = true;
            r.message = msg;
            r.riskLevel = level;
            return r;
        }

        static Decision confirm(String msg, String level) {
            Decision r = new Decision();
            r.blocked = true;
            r.needConfirm = true;
            r.message = msg;
            r.riskLevel = level;
            return r;
        }
    }

    /**
     * 检测并处理脚本执行命令
     * 当用户执行 sh/bash/./ 等脚本执行命令时，拦截并扫描脚本内容
     *
     * @return null表示不拦截直接放行，Decision表示拦截结果
     */
    public Decision check(WebSocketSession session, WebSshData webSshData,
                          String commandText, Charset charset, Consumer<byte[]> terminalSender) {
        // 脚本执行触发命令检测（sh/bash/zsh/./source + 脚本）
        if (!isScriptExecCommand(commandText)) {
            return null; // 非脚本执行命令，不拦截
        }

        // 脚本扫描服务未启用时放行
        if (scriptSecurityService == null || securityConfig == null || !securityConfig.isScriptScanEnabled()) {
            return null;
        }

        log.info("检测到脚本执行命令: {}", commandText);

        // 如果前端已携带了确认Token，说明用户已完成确认流程
        String confirmToken = webSshData.getScriptConfirmToken();
        String confirmText = webSshData.getScriptConfirmText();
        if (confirmToken != null && !confirmToken.isEmpty() && confirmText != null) {
            // 验证确认Token
            String httpSessionId = (String) session.getAttributes().get("HTTP.SESSION.ID");
            String verifySessionId = (httpSessionId != null && !httpSessionId.isEmpty()) ? httpSessionId : session.getId();

            if (tokenService != null && tokenService.validateAndConsumeToken(verifySessionId, confirmText, confirmToken)) {
                log.info("脚本执行确认Token验证通过: {}", commandText);
                return Decision.allow();
            } else {
                // Token验证失败，发送拒绝消息
                String rejectMsg = "\r\n\033[1;31m⚠️  脚本执行被拒绝：确认Token无效或已过期，请重新确认\033[0m\r\n";
                terminalSender.accept(rejectMsg.getBytes(charset));
                return Decision.block("脚本确认Token无效", "critical");
            }
        }

        // 执行脚本内容扫描
        com.gxcj.xjtool.dto.ScriptScanResult scanResult = scriptSecurityService.scanCommand(commandText);

        if (scanResult.isPassed()) {
            // 脚本安全，直接放行
            return Decision.allow();
        }

        String riskLevel = scanResult.getRiskLevel();
        String message = scanResult.getMessage();

        // 根据配置决定阻断行为（blockCriticalScriptOps=true 时，high 等级也阻断）
        if (securityConfig.isBlockCriticalScriptOps() && "high".equals(riskLevel)) {
            String blockMsg = String.format(
                    "\r\n\033[1;31m========================================\033[0m\r\n" +
                    "\033[1;31m⚠️  高危操作！脚本执行已被系统阻断！\033[0m\r\n" +
                    "\033[1;31m========================================\033[0m\r\n" +
                    "\r\n检测到危险等级: \033[1;33m%s\033[0m\r\n" +
                    "\r\n%s\r\n\r\n" +
                    "\033[1;31m该操作风险极高，已被系统阻断执行！\033[0m\r\n" +
                    "\r\n如确需执行，请联系管理员授权。\r\n",
                    com.gxcj.xjtool.service.ScriptSecurityService.getRiskLevelLabel(riskLevel),
                    formatDangerousOpsForDisplay(scanResult.getDangerousOperations()));

            terminalSender.accept(blockMsg.getBytes(charset));
            log.warn("高危脚本执行被阻断（blockCriticalScriptOps=true）: sessionId={}, command={}",
                    session.getId(), commandText);
            return Decision.block(message, riskLevel);
        }

        // high/medium：需要用户确认
        String confirmMsg = String.format(
                "\r\n\033[1;33m========================================\033[0m\r\n" +
                "\033[1;33m⚠️  检测到脚本包含危险操作！\033[0m\r\n" +
                "\033[1;33m========================================\033[0m\r\n" +
                "\r\n危险等级: \033[1;33m%s\033[0m\r\n" +
                "\r\n%s\r\n" +
                "\r\n如确认脚本安全，请在Web界面点击\"确认执行\"按钮。\r\n" +
                "\r\n脚本命令: \033[1;36m%s\033[0m\r\n",
                com.gxcj.xjtool.service.ScriptSecurityService.getRiskLevelLabel(riskLevel),
                message,
                commandText.length() > 200 ? commandText.substring(0, 200) + "..." : commandText);

        terminalSender.accept(confirmMsg.getBytes(charset));

        // 发送特殊消息给前端，触发确认弹窗（带上脚本路径供前端显示）
        String scriptPath = webSshData.getScriptPath();
        sendScriptConfirmRequest(session, scanResult, commandText, scriptPath);

        log.warn("危险脚本执行被阻断，需确认: sessionId={}, riskLevel={}, operations={}",
                session.getId(), riskLevel, scanResult.getDangerousOperations().size());

        return Decision.confirm(message, riskLevel);
    }

    /**
     * 格式化危险操作列表为终端显示格式
     */
    private String formatDangerousOpsForDisplay(List<com.gxcj.xjtool.dto.ScriptScanResult.DangerousOperation> operations) {
        if (operations == null || operations.isEmpty()) {
            return "未知危险操作";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(operations.size(), 5); i++) {
            com.gxcj.xjtool.dto.ScriptScanResult.DangerousOperation op = operations.get(i);
            sb.append(String.format("  %d. [%s] %s\r\n    操作: %s\r\n    建议: %s\r\n",
                    i + 1,
                    op.getRiskLevel().toUpperCase(),
                    op.getDescription(),
                    op.getOriginalText(),
                    op.getSuggestion()));
        }
        if (operations.size() > 5) {
            sb.append("  ... 还有 ").append(operations.size() - 5).append(" 个危险操作\r\n");
        }
        return sb.toString();
    }

    /**
     * 向前端发送脚本确认请求
     * 发送特殊格式的JSON消息，前端收到后弹出确认对话框
     */
    private void sendScriptConfirmRequest(WebSocketSession session,
                                          com.gxcj.xjtool.dto.ScriptScanResult scanResult,
                                          String commandText, String scriptPath) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

            Map<String, Object> confirmRequest = new java.util.HashMap<>();
            confirmRequest.put("type", "script_confirm_required");
            confirmRequest.put("riskLevel", scanResult.getRiskLevel());
            confirmRequest.put("riskLevelLabel", com.gxcj.xjtool.service.ScriptSecurityService.getRiskLevelLabel(scanResult.getRiskLevel()));
            confirmRequest.put("riskLevelColor", com.gxcj.xjtool.service.ScriptSecurityService.getRiskLevelColor(scanResult.getRiskLevel()));
            confirmRequest.put("message", scanResult.getMessage());
            confirmRequest.put("commandText", commandText.length() > 200 ? commandText.substring(0, 200) + "..." : commandText);
            confirmRequest.put("operations", scanResult.getDangerousOperations());
            confirmRequest.put("needConfirm", scanResult.isNeedConfirm());
            if (scriptPath != null && !scriptPath.isEmpty()) {
                confirmRequest.put("scriptPath", scriptPath);
            }

            String json = mapper.writeValueAsString(confirmRequest);
            session.sendMessage(new TextMessage(json));

            log.debug("已向前端发送脚本确认请求: sessionId={}", session.getId());
        } catch (Exception e) {
            log.error("发送脚本确认请求失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 判断是否是脚本执行命令
     */
    private boolean isScriptExecCommand(String command) {
        if (command == null || command.trim().isEmpty()) {
            return false;
        }
        String trimmed = command.trim();
        // 检测常见的脚本执行格式
        // sh -c "..."  bash -c "..."  ./script.sh  bash script.sh  source script.sh
        return trimmed.matches("(?i)^(sh|bash|zsh|dash|ksh|source|\\.)\\s+.*") ||
               trimmed.matches("(?i)^\\./.*") ||
               trimmed.matches("(?i)^bash\\s+.*") ||
               trimmed.matches("(?i)^sh\\s+.*") ||
               trimmed.matches("(?i)^zsh\\s+.*") ||
               trimmed.matches("(?i)^source\\s+.*");
    }
}
