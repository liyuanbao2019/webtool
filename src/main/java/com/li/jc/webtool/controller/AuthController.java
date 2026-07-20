package com.li.jc.webtool.controller;

import com.li.jc.webtool.config.AuthConfig;
import com.li.jc.webtool.config.AuthInterceptor;
import com.li.jc.webtool.config.SecurityConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

/**
 * 认证控制器
 * 处理登录、登出等认证相关请求
 * 支持多用户登录
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private AuthConfig authConfig;

    @Autowired
    private SecurityConfig securityConfig;

    /**
     * 登录接口（支持多用户）
     * 
     * @param params  包含username和password的请求体
     * @param session HTTP会话
     * @param request HTTP请求
     * @return 登录结果
     */
    @Autowired
    private com.li.jc.webtool.service.LoginAttemptService loginAttemptService;

    /**
     * 获取验证码
     */
    @GetMapping("/captcha")
    public void getCaptcha(javax.servlet.http.HttpServletResponse response, HttpSession session)
            throws java.io.IOException {
        response.setContentType("image/jpeg");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Cache-Control", "no-cache");
        response.setDateHeader("Expires", 0);

        String code = com.li.jc.webtool.util.CaptchaUtil.generateCode(4);
        session.setAttribute("CAPTCHA_CODE", code);

        java.awt.image.BufferedImage image = com.li.jc.webtool.util.CaptchaUtil.generateImage(code, 100, 38);
        javax.imageio.ImageIO.write(image, "JPEG", response.getOutputStream());
    }

    /**
     * 登录接口（支持多用户 + 安全增强）
     * 
     * @param params  包含username, password, captcha的请求体
     * @param session HTTP会话
     * @param request HTTP请求
     * @return 登录结果
     */
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> params,
            HttpSession session,
            javax.servlet.http.HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();

        String username = params.get("username");
        String password = params.get("password");
        String captcha = params.get("captcha");

        // 获取IP地址
        String ip = getClientIp(request);
        String lockKey = buildLockKey(username, ip);

        // 1. 检查账户锁定
        if (loginAttemptService.isBlocked(lockKey)) {
            result.put("success", false);
            result.put("message", "登录失败次数过多，该账号已被锁定30分钟");
            return result;
        }

        // 2. 验证验证码 (忽略大小写)
        String sessionCaptcha = (String) session.getAttribute("CAPTCHA_CODE");
        if (captcha == null || sessionCaptcha == null || !captcha.equalsIgnoreCase(sessionCaptcha)) {
            result.put("success", false);
            result.put("message", "验证码错误或已过期");
            session.removeAttribute("CAPTCHA_CODE"); // 验证一次即销毁
            return result;
        }
        session.removeAttribute("CAPTCHA_CODE"); // 验证通过也销毁，防止重用

        // 3. 验证用户名和密码
        if (authConfig.validateUser(username, password)) {
            // 登录成功
            loginAttemptService.loginSucceeded(lockKey); // 清除失败记录

            // 使旧session失效以防止会话fixation攻击
            session.invalidate();

            // 创建新session
            HttpSession newSession = request.getSession(true);
            newSession.setAttribute(AuthInterceptor.SESSION_USER_KEY, username);

            result.put("success", true);
            result.put("message", "登录成功");
            result.put("username", username);
        } else {
            // 登录失败
            loginAttemptService.loginFailed(lockKey);
            int remaining = loginAttemptService.getRemainingAttempts(lockKey);

            result.put("success", false);
            result.put("message", "用户名或密码错误，剩余尝试次数: " + remaining);
        }

        return result;
    }

    private String getClientIp(javax.servlet.http.HttpServletRequest request) {
        String xfHeader = request.getHeader("X-Forwarded-For");
        if (xfHeader == null) {
            return request.getRemoteAddr();
        }
        return xfHeader.split(",")[0];
    }

    private String buildLockKey(String username, String ip) {
        if (username == null || username.trim().isEmpty()) {
            return "ip:" + ip;
        }
        return "user:" + username.trim().toLowerCase();
    }

    /**
     * 登出接口
     * 
     * @param session HTTP会话
     * @return 登出结果
     */
    @PostMapping("/logout")
    public Map<String, Object> logout(HttpSession session) {
        Map<String, Object> result = new HashMap<>();

        // 销毁session
        session.invalidate();
        result.put("success", true);
        result.put("message", "已成功退出登录");

        return result;
    }

    /**
     * 检查登录状态
     * 
     * @param session HTTP会话
     * @return 登录状态
     */
    @GetMapping("/status")
    public Map<String, Object> status(HttpSession session) {
        Map<String, Object> result = new HashMap<>();

        Object user = session.getAttribute(AuthInterceptor.SESSION_USER_KEY);
        result.put("loggedIn", user != null);
        if (user != null) {
            result.put("username", user);
            result.put("isAdmin", securityConfig.getSqlCheck().getAdminUsers().contains(user));
        }

        return result;
    }
}
