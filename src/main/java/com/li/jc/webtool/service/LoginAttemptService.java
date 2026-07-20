package com.li.jc.webtool.service;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * 登录尝试次数限制服务（防止暴力破解）
 */
@Service
public class LoginAttemptService {

    private final int MAX_ATTEMPT = 5; // 最大尝试次数
    // 使用 Guava Cache 记录失败次数，Key支持 user:xxx 或 ip:xxx
    private LoadingCache<String, Integer> attemptsCache;

    public LoginAttemptService() {
        super();
        attemptsCache = CacheBuilder.newBuilder()
                .expireAfterWrite(30, TimeUnit.MINUTES) // 锁定时间30分钟
                .build(new CacheLoader<String, Integer>() {
                    @Override
                    public Integer load(String key) {
                        return 0;
                    }
                });
    }

    /**
     * 登录成功，清除失败记录
     */
    public void loginSucceeded(String key) {
        attemptsCache.invalidate(key);
    }

    /**
     * 登录失败，增加失败计数
     */
    public void loginFailed(String key) {
        int attempts = 0;
        try {
            attempts = attemptsCache.get(key);
        } catch (ExecutionException e) {
            attempts = 0;
        }
        attempts++;
        attemptsCache.put(key, attempts);
    }

    /**
     * 检查是否被锁定
     */
    public boolean isBlocked(String key) {
        try {
            return attemptsCache.get(key) >= MAX_ATTEMPT;
        } catch (ExecutionException e) {
            return false;
        }
    }

    /**
     * 获取剩余尝试次数
     */
    public int getRemainingAttempts(String key) {
        try {
            int used = attemptsCache.get(key);
            return Math.max(0, MAX_ATTEMPT - used);
        } catch (ExecutionException e) {
            return MAX_ATTEMPT;
        }
    }
}
