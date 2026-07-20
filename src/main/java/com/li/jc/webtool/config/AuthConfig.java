package com.li.jc.webtool.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 认证配置类
 * 从application.yml读取登录认证相关配置
 * 支持多用户配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "auth")
public class AuthConfig {

    /**
     * 是否启用登录认证
     */
    private boolean enabled = true;

    /**
     * 用户列表 (username -> password)
     * 示例：
     * users:
     * jkyw: "123456Wp!@#"
     * zhangsan: "password123"
     */
    private Map<String, String> users = new HashMap<>();

    /**
     * 验证用户名和密码
     *
     * @param username 用户名
     * @param password 密码
     * @return 验证是否通过
     */
    public boolean validateUser(String username, String password) {
        if (username == null || password == null) {
            return false;
        }
        String storedPassword = users.get(username);
        return password.equals(storedPassword);
    }

    /**
     * 检查用户是否存在
     *
     * @param username 用户名
     * @return 用户是否存在
     */
    public boolean userExists(String username) {
        return users.containsKey(username);
    }
}
