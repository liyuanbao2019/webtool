package com.gxcj.xjtool.service.impl;

import com.gxcj.xjtool.service.DangerousCommandTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 危险命令确认Token服务实现
 * 使用一次性Token机制，防止攻击者绕过前端直接调用后端API执行危险命令
 */
@Service
public class DangerousCommandTokenServiceImpl implements DangerousCommandTokenService {

    private static final Logger log = LoggerFactory.getLogger(DangerousCommandTokenServiceImpl.class);

    // Token有效期：30秒
    private static final long TOKEN_VALIDITY_MS = 30000;

    // Token存储：key=token, value=TokenInfo
    private final Map<String, TokenInfo> tokenStore = new ConcurrentHashMap<>();

    /**
     * Token信息
     */
    private static class TokenInfo {
        String sessionId;
        String commandHash; // 命令的SHA-256哈希，防止token被用于其他命令
        long createTime;

        TokenInfo(String sessionId, String commandHash) {
            this.sessionId = sessionId;
            this.commandHash = commandHash;
            this.createTime = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - createTime > TOKEN_VALIDITY_MS;
        }
    }

    @Override
    public String generateToken(String sessionId, String command) {
        // 生成随机Token
        String token = UUID.randomUUID().toString();

        // 计算命令的哈希值
        String commandHash = hashCommand(command);

        // 存储Token信息
        tokenStore.put(token, new TokenInfo(sessionId, commandHash));

        log.info("生成危险命令确认Token: sessionId={}, token={}", sessionId, token.substring(0, 8) + "...");

        return token;
    }

    @Override
    public boolean validateAndConsumeToken(String sessionId, String command, String token) {
        if (token == null || token.isEmpty()) {
            log.warn("危险命令验证失败: Token为空, sessionId={}", sessionId);
            return false;
        }

        // 获取Token信息
        TokenInfo tokenInfo = tokenStore.remove(token); // 一次性Token，验证后立即删除

        if (tokenInfo == null) {
            log.warn("危险命令验证失败: Token不存在或已被使用, sessionId={}, token={}",
                    sessionId, token.substring(0, 8) + "...");
            return false;
        }

        // 检查Token是否过期
        if (tokenInfo.isExpired()) {
            log.warn("危险命令验证失败: Token已过期, sessionId={}, age={}ms",
                    sessionId, System.currentTimeMillis() - tokenInfo.createTime);
            return false;
        }

        // 检查会话ID是否匹配
        if (!tokenInfo.sessionId.equals(sessionId)) {
            log.warn("危险命令验证失败: 会话ID不匹配, expected={}, actual={}",
                    tokenInfo.sessionId, sessionId);
            return false;
        }

        // 检查命令哈希是否匹配（防止token被用于执行其他命令）
        String commandHash = hashCommand(command);
        if (!tokenInfo.commandHash.equals(commandHash)) {
            log.warn("危险命令验证失败: 命令哈希不匹配, sessionId={}", sessionId);
            return false;
        }

        log.info("危险命令验证成功: sessionId={}, token={}", sessionId, token.substring(0, 8) + "...");
        return true;
    }

    /**
     * 计算命令的SHA-256哈希值（统一规范化：去除首尾空白、统一换行符，防止前后端格式差异导致哈希不匹配）
     */
    private String hashCommand(String command) {
        if (command == null) {
            return "";
        }
        // 规范化：trim 去除首尾空白，将 \r\n 和 \r 统一为 \n
        String normalized = command.trim().replaceAll("\\r\\n|\\r", "\n");
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1)
                    hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            log.error("计算命令哈希失败", e);
            return normalized; // 降级方案：使用规范化后的文本
        }
    }

    /**
     * 定时清理过期Token（每分钟执行一次）
     */
    @Scheduled(fixedRate = 60000)
    @Override
    public void cleanExpiredTokens() {
        int cleanedCount = 0;
        for (Map.Entry<String, TokenInfo> entry : tokenStore.entrySet()) {
            if (entry.getValue().isExpired()) {
                tokenStore.remove(entry.getKey());
                cleanedCount++;
            }
        }
        if (cleanedCount > 0) {
            log.info("清理过期Token: {} 个", cleanedCount);
        }
    }
}
