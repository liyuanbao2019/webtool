package com.gxcj.xjtool.service;

/**
 * 危险命令确认Token服务
 * 提供危险命令执行的双重验证机制
 */
public interface DangerousCommandTokenService {

    /**
     * 生成危险命令确认Token
     * 
     * @param sessionId WebSocket会话ID
     * @param command   待执行的命令
     * @return 一次性确认Token
     */
    String generateToken(String sessionId, String command);

    /**
     * 验证并消费Token
     * 
     * @param sessionId WebSocket会话ID
     * @param command   待执行的命令
     * @param token     确认Token
     * @return 是否验证通过
     */
    boolean validateAndConsumeToken(String sessionId, String command, String token);

    /**
     * 清理过期Token
     */
    void cleanExpiredTokens();
}
