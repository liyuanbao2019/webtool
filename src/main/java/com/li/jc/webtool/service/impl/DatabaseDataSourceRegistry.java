package com.li.jc.webtool.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.li.jc.webtool.config.DatabaseConfig;
import com.li.jc.webtool.dto.DataSourceDto;
import com.li.jc.webtool.dto.SqlResultResponse;
import com.li.jc.webtool.util.CryptoUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Loads, validates, encrypts and persists the shared database datasource catalog. */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseDataSourceRegistry {

    private static final List<String> SUPPORTED_TYPES = Arrays.asList(
            "ORACLE", "DM", "MYSQL", "STARROCKS", "POSTGRESQL", "POSTGRES", "PG");

    private final DatabaseConfig databaseConfig;
    private final DatabaseConnectionManager connectionManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.sql-datasource-config-file:./data/sql-datasources.json}")
    private String configFile;

    @PostConstruct
    public void load() {
        File file = new File(configFile);
        if (!file.exists()) {
            persist();
            return;
        }
        try {
            List<DatabaseConfig.DataSourceConfig> loaded = objectMapper.readValue(file,
                    new TypeReference<List<DatabaseConfig.DataSourceConfig>>() { });
            databaseConfig.setDatasources(new ArrayList<>(loaded));
            log.info("Loaded runtime SQL datasources from {}", file.getAbsolutePath());
        } catch (Exception e) {
            log.error("Load runtime SQL datasource config failed: {}", file.getAbsolutePath(), e);
        }
    }

    public List<DataSourceDto> getEncryptedDataSources() {
        List<DataSourceDto> result = new ArrayList<>();
        List<DatabaseConfig.DataSourceConfig> dataSources = databaseConfig.getDatasources();
        if (dataSources == null) {
            return result;
        }
        for (int i = 0; i < dataSources.size(); i++) {
            DatabaseConfig.DataSourceConfig source = dataSources.get(i);
            DataSourceDto plain = DataSourceDto.builder()
                    .index(i)
                    .name(source.getName())
                    .url(source.getUrl())
                    .username(source.getUsername())
                    .build();
            try {
                DataSourceDto encrypted = new DataSourceDto();
                encrypted.setEncryptedData(CryptoUtil.encrypt(objectMapper.writeValueAsString(plain)));
                result.add(encrypted);
            } catch (Exception e) {
                log.error("Encrypt datasource information failed", e);
            }
        }
        return result;
    }

    public List<DataSourceDto> getEncryptedManageDataSources() {
        List<DataSourceDto> result = new ArrayList<>();
        List<DatabaseConfig.DataSourceConfig> dataSources = databaseConfig.getDatasources();
        if (dataSources == null) {
            return result;
        }
        for (int i = 0; i < dataSources.size(); i++) {
            DatabaseConfig.DataSourceConfig source = dataSources.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", i);
            row.put("name", source.getName());
            row.put("type", source.getType());
            row.put("url", source.getUrl());
            row.put("pxc", source.isPxc());
            row.put("username", source.getUsername());
            row.put("password", source.getPassword());
            row.put("slave", source.getSlave());
            try {
                DataSourceDto encrypted = new DataSourceDto();
                encrypted.setEncryptedData(CryptoUtil.encrypt(objectMapper.writeValueAsString(row)));
                result.add(encrypted);
            } catch (Exception e) {
                log.error("Encrypt datasource management row failed", e);
            }
        }
        return result;
    }

    public synchronized SqlResultResponse add(DatabaseConfig.DataSourceConfig request) {
        String validationError = validate(request);
        if (validationError != null) {
            return SqlResultResponse.error(validationError);
        }
        List<DatabaseConfig.DataSourceConfig> dataSources = mutableDataSources();
        dataSources.add(copy(request));
        save(dataSources);
        return success("数据源已新增");
    }

    public synchronized SqlResultResponse update(int index, DatabaseConfig.DataSourceConfig request) {
        String validationError = validate(request);
        if (validationError != null) {
            return SqlResultResponse.error(validationError);
        }
        List<DatabaseConfig.DataSourceConfig> dataSources = mutableDataSources();
        if (index < 0 || index >= dataSources.size()) {
            return SqlResultResponse.error("数据源不存在");
        }
        dataSources.set(index, copy(request));
        save(dataSources);
        return success("数据源已更新");
    }

    public synchronized SqlResultResponse delete(int index) {
        List<DatabaseConfig.DataSourceConfig> dataSources = mutableDataSources();
        if (index < 0 || index >= dataSources.size()) {
            return SqlResultResponse.error("数据源不存在");
        }
        dataSources.remove(index);
        save(dataSources);
        return success("数据源已删除");
    }

    public DatabaseConfig.DataSourceConfig get(int index) {
        return connectionManager.getDataSourceConfig(index);
    }

    private void save(List<DatabaseConfig.DataSourceConfig> dataSources) {
        databaseConfig.setDatasources(dataSources);
        persist();
        connectionManager.reset();
    }

    private void persist() {
        File file = new File(configFile);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            log.warn("Create SQL datasource config directory failed: {}", parent.getAbsolutePath());
            return;
        }
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, mutableDataSources());
        } catch (IOException e) {
            log.error("Persist SQL datasource config failed: {}", file.getAbsolutePath(), e);
        }
    }

    private List<DatabaseConfig.DataSourceConfig> mutableDataSources() {
        return databaseConfig.getDatasources() == null
                ? new ArrayList<>()
                : new ArrayList<>(databaseConfig.getDatasources());
    }

    private String validate(DatabaseConfig.DataSourceConfig request) {
        if (request == null) {
            return "数据源配置不能为空";
        }
        if (isBlank(request.getName())) {
            return "数据源名称不能为空";
        }
        if (isBlank(request.getType())) {
            return "数据库类型不能为空";
        }
        String type = request.getType().trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_TYPES.contains(type)) {
            return "不支持的数据库类型: " + request.getType();
        }
        if (isBlank(request.getUrl())) {
            return "JDBC URL不能为空";
        }
        if ("STARROCKS".equals(type) && !request.getUrl().trim().toLowerCase(Locale.ROOT).startsWith("jdbc:mysql://")) {
            return "StarRocks 使用 MySQL 兼容协议，请填写 jdbc:mysql://FE_HOST:QUERY_PORT/DATABASE 格式的 JDBC URL";
        }
        if (isBlank(request.getUsername())) {
            return "用户名不能为空";
        }
        if (request.getPassword() == null) {
            request.setPassword("");
        }
        request.setType(type);
        return null;
    }

    private DatabaseConfig.DataSourceConfig copy(DatabaseConfig.DataSourceConfig source) {
        DatabaseConfig.DataSourceConfig target = new DatabaseConfig.DataSourceConfig();
        target.setName(trim(source.getName()));
        target.setType(source.getType() == null ? "ORACLE" : source.getType().trim().toUpperCase(Locale.ROOT));
        target.setUrl(trim(source.getUrl()));
        target.setPxc(source.isPxc());
        target.setUsername(trim(source.getUsername()));
        target.setPassword(source.getPassword());
        target.setSlave(trim(source.getSlave()));
        return target;
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private SqlResultResponse success(String message) {
        return SqlResultResponse.success(Collections.singletonList("message"),
                Collections.<Map<String, Object>>singletonList(
                        Collections.<String, Object>singletonMap("message", message)), 0);
    }
}
