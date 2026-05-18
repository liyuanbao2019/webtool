package com.gxcj.xjtool.service;

/**
 * 请求限流服务接口
 * 基于用户名和IP地址的限流
 */
public interface RateLimitService {

    /**
     * 检查用户请求是否超限
     * 
     * @param username 用户名
     * @return true=允许请求，false=超限
     */
    boolean allowUserRequest(String username);

    /**
     * 检查IP请求是否超限
     * 
     * @param ip IP地址
     * @return true=允许请求，false=超限
     */
    boolean allowIpRequest(String ip);
}
