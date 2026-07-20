package com.li.jc.webtool.service;

/**
 * SSH审计日志服务接口
 * 记录SSH连接和操作日志
 */
public interface SshAuditService {

    /**
     * 记录SSH连接建立
     * 
     * @param username     WebTool登录用户名
     * @param sessionId    WebSocket会话ID
     * @param targetHost   目标服务器地址
     * @param targetPort   目标SSH端口
     * @param targetUser   目标SSH用户名
     * @param success      连接是否成功
     * @param errorMessage 失败时的错误信息
     */
    void logConnection(String username, String sessionId, String targetHost,
            int targetPort, String targetUser, boolean success, String errorMessage);

    /**
     * 记录SSH连接断开
     * 
     * @param username       WebTool登录用户名
     * @param sessionId      WebSocket会话ID
     * @param connectionTime 连接持续时间（毫秒）
     */
    void logDisconnection(String username, String sessionId, long connectionTime);

    /**
     * 记录SSH命令执行（可选功能）
     * 
     * @param username   WebTool登录用户名
     * @param sessionId  WebSocket会话ID
     * @param targetHost 目标服务器地址
     * @param command    执行的命令
     */
    void logCommand(String username, String sessionId, String targetHost, String command);
}
