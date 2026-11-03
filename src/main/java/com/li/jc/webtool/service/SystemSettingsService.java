package com.li.jc.webtool.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.li.jc.webtool.config.SystemSettingsEnvironment;
import lombok.Data;
import org.springframework.core.env.*;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.util.*;
import java.security.MessageDigest;

@Service
public class SystemSettingsService {
    private final ConfigurableEnvironment environment;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String,Object> running;
    public SystemSettingsService(ConfigurableEnvironment environment) {
        this.environment = environment;
        running = new LinkedHashMap<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            if ((source.getName().startsWith("Config resource") || source.getName().equals(SystemSettingsEnvironment.SOURCE))
                    && source instanceof EnumerablePropertySource) {
                for (String key : ((EnumerablePropertySource<?>) source).getPropertyNames())
                    if (SystemSettingsEnvironment.managed(key)) running.put(key, environment.getProperty(key));
            }
        }
    }
    @Data public static class Field {
        private String key;
        @lombok.ToString.Exclude
        private Object value;
        private boolean secret;
        private boolean retain;
    }
    @Data public static class View {
        private String version;
        private boolean restartRequired;
        private List<Field> fields = new ArrayList<>();
    }
    public static boolean secret(String key) {
        return key.startsWith("auth.users.") || key.toLowerCase(Locale.ROOT).matches(".*(?:password|token|secret|credential)$")
            || key.contains("su-passwords.") || key.contains("su_passwords.");
    }
    private Map<String,Object> stored() {
        Path file = SystemSettingsEnvironment.file(environment);
        if (!Files.exists(file)) return new LinkedHashMap<>(running);
        try { return mapper.readValue(file.toFile(), new TypeReference<LinkedHashMap<String,Object>>() {}); }
        catch (Exception e) { throw new IllegalStateException("无法读取系统设置，请检查配置文件并保留原文件", e); }
    }
    private String version(Map<String,Object> values) {
        try { return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(new TreeMap<>(values)))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public synchronized View get() {
        Map<String,Object> values = stored();
        View view = new View(); view.setVersion(version(values));
        view.setRestartRequired(!version(values).equals(version(running)));
        new TreeMap<>(values).forEach((key,value) -> {
            Field field = new Field(); field.setKey(key); field.setSecret(secret(key));
            field.setRetain(secret(key)); field.setValue(secret(key) ? null : value); view.getFields().add(field);
        });
        return view;
    }
    public synchronized View save(View request, String currentUser) {
        Map<String,Object> old = stored();
        if (request == null || !Objects.equals(request.getVersion(), version(old)))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "配置已被其他管理员修改，请重新加载后保存");
        if (request.getFields() == null || request.getFields().size() > 3000) throw new IllegalArgumentException("配置项数量无效");
        Map<String,Object> values = new LinkedHashMap<>();
        for (Field field : request.getFields()) {
            String key = field.getKey();
            if (key == null || !SystemSettingsEnvironment.managed(key) || !key.matches("[A-Za-z0-9_.\\[\\]-]{1,240}"))
                throw new IllegalArgumentException("配置键格式或范围不正确");
            if (values.containsKey(key)) throw new IllegalArgumentException("配置键重复：" + key);
            Object value = field.isRetain() && secret(key) ? old.get(key) : field.getValue();
            if (value == null || !(value instanceof String || value instanceof Number || value instanceof Boolean)
                || value.toString().length() > 16000) throw new IllegalArgumentException("配置值无效：" + key);
            values.put(key,value);
        }
        if (!values.containsKey("auth.users." + currentUser) || values.get("auth.users." + currentUser).toString().trim().isEmpty())
            throw new IllegalArgumentException("请保留当前管理员账号及有效密码，避免重启后无法登录");
        if (values.entrySet().stream().noneMatch(e -> e.getKey().startsWith("security.sql-check.admin-users[") && currentUser.equals(e.getValue().toString())))
            throw new IllegalArgumentException("管理员列表须保留当前账号");
        for (Map.Entry<String,Object> entry : values.entrySet()) {
            String key = entry.getKey(), value = entry.getValue().toString();
            if (key.endsWith(".enabled") || key.endsWith(".cache") || key.endsWith(".log-commands")
                || key.endsWith(".no-cache") || key.endsWith(".no-store"))
                if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("开关须为 true 或 false：" + key);
            if (key.equals("server.port") || key.equals("app.agent.port")) {
                try { int port = Integer.parseInt(value); if(port < 1 || port > 65535) throw new NumberFormatException(); }
                catch (NumberFormatException e) { throw new IllegalArgumentException("端口须为 1–65535：" + key); }
            }
            if (key.startsWith("logging.level.") && !Arrays.asList("TRACE","DEBUG","INFO","WARN","ERROR","FATAL","OFF").contains(value.toUpperCase(Locale.ROOT)))
                throw new IllegalArgumentException("日志级别无效：" + key);
            if (key.matches("ssh\\.(max-connections|connection-timeout|keepalive-interval)") || key.matches("oracle\\.(pool|query)\\..+")) {
                try { if (Long.parseLong(value) < 0 || (key.equals("ssh.max-connections") && Long.parseLong(value) == 0)) throw new NumberFormatException(); }
                catch (NumberFormatException e) { throw new IllegalArgumentException("请输入有效的非负整数：" + key); }
            }
            if (key.equals("security.csrf.token-timeout") || key.equals("security.rate-limit.per-user") || key.equals("security.rate-limit.per-ip")) {
                try { if (Long.parseLong(value) < 1) throw new NumberFormatException(); }
                catch (NumberFormatException e) { throw new IllegalArgumentException("请输入正整数：" + key); }
            }
        }
        try {
            org.springframework.boot.context.properties.bind.Binder binder = new org.springframework.boot.context.properties.bind.Binder(
                new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(values));
            binder.bind("auth", org.springframework.boot.context.properties.bind.Bindable.of(com.li.jc.webtool.config.AuthConfig.class));
            binder.bind("security", org.springframework.boot.context.properties.bind.Bindable.of(com.li.jc.webtool.config.SecurityConfig.class));
            binder.bind("audit.ssh", org.springframework.boot.context.properties.bind.Bindable.of(com.li.jc.webtool.config.SshSecurityConfig.class));
            com.li.jc.webtool.config.DatabaseConfig database = binder.bind("oracle", org.springframework.boot.context.properties.bind.Bindable.of(com.li.jc.webtool.config.DatabaseConfig.class)).orElse(new com.li.jc.webtool.config.DatabaseConfig());
            if (database.getPool().getMaximumPoolSize() < 1 || database.getPool().getMinimumIdle() > database.getPool().getMaximumPoolSize())
                throw new IllegalArgumentException();
            binder.bind("app", org.springframework.boot.context.properties.bind.Bindable.of(com.li.jc.webtool.config.ServerConfig.class));
            binder.bind("server", org.springframework.boot.context.properties.bind.Bindable.of(org.springframework.boot.autoconfigure.web.ServerProperties.class));
            binder.bind("spring.servlet.multipart", org.springframework.boot.context.properties.bind.Bindable.of(org.springframework.boot.autoconfigure.web.servlet.MultipartProperties.class));
        } catch (Exception e) { throw new IllegalArgumentException("参数类型或格式不正确，请检查数值、时间、大小及列表配置"); }
        Path file = SystemSettingsEnvironment.file(environment), temp = null;
        try {
            Files.createDirectories(file.getParent());
            temp = Files.createTempFile(file.getParent(), "system-settings-", ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(),values);
            if (Files.exists(file)) Files.copy(file,file.resolveSibling(file.getFileName()+".bak"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.COPY_ATTRIBUTES);
            try { Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception e) { throw new IllegalStateException("保存系统设置失败，请检查配置目录写入权限", e); }
        finally { if(temp != null) try { Files.deleteIfExists(temp); } catch(Exception ignored) {} }
        return get();
    }
}
