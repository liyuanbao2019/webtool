package com.li.jc.webtool.service.impl;

import com.li.jc.webtool.config.SecurityConfig;
import com.li.jc.webtool.service.RateLimitService;
import com.google.common.util.concurrent.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 请求限流服务实现
 * 使用Guava RateLimiter实现基于用户和IP的限流
 */
@Service
public class RateLimitServiceImpl implements RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitServiceImpl.class);

    @Autowired
    private SecurityConfig securityConfig;

    // 用户限流器缓存 (username -> RateLimiter)
    private final Map<String, RateLimiter> userLimiters = new ConcurrentHashMap<>();

    // IP限流器缓存 (ip -> RateLimiter)
    private final Map<String, RateLimiter> ipLimiters = new ConcurrentHashMap<>();

    @Override
    public boolean allowUserRequest(String username) {
        if (!securityConfig.getRateLimit().isEnabled()) {
            return true;
        }

        if (username == null || username.isEmpty()) {
            log.warn("用户名为空，拒绝请求");
            return false;
        }

        // 获取或创建用户限流器
        RateLimiter limiter = userLimiters.computeIfAbsent(username, k -> {
            double permitsPerMinute = securityConfig.getRateLimit().getPerUser();
            double permitsPerSecond = permitsPerMinute / 60.0;
            log.debug("为用户 {} 创建限流器: {}请求/分钟", username, permitsPerMinute);
            return RateLimiter.create(permitsPerSecond);
        });

        // 尝试获取令牌（允许等待最多100ms）
        boolean allowed = limiter.tryAcquire(1, 100, java.util.concurrent.TimeUnit.MILLISECONDS);
        if (!allowed) {
            log.warn("用户 {} 请求超限", username);
        }

        return allowed;
    }

    @Override
    public boolean allowIpRequest(String ip) {
        if (!securityConfig.getRateLimit().isEnabled()) {
            return true;
        }

        if (ip == null || ip.isEmpty()) {
            log.warn("IP地址为空，拒绝请求");
            return false;
        }

        // 获取或创建IP限流器
        RateLimiter limiter = ipLimiters.computeIfAbsent(ip, k -> {
            double permitsPerMinute = securityConfig.getRateLimit().getPerIp();
            double permitsPerSecond = permitsPerMinute / 60.0;
            log.debug("为IP {} 创建限流器: {}请求/分钟", ip, permitsPerMinute);
            return RateLimiter.create(permitsPerSecond);
        });

        // 尝试获取令牌（允许等待最多100ms）
        boolean allowed = limiter.tryAcquire(1, 100, java.util.concurrent.TimeUnit.MILLISECONDS);
        if (!allowed) {
            log.warn("IP {} 请求超限", ip);
        }

        return allowed;
    }
}
