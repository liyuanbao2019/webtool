package com.li.jc.webtool.config;

import com.li.jc.webtool.service.SshService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import javax.servlet.http.HttpSession;
import java.util.Map;

/**
 * WebSocket握手拦截器
 * 用于验证WebSocket连接请求的登录状态和连接数限制
 */
@Component
public class WebSocketAuthInterceptor implements HandshakeInterceptor {

    @Autowired
    private AuthConfig authConfig;

    @Autowired
    private SecurityConfig securityConfig;

    @Autowired
    private SshService sshService;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Map<String, Object> attributes) throws Exception {
        // 1. 检查来源（防止CSWSH）
        if (securityConfig.getOriginCheck().isEnabled()) {
            if (!checkOrigin(request)) {
                response.setStatusCode(HttpStatus.FORBIDDEN);
                return false;
            }
        }

        // 检查HTTP会话中的登录状态
        if (request instanceof ServletServerHttpRequest) {
            ServletServerHttpRequest servletRequest = (ServletServerHttpRequest) request;
            HttpSession session = servletRequest.getServletRequest().getSession(true); // 强制获取/创建 session，确保 ID 一定存在

            if (session != null) {
                // 存储 HTTP Session ID，供后续验证危险命令 Token 使用
                // 必须在这里存储，保证与 generate-token API 调用使用的 session ID 一致
                attributes.put("HTTP.SESSION.ID", session.getId());

                if (session.getAttribute(AuthInterceptor.SESSION_USER_KEY) != null) {
                    // 已登录，将用户名存储到WebSocket attributes中（供SSH审计使用）
                    String username = (String) session.getAttribute(AuthInterceptor.SESSION_USER_KEY);
                    attributes.put("username", username);
                }
            }

            // 如果未启用认证，存储完毕后直接放行并检查连接数限制
            if (!authConfig.isEnabled()) {
                return checkConnectionLimit(response);
            }

            // 启用认证情况下，必须登录才能放行
            if (session != null && session.getAttribute(AuthInterceptor.SESSION_USER_KEY) != null) {
                return checkConnectionLimit(response);
            }
        } else if (!authConfig.isEnabled()) {
            return checkConnectionLimit(response);
        }

        // 未登录或非常规请求，拒绝WebSocket连接
        return false;
    }

    /**
     * 检查连接数限制
     * 
     * @param response HTTP响应对象，用于设置错误信息
     * @return true=允许连接，false=拒绝连接
     */
    private boolean checkConnectionLimit(ServerHttpResponse response) {
        int currentConnections = sshService.getCurrentConnectionCount();
        int maxConnections = sshService.getMaxConnections();

        if (currentConnections >= maxConnections) {
            // 连接数已满，拒绝连接
            response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            response.getHeaders().add("X-Error-Message", "当前服务器连接数已达上限，请关闭部分连接后重试");
            return false;
        }

        return true;
    }

    /**
     * 检查请求来源
     */
    private boolean checkOrigin(ServerHttpRequest request) {
        // 获取Origin头
        String origin = request.getHeaders().getOrigin();

        // 如果没有Origin头，尝试检查Referer
        if (origin == null) {
            java.util.List<String> referers = request.getHeaders().get("Referer");
            if (referers != null && !referers.isEmpty()) {
                origin = referers.get(0);
            }
        }

        // 如果都没有，为了兼容性暂时放行（或者是直接通过非浏览器客户端连接）
        // 严格模式下应该拦截
        if (origin == null) {
            return true;
        }

        // 检查是否在允许列表中
        for (String allowed : securityConfig.getOriginCheck().getAllowedOrigins()) {
            if (origin.startsWith(allowed)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Exception exception) {
        // 握手后处理，暂无需实现
    }
}
