package com.li.jc.webtool.dto;

/**
 * SFTP 读取远程脚本文件的结果：包含内容与实际读取成功的路径（可能与请求路径不同，例如按 shell 目录回退后）
 */
public class ScriptReadResult {

    private final String content;
    /** 实际通过 SFTP stat/read 成功的绝对路径 */
    private final String resolvedRemotePath;

    public ScriptReadResult(String content, String resolvedRemotePath) {
        this.content = content;
        this.resolvedRemotePath = resolvedRemotePath;
    }

    public String getContent() {
        return content;
    }

    public String getResolvedRemotePath() {
        return resolvedRemotePath;
    }
}
