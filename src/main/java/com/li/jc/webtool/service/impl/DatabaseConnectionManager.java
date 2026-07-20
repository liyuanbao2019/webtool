package com.li.jc.webtool.service.impl;

import com.li.jc.webtool.config.DatabaseConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the lifecycle and metadata cache of the primary datasource pools.
 * Keeping this concern outside {@link DatabaseServiceImpl} prevents SQL execution,
 * metadata inspection and vendor administration code from managing pool state directly.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseConnectionManager {

    private final DatabaseConfig databaseConfig;
    private final Map<Integer, HikariDataSource> pools = new ConcurrentHashMap<>();
    private final Map<Integer, String> databaseTypes = new ConcurrentHashMap<>();
    private final Map<Integer, String> databaseNames = new ConcurrentHashMap<>();

    public Connection getConnection(int index) throws SQLException {
        HikariDataSource dataSource = pools.get(index);
        if (dataSource == null) {
            synchronized (pools) {
                dataSource = pools.get(index);
                if (dataSource == null) {
                    DatabaseConfig.DataSourceConfig config = getDataSourceConfig(index);
                    if (config == null) {
                        throw new SQLException("数据源配置不存在: index=" + index);
                    }
                    dataSource = createPool(config);
                    pools.put(index, dataSource);
                    String type = normalizeType(config.getType());
                    databaseTypes.put(index, type);
                    if (DatabaseDialect.isMySQL(type)) {
                        String database = DatabaseDialect.extractDatabaseFromUrl(config.getUrl());
                        if (database != null) {
                            databaseNames.put(index, database);
                        }
                    }
                }
            }
        }
        return dataSource.getConnection();
    }

    public String getDatabaseType(int index) {
        if (!databaseTypes.containsKey(index)) {
            try (Connection ignored = getConnection(index)) {
                // Opening the pool also detects and caches its configured type.
            } catch (Exception e) {
                log.warn("Unable to initialize datasource type for index={}, using ORACLE", index, e);
            }
        }
        return getCachedDatabaseType(index);
    }

    public String getCachedDatabaseType(int index) {
        return databaseTypes.getOrDefault(index, "ORACLE");
    }

    public String getCachedDatabaseName(int index) {
        return databaseNames.get(index);
    }

    public String getPoolState(int index) {
        HikariDataSource dataSource = pools.get(index);
        if (dataSource == null || dataSource.getHikariPoolMXBean() == null) {
            return "N/A";
        }
        return String.format("Active: %d, Idle: %d, Total: %d",
                dataSource.getHikariPoolMXBean().getActiveConnections(),
                dataSource.getHikariPoolMXBean().getIdleConnections(),
                dataSource.getHikariPoolMXBean().getTotalConnections());
    }

    public DatabaseConfig.DataSourceConfig getDataSourceConfig(int index) {
        if (databaseConfig.getDatasources() == null || index < 0 || index >= databaseConfig.getDatasources().size()) {
            return null;
        }
        return databaseConfig.getDatasources().get(index);
    }

    public void reset() {
        pools.values().forEach(this::closePool);
        pools.clear();
        databaseTypes.clear();
        databaseNames.clear();
    }

    @PreDestroy
    public void close() {
        reset();
    }

    private HikariDataSource createPool(DatabaseConfig.DataSourceConfig config) {
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(config.getUrl());
        hikari.setUsername(config.getUsername());
        hikari.setPassword(config.getPassword());

        String type = normalizeType(config.getType());
        if (DatabaseDialect.isMySQL(type)) {
            if ("STARROCKS".equals(type)
                    && (config.getUrl() == null || !config.getUrl().toLowerCase().startsWith("jdbc:mysql://"))) {
                throw new IllegalArgumentException(
                        "StarRocks 使用 MySQL 兼容协议，请填写 jdbc:mysql://FE_HOST:QUERY_PORT/DATABASE 格式的 JDBC URL");
            }
            hikari.setDriverClassName("com.mysql.cj.jdbc.Driver");
            hikari.setConnectionTestQuery("SELECT 1");
            hikari.setPoolName(type + "Pool-" + config.getName());
        } else if ("DM".equals(type)) {
            hikari.setDriverClassName("dm.jdbc.driver.DmDriver");
            hikari.setConnectionTestQuery("SELECT 1");
            hikari.setPoolName("DMPool-" + config.getName());
        } else if ("POSTGRESQL".equals(type) || "POSTGRES".equals(type) || "PG".equals(type)) {
            hikari.setDriverClassName("org.postgresql.Driver");
            hikari.setConnectionTestQuery("SELECT 1");
            hikari.setPoolName("PostgreSQLPool-" + config.getName());
        } else {
            hikari.setDriverClassName("oracle.jdbc.OracleDriver");
            hikari.setConnectionTestQuery("SELECT 1 FROM DUAL");
            hikari.setPoolName("OraclePool-" + config.getName());
        }

        DatabaseConfig.PoolConfig pool = databaseConfig.getPool();
        hikari.setMaximumPoolSize(pool.getMaximumPoolSize());
        hikari.setMinimumIdle(pool.getMinimumIdle());
        hikari.setConnectionTimeout(pool.getConnectionTimeout());
        hikari.setIdleTimeout(pool.getIdleTimeout());
        hikari.setMaxLifetime(pool.getMaxLifetime());
        log.info("Initializing {} datasource pool: {}, URL: {}", type, config.getName(), config.getUrl());
        return new HikariDataSource(hikari);
    }

    private String normalizeType(String type) {
        return type == null ? "ORACLE" : type.trim().toUpperCase();
    }

    private void closePool(HikariDataSource dataSource) {
        if (dataSource == null || dataSource.isClosed()) {
            return;
        }
        try {
            dataSource.close();
        } catch (Exception e) {
            log.warn("Close datasource pool failed: {}", dataSource.getPoolName(), e);
        }
    }
}
