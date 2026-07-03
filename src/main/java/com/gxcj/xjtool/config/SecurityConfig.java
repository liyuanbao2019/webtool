package com.gxcj.xjtool.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 安全配置类
 * 从application.yml读取安全相关配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "security")
public class SecurityConfig {

    /**
     * CSRF防护配置
     */
    private CsrfConfig csrf = new CsrfConfig();

    /**
     * 请求限流配置
     */
    private RateLimitConfig rateLimit = new RateLimitConfig();

    /**
     * SQL安全检查配置
     */
    private SqlCheckConfig sqlCheck = new SqlCheckConfig();

    /**
     * 来源验证配置
     */
    private OriginCheckConfig originCheck = new OriginCheckConfig();

    @Data
    public static class CsrfConfig {
        /**
         * 是否启用CSRF防护
         */
        private boolean enabled = true;

        /**
         * Token有效期（秒）
         */
        private int tokenTimeout = 1800;
    }

    @Data
    public static class RateLimitConfig {
        /**
         * 是否启用限流
         */
        private boolean enabled = true;

        /**
         * 每用户每分钟请求数
         */
        private int perUser = 10;

        /**
         * 每IP每分钟请求数
         */
        private int perIp = 20;
    }

    @Data
    public static class SqlCheckConfig {
        /**
         * 是否启用SQL安全检查
         */
        private boolean enabled = true;

        /**
         * 危险关键词黑名单
         */
        private List<String> blockKeywords = new ArrayList<>();

        /**
         * 管理员用户白名单（可执行危险SQL）
         */
        private List<String> adminUsers = new ArrayList<>();

        /**
         * MySQL/PXC DDL protection.
         */
        private MysqlPxcDdlConfig mysqlPxcDdl = new MysqlPxcDdlConfig();
    }

    @Data
    public static class MysqlPxcDdlConfig {
        /**
         * Enable special protection for high-risk DDL on MySQL PXC/Galera datasources.
         */
        private boolean enabled = true;

        /**
         * Protect every MySQL datasource, not only those matched as PXC/Galera.
         */
        private boolean protectAllMysql = false;

        /**
         * Tables with estimated rows greater than or equal to this value are blocked.
         */
        private long largeTableRows = 1000000L;

        /**
         * Block DDL when table size cannot be determined.
         */
        private boolean blockWhenTableSizeUnknown = true;
    }

    @Data
    public static class OriginCheckConfig {
        /**
         * 是否启用来源验证
         */
        private boolean enabled = true;

        /**
         * 允许的来源列表
         */
        private List<String> allowedOrigins = new ArrayList<>();
    }
}
