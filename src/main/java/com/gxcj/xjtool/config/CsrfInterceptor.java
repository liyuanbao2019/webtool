package com.gxcj.xjtool.config;

import com.gxcj.xjtool.service.CsrfTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.List;

/**
 * CSRF防护拦截器
 * 验证POST请求携带的CSRF Token
 */
@Component
public class CsrfInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(CsrfInterceptor.class);

    private static final String CSRF_TOKEN_HEADER = "X-CSRF-Token";
    private static final String CSRF_TOKEN_PARAM = "csrfToken";

    // 白名单路径（不需要CSRF验证）
    private static final List<String> WHITELIST_PATHS = Arrays.asList(
            "/api/auth/login",
            "/api/auth/logout",
            "/api/auth/status",
            "/api/csrf/token",
            "/api/test/proxy",
            "/api/test/pressure/start",
            "/api/test/pressure/stop",
            "/api/test/pressure/status",
            "/api/test/pressure/export/pdf",
            "/api/test/pressure/export/excel");

    @Autowired
    private SecurityConfig securityConfig;

    @Autowired
    private CsrfTokenService csrfTokenService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {

        // 如果未启用CSRF防护，直接放行
        if (!securityConfig.getCsrf().isEnabled()) {
            return true;
        }

        // 只验证POST、PUT、DELETE、PATCH请求
        String method = request.getMethod();
        if (!isModifyingMethod(method)) {
            return true;
        }

        // 白名单路径放行
        String uri = request.getRequestURI();
        if (isWhitelistPath(uri)) {
            return true;
        }

        // 获取并验证Token
        String token = request.getHeader(CSRF_TOKEN_HEADER);
        if (token == null || token.trim().isEmpty()) {
            token = request.getParameter(CSRF_TOKEN_PARAM);
        }
        if (token == null) {
            log.warn("CSRF验证失败：缺少Token [URI: {}, Method: {}]", uri, method);
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"success\":false,\"message\":\"CSRF验证失败：缺少Token\"}");
            return false;
        }

        if (!csrfTokenService.validateToken(token)) {
            log.warn("CSRF验证失败：Token无效 [URI: {}, Method: {}]", uri, method);
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"success\":false,\"message\":\"CSRF验证失败：Token无效或已过期\"}");
            return false;
        }

        return true;
    }

    /**
     * 是否为修改操作的HTTP方法
     */
    private boolean isModifyingMethod(String method) {
        return "POST".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method)
                || "DELETE".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method);
    }

    /**
     * 是否为白名单路径
     */
    private boolean isWhitelistPath(String uri) {
        return WHITELIST_PATHS.stream().anyMatch(uri::equals);
    }
}
