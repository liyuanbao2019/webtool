package com.li.jc.webtool.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Per-account script library. Disk is replaced only after the complete new snapshot is written. */
@Service
public class SavedSqlService {
    private final Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    public SavedSqlService(@Value("${app.saved-sql-directory:./data/saved-sql}") String directory) {
        this.directory = Paths.get(directory).toAbsolutePath().normalize();
    }

    @Data
    public static class Script {
        private String id;
        private String name;
        private String sql;
        private long updatedAt;
    }

    public synchronized List<Script> list(String user) {
        List<Script> scripts = read(user);
        scripts.sort(Comparator.comparingLong(Script::getUpdatedAt).reversed());
        scripts.forEach(s -> s.setSql(null));
        return scripts;
    }

    public synchronized Script get(String user, String id) { return find(read(user), id); }

    public synchronized Script save(String user, String id, Script input) {
        if (input == null || input.getName() == null || input.getName().trim().isEmpty()
                || input.getName().trim().length() > 100) {
            throw new IllegalArgumentException("脚本名称须为 1–100 个字符");
        }
        if (input.getSql() == null || input.getSql().trim().isEmpty()) {
            throw new IllegalArgumentException("SQL 内容不能为空");
        }
        if (input.getSql().getBytes(StandardCharsets.UTF_8).length > 1024 * 1024) {
            throw new IllegalArgumentException("单个 SQL 脚本不能超过 1 MB");
        }
        List<Script> scripts = read(user);
        Script target = id == null ? new Script() : find(scripts, id);
        String name = input.getName().trim();
        if (scripts.stream().anyMatch(s -> name.equals(s.getName()) && !Objects.equals(id, s.getId()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已存在同名脚本，请修改名称或打开原脚本后保存");
        }
        if (id == null) {
            if (scripts.size() >= 200) throw new IllegalArgumentException("每个账号最多保存 200 个 SQL 脚本");
            target.setId(UUID.randomUUID().toString());
            scripts.add(target);
        }
        target.setName(name);
        target.setSql(input.getSql());
        target.setUpdatedAt(System.currentTimeMillis());
        write(user, scripts);
        return target;
    }

    public synchronized void delete(String user, String id) {
        List<Script> scripts = read(user);
        scripts.remove(find(scripts, id));
        write(user, scripts);
    }

    private Script find(List<Script> scripts, String id) {
        return scripts.stream().filter(s -> id.equals(s.getId())).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "脚本不存在或无权访问"));
    }

    private Path file(String user) {
        if (user == null || user.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录后管理个人 SQL 脚本");
        }
        // The account never becomes a filesystem path, including accounts containing slashes.
        String key;
        // SHA-256 keeps filenames bounded even for long account names.
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(user.getBytes(StandardCharsets.UTF_8));
            key = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        return directory.resolve(key + ".json");
    }

    private List<Script> read(String user) {
        Path file = file(user);
        if (!Files.exists(file)) return new ArrayList<>();
        try {
            List<Script> scripts = mapper.readValue(file.toFile(), new TypeReference<List<Script>>() { });
            if (scripts == null || scripts.stream().anyMatch(s -> s == null || s.getId() == null
                    || s.getName() == null || s.getSql() == null)) throw new IOException("Invalid script library");
            return scripts;
        } catch (IOException e) {
            throw new IllegalStateException("读取个人 SQL 脚本失败，请联系管理员检查存储文件；原文件已保留", e);
        }
    }

    private void write(String user, List<Script> scripts) {
        Path file = file(user);
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, "saved-sql-", ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), scripts);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("保存个人 SQL 脚本失败，请联系管理员检查存储目录权限", e);
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }
}
