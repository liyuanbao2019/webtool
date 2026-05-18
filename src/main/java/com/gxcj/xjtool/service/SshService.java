package com.gxcj.xjtool.service;

import com.gxcj.xjtool.dto.ScriptReadResult;
import com.gxcj.xjtool.model.WebSshData;
import org.springframework.web.socket.WebSocketSession;

public interface SshService {
    void initConnection(WebSocketSession session, WebSshData webSshData);

    void recvHandle(WebSocketSession session, WebSshData webSshData);

    void sendMessage(WebSocketSession session, byte[] buffer);

    void close(WebSocketSession session);

    // 保持连接活跃,更新最后活动时间
    void keepAlive(WebSocketSession session);

    // 调整终端大小
    void resizeTerminal(WebSocketSession session, Integer cols, Integer rows);

    // 动态切换终端字符集
    void updateTerminalCharset(WebSocketSession session, String charset);

    // 获取当前连接数（用于监控和连接限制检查）
    int getCurrentConnectionCount();

    // 获取最大连接数（用于监控和连接限制检查）
    int getMaxConnections();

    /**
     * 通过SFTP读取远程服务器上的脚本文件内容
     * 用于后端深度扫描脚本内容中的危险操作
     *
     * @param session WebSocket会话
     * @param scriptPath 脚本文件的绝对路径（多为前端拼接，可能与实际目录不一致）
     * @param charsetName 字符集名称（UTF-8/GBK等）
     * @return 内容与 {@link ScriptReadResult#getResolvedRemotePath() 实际读取成功的路径}；读取失败返回 null
     */
    ScriptReadResult readScriptFile(WebSocketSession session, String scriptPath, String charsetName);
}
