package com.li.jc.webtool.model;

import lombok.Data;

/**
 * 服务器信息DTO（数据传输对象）
 * 整个对象加密传输，保护所有敏感信息
 */
@Data
public class ServerInfoDTO {
    // 加密后的完整服务器信息（JSON格式）
    private String encryptedData;
}
