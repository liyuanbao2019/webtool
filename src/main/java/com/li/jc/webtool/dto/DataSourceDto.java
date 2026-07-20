package com.li.jc.webtool.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Encrypted datasource summary returned to the SQL tool frontend.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DataSourceDto {
    /**
     * 索引（用于选择）- 解密后使用
     */
    @Builder.Default
    private Integer index = null;

    /**
     * 数据源名称 - 解密后使用
     */
    private String name;

    /**
     * 连接 URL（脱敏显示）- 解密后使用
     */
    private String url;

    /**
     * 用户名 - 解密后使用
     */
    private String username;

    /**
     * 加密后的完整数据（用于传输安全）
     */
    private String encryptedData;
}
