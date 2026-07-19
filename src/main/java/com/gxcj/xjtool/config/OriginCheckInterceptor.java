package com.gxcj.xjtool.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 来源验证拦截器
 * 用于防止 CSRF 和非法跨域请求
 */
@Component
public class OriginCheckInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(OriginCheckInterceptor.class);

    @Autowired
    private SecurityConfig securityConfig;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {

        boolean forceCheck = DatabaseApiPathPolicy.requiresOriginCheck(request.getRequestURI());
        if (!forceCheck && !securityConfig.getOriginCheck().isEnabled()) {
            return true;
        }

        String origin = request.getHeader("Origin");
        String referer = request.getHeader("Referer");

        // 如果没有 Origin 和 Referer，通常是直接访问或服务器间调用，暂时放行
        // 严格模式下可能需要限制，但为了兼容性先允许
        if (origin == null && referer == null) {
            if (forceCheck && !"XMLHttpRequest".equalsIgnoreCase(request.getHeader("X-Requested-With"))) {
                log.warn("拒绝缺少来源头的敏感请求: URI={}", request.getRequestURI());
                response.setStatus(403);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"success\":false,\"message\":\"敏感操作缺少合法请求来源\"}");
                return false;
            }
            return true;
        }

        String source = origin != null ? origin : referer;

        // 简化处理：校验 source 是否以允许的列表中的某项开头
        // 这样可以兼容 Referer 包含路径的情况
        boolean isAllowed = false;
        for (String allowed : securityConfig.getOriginCheck().getAllowedOrigins()) {
            if (source.startsWith(allowed)) {
                isAllowed = true;
                break;
            }
        }
        if (!isAllowed && forceCheck) {
            String requestOrigin = request.getScheme() + "://" + request.getServerName()
                    + (isDefaultPort(request) ? "" : ":" + request.getServerPort());
            isAllowed = source.startsWith(requestOrigin);
        }

        if (!isAllowed) {
            log.warn("拒绝非法来源请求: Origin={}, Referer={}, URI={}", origin, referer, request.getRequestURI());
            response.setStatus(403);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"success\":false,\"message\":\"非法请求来源\"}");
            return false;
        }

        return true;
    }

    private boolean isDefaultPort(HttpServletRequest request) {
        int port = request.getServerPort();
        return ("http".equalsIgnoreCase(request.getScheme()) && port == 80)
                || ("https".equalsIgnoreCase(request.getScheme()) && port == 443);
    }
}
