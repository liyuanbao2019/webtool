package com.li.jc.webtool.config;

import com.li.jc.webtool.service.RateLimitService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

/**
 * 请求限流拦截器
 * 基于用户和IP的双重限流
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    @Autowired
    private SecurityConfig securityConfig;

    @Autowired
    private RateLimitService rateLimitService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {

        // 如果未启用限流，直接放行
        if (!securityConfig.getRateLimit().isEnabled()) {
            return true;
        }

        // 只限流SQL执行和执行计划请求
        String uri = request.getRequestURI();
        if (!isSqlExecutionRequest(uri)) {
            return true;
        }

        // 获取用户名
        HttpSession session = request.getSession(false);
        String username = session != null ? (String) session.getAttribute(AuthInterceptor.SESSION_USER_KEY) : null;

        // 获取IP
        String ip = getClientIp(request);

        // 用户限流检查
        if (username != null && !rateLimitService.allowUserRequest(username)) {
            log.warn("用户 {} 请求超限 [URI: {}, IP: {}]", username, uri, ip);
            response.setStatus(429); // Too Many Requests
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"success\":false,\"message\":\"请求过于频繁，请稍后再试\"}");
            return false;
        }

        // IP限流检查
        if (ip != null && !rateLimitService.allowIpRequest(ip)) {
            log.warn("IP {} 请求超限 [URI: {}, User: {}]", ip, uri, username);
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"success\":false,\"message\":\"请求过于频繁，请稍后再试\"}");
            return false;
        }

        return true;
    }

    /**
     * 是否为SQL执行请求
     */
    private boolean isSqlExecutionRequest(String uri) {
        return DatabaseApiPathPolicy.isRateLimited(uri);
    }

    /**
     * 获取客户端真实IP
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // 如果是多个IP（通过代理），取第一个
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}
