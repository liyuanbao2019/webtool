package com.gxcj.xjtool.service.impl;

import com.gxcj.xjtool.config.SecurityConfig;
import com.gxcj.xjtool.service.CsrfTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpSession;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CSRF Token服务实现类
 * 使用Session存储Token，支持Token过期
 */
@Service
public class CsrfTokenServiceImpl implements CsrfTokenService {

    private static final Logger log = LoggerFactory.getLogger(CsrfTokenServiceImpl.class);

    private static final String SESSION_TOKEN_KEY = "CSRF_TOKEN";
    private static final String SESSION_TOKEN_TIME_KEY = "CSRF_TOKEN_TIME";

    @Autowired
    private SecurityConfig securityConfig;

    private final SecureRandom secureRandom = new SecureRandom();

    // Token过期时间缓存（Session ID -> 过期时间戳）
    private final Map<String, Long> tokenExpiryMap = new ConcurrentHashMap<>();

    @Override
    public String generateToken() {
        HttpSession session = getCurrentSession();
        if (session == null) {
            log.warn("无法获取当前Session，Token生成失败");
            return null;
        }

        // 生成随机Token
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        // 存储到Session
        session.setAttribute(SESSION_TOKEN_KEY, token);
        long expiryTime = System.currentTimeMillis() + (securityConfig.getCsrf().getTokenTimeout() * 1000L);
        session.setAttribute(SESSION_TOKEN_TIME_KEY, expiryTime);
        tokenExpiryMap.put(session.getId(), expiryTime);

        log.debug("生成CSRF Token: {} (Session: {})", token.substring(0, 8) + "...", session.getId());
        return token;
    }

    @Override
    public boolean validateToken(String token) {
        if (token == null || token.isEmpty()) {
            log.warn("CSRF Token为空");
            return false;
        }

        HttpSession session = getCurrentSession();
        if (session == null) {
            log.warn("无法获取当前Session，Token验证失败");
            return false;
        }

        // 从Session获取存储的Token
        String storedToken = (String) session.getAttribute(SESSION_TOKEN_KEY);
        Long expiryTime = (Long) session.getAttribute(SESSION_TOKEN_TIME_KEY);

        // 验证Token存在
        if (storedToken == null) {
            log.warn("Session中未找到CSRF Token");
            return false;
        }

        // 验证Token是否过期
        if (expiryTime == null || System.currentTimeMillis() > expiryTime) {
            log.warn("CSRF Token已过期");
            invalidateToken(storedToken);
            return false;
        }

        // 验证Token匹配
        boolean valid = storedToken.equals(token);
        if (!valid) {
            log.warn("CSRF Token不匹配");
        }

        return valid;
    }

    @Override
    public void invalidateToken(String token) {
        HttpSession session = getCurrentSession();
        if (session != null) {
            session.removeAttribute(SESSION_TOKEN_KEY);
            session.removeAttribute(SESSION_TOKEN_TIME_KEY);
            tokenExpiryMap.remove(session.getId());
            log.debug("CSRF Token已失效 (Session: {})", session.getId());
        }
    }

    /**
     * 获取当前请求的Session
     */
    private HttpSession getCurrentSession() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        return attributes.getRequest().getSession(false);
    }
}
