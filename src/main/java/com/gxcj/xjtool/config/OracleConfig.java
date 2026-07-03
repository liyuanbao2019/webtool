package com.gxcj.xjtool.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Oracle 数据源配置类
 * 从 application.yml 读取多个 Oracle 数据库连接配置
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "oracle")
public class OracleConfig {

    /**
     * 数据源列表
     */
    private List<OracleDataSource> datasources = new ArrayList<>();

    /**
     * 连接池配置
     */
    private PoolConfig pool = new PoolConfig();

    /**
     * 查询保护配置
     */
    private QueryConfig query = new QueryConfig();

    /**
     * 数据源配置（兼容Oracle、达梦、MySQL数据库）
     */
    @Data
    public static class OracleDataSource {
        /**
         * 数据源名称（显示用）
         */
        private String name;

        /**
         * 数据库类型: ORACLE、DM (达梦)、MYSQL
         */
        private String type = "ORACLE"; // 默认为Oracle

        /**
         * JDBC URL
         */
        private String url;

        /**
         * Whether this MySQL datasource is a PXC/Galera cluster.
         * Defaults to true for backward compatibility with existing production MySQL configs.
         */
        private boolean pxc = true;

        /**
         * 用户名
         */
        private String username;

        /**
         * 密码
         */
        private String password;

        /**
         * RAC 从节点 IP 列表（逗号分隔），用于精准杀掉会话
         * 仅 Oracle 且需要主备切换时配置
         */
        private String slave;
    }

    /**
     * 连接池配置
     */
    @Data
    public static class PoolConfig {
        /**
         * 每个数据源最大连接数（避免过多连接影响业务）
         */
        private int maximumPoolSize = 5;

        /**
         * 最小空闲连接数
         */
        private int minimumIdle = 1;

        /**
         * 连接超时（毫秒）
         */
        private long connectionTimeout = 30000;

        /**
         * 空闲连接超时（毫秒）
         */
        private long idleTimeout = 600000;

        /**
         * 连接最大生命周期（毫秒）
         */
        private long maxLifetime = 1800000;
    }

    /**
     * 查询保护配置（防止OOM和慢查询）
     */
    @Data
    public static class QueryConfig {
        /**
         * 默认最大返回行数 (0表示无限制)
         */
        private int defaultMaxRows = 0;

        /**
         * 查询超时时间（秒）
         */
        private int maxQueryTimeout = 300;

        /**
         * 每次从数据库fetch的行数
         */
        private int fetchSize = 500;
    }
}
