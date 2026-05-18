package com.gxcj.xjtool.service;

/**
 * CSRF Token服务接口
 * 用于生成和验证CSRF令牌，防止跨站请求伪造攻击
 */
public interface CsrfTokenService {

    /**
     * 为当前会话生成CSRF Token
     * 
     * @return CSRF Token
     */
    String generateToken();

    /**
     * 验证CSRF Token
     * 
     * @param token 待验证的Token
     * @return 验证是否通过
     */
    boolean validateToken(String token);

    /**
     * 使Token失效
     * 
     * @param token 待失效的Token
     */
    void invalidateToken(String token);
}
