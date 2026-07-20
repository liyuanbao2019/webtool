package com.li.jc.webtool.model;

import lombok.Data;

@Data
public class WebSshData {
    private String operate; // connect, command, resize
    private String host;
    private Integer port;
    private String username;
    private String password;
    private String connectionMode; // ssh, agent
    private String agentBaseUrl;
    private String agentId;
    private String agentToken;
    private String command;
    private String commandText;
    private Integer cols; // 终端列数
    private Integer rows; // 终端行数
    private String charset; // 终端字符集（UTF-8/GBK/GB2312/GB18030）
    private String dangerousCommandToken; // 危险命令确认Token（用于后端验证）
    private String dangerousCommandText; // 危险命令文本（用于后端验证，避免缓冲区累积问题）
    private String scriptConfirmToken; // 脚本确认Token（用于后端验证脚本执行请求）
    private String scriptConfirmText; // 脚本确认文本（用于后端验证）
    private String scriptScanToken; // 脚本扫描后生成的一次性确认Token
    private String scriptPreviewContent; // 脚本执行预览内容（前端传给后端用于显示和验证）
    private String scriptPath; // 脚本文件路径（用于后端通过SFTP读取脚本内容进行深度扫描）
}
