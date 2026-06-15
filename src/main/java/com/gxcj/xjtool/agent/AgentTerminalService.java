package com.gxcj.xjtool.agent;

import com.gxcj.xjtool.config.ServerConfig;
import com.gxcj.xjtool.model.WebSshData;
import com.gxcj.xjtool.config.SshSecurityConfig;
import com.gxcj.xjtool.service.DangerousCommandTokenService;
import com.gxcj.xjtool.service.SshAuditService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class AgentTerminalService {
    private final Map<String, AgentTerminalSession> sessions = new ConcurrentHashMap<>();

    @Autowired
    private AgentTerminalClient agentTerminalClient;

    @Autowired
    private ServerConfig serverConfig;

    @Autowired
    private SshSecurityConfig securityConfig;

    @Autowired(required = false)
    private DangerousCommandTokenService tokenService;

    @Autowired(required = false)
    private SshAuditService sshAuditService;

    public boolean isAgentMode(WebSshData data) {
        return data != null
                && data.getConnectionMode() != null
                && "agent".equalsIgnoreCase(data.getConnectionMode().trim());
    }

    public boolean hasSession(WebSocketSession browserSession) {
        return browserSession != null && sessions.containsKey(browserSession.getId());
    }

    public void initConnection(WebSocketSession browserSession, WebSshData data) {
        try {
            applyServerSideAgentConfig(data);
            Charset charset = resolveCharset(data.getCharset());
            AgentTerminalSession agentSession = agentTerminalClient.open(data, browserSession.getId(),
                    new AgentTerminalCallback() {
                        @Override
                        public void onOutput(byte[] output) {
                            sendToBrowser(browserSession, new String(output, charset));
                        }

                        @Override
                        public void onError(String message) {
                            sendToBrowser(browserSession, "\r\n[Agent error] "
                                    + (message == null ? "unknown error" : message) + "\r\n");
                        }

                        @Override
                        public void onClosed(String reason) {
                            sessions.remove(browserSession.getId());
                            sendToBrowser(browserSession, "\r\n[Agent closed] "
                                    + (reason == null ? "" : reason) + "\r\n");
                        }
                    });
            sessions.put(browserSession.getId(), agentSession);
            sendToBrowser(browserSession, String.format("\u001b[0;32m会话ID: %s\u001b[0m\r\n", browserSession.getId()));
            log.info("Agent terminal connected: browserSession={}, agentSession={}, agentBaseUrl={}",
                    browserSession.getId(), agentSession.getLocalSessionId(), data.getAgentBaseUrl());
        } catch (Exception e) {
            log.error("Agent terminal connection failed: browserSession={}", browserSession.getId(), e);
            String errorMessage = e.getMessage();
            if (errorMessage != null) {
                // 对错误信息中的URL进行脱敏处理，避免暴露敏感IP和端口
                errorMessage = errorMessage.replaceAll("(?i)(ws|wss|http|https)://[^\\s\\]]+", "$1://***");
            }
            sendToBrowser(browserSession, "\r\nAgent连接失败: " + errorMessage + "\r\n");
        }
    }

    private void applyServerSideAgentConfig(WebSshData data) {
        if (data == null || serverConfig == null || serverConfig.getAgent() == null) {
            return;
        }
        ServerConfig.AgentConfig agent = serverConfig.getAgent();
        int port = agent.getPort() > 0 ? agent.getPort() : 18080;
        data.setConnectionMode("agent");
        data.setAgentBaseUrl("http://" + data.getHost() + ":" + port);
        data.setAgentId(data.getHost());
        data.setAgentToken(agent.getToken());
    }

    public void recvHandle(WebSocketSession browserSession, WebSshData data) {
        AgentTerminalSession agentSession = sessions.get(browserSession.getId());
        if (agentSession == null || !agentSession.isOpen()) {
            sendToBrowser(browserSession, "\r\nAgent会话不存在或已关闭\r\n");
            return;
        }
        try {
            if (!isCommandAllowed(browserSession, data)) {
                return;
            }
            agentSession.sendInput(data.getCommand());
            auditCommand(browserSession, data);
        } catch (Exception e) {
            log.error("Failed to send input to agent: browserSession={}", browserSession.getId(), e);
            sendToBrowser(browserSession, "\r\n发送Agent输入失败: " + e.getMessage() + "\r\n");
        }
    }

    public void resizeTerminal(WebSocketSession browserSession, Integer cols, Integer rows) {
        AgentTerminalSession agentSession = sessions.get(browserSession.getId());
        if (agentSession == null || !agentSession.isOpen() || cols == null || rows == null) {
            return;
        }
        try {
            agentSession.resize(cols, rows);
        } catch (Exception e) {
            log.warn("Failed to resize agent terminal: browserSession={}", browserSession.getId(), e);
        }
    }

    public void keepAlive(WebSocketSession browserSession) {
        AgentTerminalSession agentSession = sessions.get(browserSession.getId());
        if (agentSession == null || !agentSession.isOpen()) {
            return;
        }
        try {
            agentSession.ping();
        } catch (Exception e) {
            log.debug("Agent ping failed: browserSession={}, error={}", browserSession.getId(), e.getMessage());
        }
    }

    public void close(WebSocketSession browserSession) {
        if (browserSession == null) {
            return;
        }
        AgentTerminalSession agentSession = sessions.remove(browserSession.getId());
        if (agentSession == null) {
            return;
        }
        try {
            agentSession.close();
        } catch (Exception e) {
            log.debug("Agent close failed: browserSession={}, error={}", browserSession.getId(), e.getMessage());
        }
    }

    private void sendToBrowser(WebSocketSession session, String text) {
        if (session == null || !session.isOpen() || text == null || text.isEmpty()) {
            return;
        }
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(text));
            }
        } catch (Exception e) {
            log.debug("Failed to send agent output to browser: {}", e.getMessage());
        }
    }

    private Charset resolveCharset(String charsetName) {
        if (charsetName == null || charsetName.trim().isEmpty()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(charsetName.trim());
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }

    private boolean isCommandAllowed(WebSocketSession browserSession, WebSshData data) {
        String command = data.getCommand();
        if (command == null || !(command.contains("\n") || command.contains("\r"))) {
            return true;
        }
        String commandText = cleanCommand(data.getCommandText());
        if (commandText.isEmpty()) {
            commandText = cleanCommand(command);
        }
        if (!isDangerousCommand(commandText)) {
            return true;
        }

        String httpSessionId = (String) browserSession.getAttributes().get("HTTP.SESSION.ID");
        String verifySessionId = (httpSessionId != null && !httpSessionId.isEmpty())
                ? httpSessionId
                : browserSession.getId();
        String token = data.getDangerousCommandToken();
        if (tokenService != null && tokenService.validateAndConsumeToken(verifySessionId, commandText, token)) {
            return true;
        }

        log.warn("Agent dangerous command rejected: sessionId={}, command={}, hasToken={}",
                verifySessionId, commandText, token != null);
        sendToBrowser(browserSession, "\r\n危险命令被系统拒绝：未经授权的命令执行尝试\r\n");
        return false;
    }

    private boolean isDangerousCommand(String command) {
        if (command == null || command.trim().isEmpty()
                || securityConfig == null
                || !securityConfig.isDangerousCommandConfirm()) {
            return false;
        }
        String cmd = command.trim().toLowerCase();
        for (String dangerous : securityConfig.getDangerousCommands()) {
            String normalized = dangerous.toLowerCase();
            if (cmd.equals(normalized) || cmd.startsWith(normalized + " ")) {
                return true;
            }
        }
        return false;
    }

    private void auditCommand(WebSocketSession browserSession, WebSshData data) {
        if (sshAuditService == null) {
            return;
        }
        String command = data.getCommand();
        if (command == null || !(command.contains("\n") || command.contains("\r"))) {
            return;
        }
        String commandText = cleanCommand(data.getCommandText());
        if (commandText.isEmpty()) {
            commandText = cleanCommand(command);
        }
        if (commandText.isEmpty()) {
            return;
        }
        String username = (String) browserSession.getAttributes().get("username");
        if (username == null) {
            username = "unknown";
        }
        sshAuditService.logCommand(username, browserSession.getId(), data.getHost(), commandText);
    }

    private String cleanCommand(String command) {
        if (command == null) {
            return "";
        }
        String cleaned = command.replaceAll("\\x1B\\[[0-9;]*[a-zA-Z]", "");
        cleaned = cleaned.replaceAll("\\[[A-Z]", "");
        cleaned = cleaned.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "");
        return cleaned.trim();
    }
}
