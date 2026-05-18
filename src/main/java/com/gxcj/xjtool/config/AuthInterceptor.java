package com.gxcj.xjtool.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

/**
 * 登录认证拦截器
 * 拦截未登录的请求，重定向到登录页面
 * 审计看板需要管理员权限（由 security.sql-check.admin-users 配置控制）
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    @Autowired
    private AuthConfig authConfig;

    @Autowired
    private SecurityConfig securityConfig;

    /**
     * Session中存储登录状态的key
     */
    public static final String SESSION_USER_KEY = "LOGIN_USER";

    /**
     * 需要管理员权限的路径前缀
     */
    private static final String ADMIN_PATH_PREFIX = "/audit-dashboard";
    private static final String ADMIN_API_PREFIX = "/api/audit";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // 如果未启用认证，直接放行
        if (!authConfig.isEnabled()) {
            return true;
        }

        // 获取请求路径
        String uri = request.getRequestURI();

        // 放行静态资源和登录相关路径
        if (isExcludedPath(uri)) {
            return true;
        }

        // 检查是否已登录
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute(SESSION_USER_KEY) == null) {
            // 未登录，重定向到登录页面
            response.sendRedirect("/login");
            return false;
        }

        // 审计看板需要管理员权限（使用 security.sql-check.admin-users 配置）
        if (uri.startsWith(ADMIN_PATH_PREFIX) || uri.startsWith(ADMIN_API_PREFIX)) {
            String username = (String) session.getAttribute(SESSION_USER_KEY);
            if (!securityConfig.getSqlCheck().getAdminUsers().contains(username)) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"success\":false,\"message\":\"无权限访问：需要管理员权限\"}");
                return false;
            }
        }

        return true;
    }

    /**
     * 判断是否为排除的路径（不需要登录验证）
     */
    private boolean isExcludedPath(String uri) {
        return uri.equals("/login")
                || uri.equals("/api/auth/login")
                || uri.equals("/api/auth/captcha") // 验证码接口
                || uri.startsWith("/api/test/") // API 调测工具代理接口
                || uri.startsWith("/css/")
                || uri.startsWith("/js/")
                || uri.startsWith("/fonts/")
                || uri.startsWith("/images/");
    }
}
