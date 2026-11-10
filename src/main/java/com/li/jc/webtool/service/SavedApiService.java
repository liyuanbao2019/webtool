package com.li.jc.webtool.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 个人接口库：按 WebTool 登录账号保存调测过的接口（方法、URL、参数、请求头、请求体），
 * 可放在自定义的多级文件夹中。每个账号一个 JSON 文件，完整写好新文件后再替换旧文件。
 */
@Service
public class SavedApiService {

    static final int MAX_REQUESTS = 500;
    static final int MAX_FOLDERS = 200;
    static final int MAX_FOLDER_DEPTH = 5;
    static final int MAX_REQUEST_BYTES = 1024 * 1024;
    static final int MAX_NAME_LENGTH = 100;
    static final int MAX_FOLDER_NAME_LENGTH = 50;
    static final int MAX_DESCRIPTION_LENGTH = 500;
    static final int MAX_URL_LENGTH = 8192;
    static final int MAX_PAIRS = 200;
    static final Set<String> METHODS = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS")));

    private final Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    public SavedApiService(@Value("${app.saved-api-directory:./data/saved-api}") String directory) {
        this.directory = Paths.get(directory).toAbsolutePath().normalize();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Library {
        private List<Folder> folders = new ArrayList<>();
        private List<ApiRequest> requests = new ArrayList<>();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Folder {
        private String id;
        private String name;
        /** 上级文件夹，null 表示顶层。 */
        private String parentId;
        private long updatedAt;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class KeyValue {
        private String key;
        private String value;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ApiRequest {
        private String id;
        /** 所在文件夹，null 表示不在任何文件夹中。 */
        private String folderId;
        private String name;
        private String description;
        private String method;
        private String url;
        private List<KeyValue> params;
        private List<KeyValue> headers;
        private String body;
        private long updatedAt;
    }

    /** 文件夹和接口列表；接口只含名称、方法、URL 等概要，打开时再取完整内容。 */
    public synchronized Library list(String user) {
        Library library = read(user);
        Library result = new Library();
        result.setFolders(library.getFolders());
        result.setRequests(library.getRequests().stream().map(this::summary).collect(Collectors.toList()));
        return result;
    }

    public synchronized ApiRequest get(String user, String id) {
        return findRequest(read(user), id);
    }

    /** id 为 null 时新建，否则更新已有接口的全部内容（包括名称和所在文件夹）。 */
    public synchronized ApiRequest save(String user, String id, ApiRequest input) {
        if (input == null) {
            throw new IllegalArgumentException("缺少接口内容");
        }
        Library library = read(user);
        ApiRequest target = id == null ? new ApiRequest() : findRequest(library, id);
        String folderId = blankToNull(input.getFolderId());
        requireFolderOrRoot(library, folderId);
        String name = requestName(input.getName());
        requireUniqueRequestName(library, folderId, name, id);
        ApiRequest clean = validated(input);
        if (id == null) {
            if (library.getRequests().size() >= MAX_REQUESTS) {
                throw new IllegalArgumentException("每个账号最多保存 " + MAX_REQUESTS + " 个接口");
            }
            target.setId(UUID.randomUUID().toString());
            library.getRequests().add(target);
        }
        target.setFolderId(folderId);
        target.setName(name);
        target.setDescription(clean.getDescription());
        target.setMethod(clean.getMethod());
        target.setUrl(clean.getUrl());
        target.setParams(clean.getParams());
        target.setHeaders(clean.getHeaders());
        target.setBody(clean.getBody());
        target.setUpdatedAt(System.currentTimeMillis());
        write(user, library);
        return target;
    }

    public synchronized ApiRequest renameRequest(String user, String id, String newName) {
        Library library = read(user);
        ApiRequest target = findRequest(library, id);
        String name = requestName(newName);
        requireUniqueRequestName(library, target.getFolderId(), name, id);
        target.setName(name);
        target.setUpdatedAt(System.currentTimeMillis());
        write(user, library);
        return summary(target);
    }

    public synchronized ApiRequest moveRequest(String user, String id, String folderId) {
        Library library = read(user);
        ApiRequest target = findRequest(library, id);
        String folder = blankToNull(folderId);
        requireFolderOrRoot(library, folder);
        requireUniqueRequestName(library, folder, target.getName(), id);
        target.setFolderId(folder);
        target.setUpdatedAt(System.currentTimeMillis());
        write(user, library);
        return summary(target);
    }

    /** 在同一文件夹中复制一份，名称自动加“ 副本”“ 副本 2”…… */
    public synchronized ApiRequest copyRequest(String user, String id) {
        Library library = read(user);
        ApiRequest source = findRequest(library, id);
        if (library.getRequests().size() >= MAX_REQUESTS) {
            throw new IllegalArgumentException("每个账号最多保存 " + MAX_REQUESTS + " 个接口");
        }
        String base = source.getName() + " 副本";
        String name = base;
        for (int i = 2; nameTaken(library, source.getFolderId(), name, null); i++) {
            name = base + " " + i;
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("接口名称过长，无法自动生成副本名称，请先重命名");
        }
        ApiRequest copy = mapper.convertValue(source, ApiRequest.class);
        copy.setId(UUID.randomUUID().toString());
        copy.setName(name);
        copy.setUpdatedAt(System.currentTimeMillis());
        library.getRequests().add(copy);
        write(user, library);
        return summary(copy);
    }

    public synchronized void deleteRequest(String user, String id) {
        Library library = read(user);
        library.getRequests().remove(findRequest(library, id));
        write(user, library);
    }

    public synchronized Folder createFolder(String user, String parentId, String rawName) {
        Library library = read(user);
        String parent = blankToNull(parentId);
        requireFolderOrRoot(library, parent);
        if (library.getFolders().size() >= MAX_FOLDERS) {
            throw new IllegalArgumentException("每个账号最多创建 " + MAX_FOLDERS + " 个文件夹");
        }
        if (parent != null && depth(library, parent) >= MAX_FOLDER_DEPTH) {
            throw new IllegalArgumentException("文件夹最多 " + MAX_FOLDER_DEPTH + " 层");
        }
        String name = folderName(rawName);
        requireUniqueFolderName(library, parent, name, null);
        Folder folder = new Folder();
        folder.setId(UUID.randomUUID().toString());
        folder.setName(name);
        folder.setParentId(parent);
        folder.setUpdatedAt(System.currentTimeMillis());
        library.getFolders().add(folder);
        write(user, library);
        return folder;
    }

    public synchronized Folder renameFolder(String user, String id, String rawName) {
        Library library = read(user);
        Folder folder = findFolder(library, id);
        String name = folderName(rawName);
        requireUniqueFolderName(library, folder.getParentId(), name, id);
        folder.setName(name);
        folder.setUpdatedAt(System.currentTimeMillis());
        write(user, library);
        return folder;
    }

    public synchronized Folder moveFolder(String user, String id, String parentId) {
        Library library = read(user);
        Folder folder = findFolder(library, id);
        String parent = blankToNull(parentId);
        requireFolderOrRoot(library, parent);
        if (parent != null && (parent.equals(id) || descendantIds(library, id).contains(parent))) {
            throw new IllegalArgumentException("不能把文件夹移动到它自己或它的子文件夹中");
        }
        int depthAfterMove = (parent == null ? 0 : depth(library, parent)) + height(library, id);
        if (depthAfterMove > MAX_FOLDER_DEPTH) {
            throw new IllegalArgumentException("移动后超过 " + MAX_FOLDER_DEPTH + " 层文件夹");
        }
        requireUniqueFolderName(library, parent, folder.getName(), id);
        folder.setParentId(parent);
        folder.setUpdatedAt(System.currentTimeMillis());
        write(user, library);
        return folder;
    }

    /** 删除文件夹及其所有子文件夹和接口，返回删除的数量。 */
    public synchronized Map<String, Integer> deleteFolder(String user, String id) {
        Library library = read(user);
        findFolder(library, id);
        Set<String> removed = descendantIds(library, id);
        removed.add(id);
        int before = library.getRequests().size();
        library.getRequests().removeIf(r -> r.getFolderId() != null && removed.contains(r.getFolderId()));
        library.getFolders().removeIf(f -> removed.contains(f.getId()));
        write(user, library);
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put("folders", removed.size());
        result.put("requests", before - library.getRequests().size());
        return result;
    }

    // ---------------------------------------------------------------- 校验

    private ApiRequest validated(ApiRequest input) {
        ApiRequest clean = new ApiRequest();
        String method = input.getMethod() == null ? "" : input.getMethod().trim().toUpperCase(Locale.ROOT);
        if (!METHODS.contains(method)) {
            throw new IllegalArgumentException("不支持的请求方法: " + input.getMethod());
        }
        String url = input.getUrl() == null ? "" : input.getUrl().trim();
        if (url.isEmpty()) {
            throw new IllegalArgumentException("请求地址不能为空");
        }
        if (url.length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException("请求地址不能超过 " + MAX_URL_LENGTH + " 个字符");
        }
        String description = input.getDescription() == null ? null : input.getDescription().trim();
        if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException("备注不能超过 " + MAX_DESCRIPTION_LENGTH + " 个字符");
        }
        clean.setMethod(method);
        clean.setUrl(url);
        clean.setDescription(description == null || description.isEmpty() ? null : description);
        clean.setParams(pairs(input.getParams(), "参数"));
        clean.setHeaders(pairs(input.getHeaders(), "请求头"));
        clean.setBody(input.getBody() == null ? "" : input.getBody());
        long bytes = utf8(clean.getUrl()) + utf8(clean.getBody()) + utf8(clean.getDescription())
                + pairBytes(clean.getParams()) + pairBytes(clean.getHeaders());
        if (bytes > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("单个接口（地址、参数、请求头、请求体合计）不能超过 1 MB");
        }
        return clean;
    }

    private static List<KeyValue> pairs(List<KeyValue> input, String what) {
        List<KeyValue> result = new ArrayList<>();
        if (input == null) {
            return result;
        }
        for (KeyValue pair : input) {
            if (pair == null) {
                continue;
            }
            KeyValue clean = new KeyValue();
            clean.setKey(pair.getKey() == null ? "" : pair.getKey());
            clean.setValue(pair.getValue() == null ? "" : pair.getValue());
            // 编辑器里的空白行不保存
            if (!clean.getKey().trim().isEmpty() || !clean.getValue().isEmpty()) {
                result.add(clean);
            }
        }
        if (result.size() > MAX_PAIRS) {
            throw new IllegalArgumentException(what + "最多 " + MAX_PAIRS + " 个");
        }
        return result;
    }

    private static long pairBytes(List<KeyValue> pairs) {
        long total = 0;
        for (KeyValue pair : pairs) {
            total += utf8(pair.getKey()) + utf8(pair.getValue());
        }
        return total;
    }

    private static long utf8(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String requestName(String raw) {
        return name(raw, MAX_NAME_LENGTH, "接口名称");
    }

    private static String folderName(String raw) {
        return name(raw, MAX_FOLDER_NAME_LENGTH, "文件夹名称");
    }

    private static String name(String raw, int max, String what) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty() || name.length() > max) {
            throw new IllegalArgumentException(what + "须为 1–" + max + " 个字符");
        }
        if (name.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(what + "不能包含换行等控制字符");
        }
        return name;
    }

    private void requireUniqueRequestName(Library library, String folderId, String name, String selfId) {
        if (nameTaken(library, folderId, name, selfId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该文件夹中已有同名接口“" + name + "”");
        }
    }

    private static boolean nameTaken(Library library, String folderId, String name, String selfId) {
        return library.getRequests().stream().anyMatch(r -> Objects.equals(folderId, r.getFolderId())
                && name.equalsIgnoreCase(r.getName()) && !r.getId().equals(selfId));
    }

    private void requireUniqueFolderName(Library library, String parentId, String name, String selfId) {
        boolean taken = library.getFolders().stream().anyMatch(f -> Objects.equals(parentId, f.getParentId())
                && name.equalsIgnoreCase(f.getName()) && !f.getId().equals(selfId));
        if (taken) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该位置已有同名文件夹“" + name + "”");
        }
    }

    private void requireFolderOrRoot(Library library, String folderId) {
        if (folderId != null) {
            findFolder(library, folderId);
        }
    }

    /** 从顶层算起第几层（顶层文件夹为 1）。 */
    private static int depth(Library library, String folderId) {
        Map<String, Folder> byId = library.getFolders().stream().collect(Collectors.toMap(Folder::getId, f -> f));
        int depth = 0;
        Set<String> seen = new HashSet<>();
        for (String id = folderId; id != null && seen.add(id); id = byId.containsKey(id) ? byId.get(id).getParentId() : null) {
            depth++;
        }
        return depth;
    }

    /** 包含自身在内的子树层数。 */
    private static int height(Library library, String folderId) {
        int max = 0;
        for (Folder child : library.getFolders()) {
            if (folderId.equals(child.getParentId())) {
                max = Math.max(max, height(library, child.getId()));
            }
        }
        return max + 1;
    }

    private static Set<String> descendantIds(Library library, String folderId) {
        Set<String> result = new HashSet<>();
        List<String> queue = new ArrayList<>(Collections.singletonList(folderId));
        while (!queue.isEmpty()) {
            String current = queue.remove(queue.size() - 1);
            for (Folder folder : library.getFolders()) {
                if (current.equals(folder.getParentId()) && result.add(folder.getId())) {
                    queue.add(folder.getId());
                }
            }
        }
        return result;
    }

    private ApiRequest summary(ApiRequest request) {
        ApiRequest summary = new ApiRequest();
        summary.setId(request.getId());
        summary.setFolderId(request.getFolderId());
        summary.setName(request.getName());
        summary.setDescription(request.getDescription());
        summary.setMethod(request.getMethod());
        summary.setUrl(request.getUrl());
        summary.setUpdatedAt(request.getUpdatedAt());
        return summary;
    }

    private static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private ApiRequest findRequest(Library library, String id) {
        return library.getRequests().stream().filter(r -> r.getId().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "接口不存在或无权访问"));
    }

    private Folder findFolder(Library library, String id) {
        return library.getFolders().stream().filter(f -> f.getId().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "文件夹不存在或无权访问"));
    }

    // ---------------------------------------------------------------- 存储

    private Path file(String user) {
        if (user == null || user.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录后管理个人接口");
        }
        // 账号经 SHA-256 摘要后作为文件名，不会成为路径的一部分
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(user.getBytes(StandardCharsets.UTF_8));
            return directory.resolve(Base64.getUrlEncoder().withoutPadding().encodeToString(digest) + ".json");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Library read(String user) {
        Path file = file(user);
        if (!Files.exists(file)) {
            return new Library();
        }
        try {
            Library library = mapper.readValue(file.toFile(), Library.class);
            if (library == null || library.getFolders() == null || library.getRequests() == null
                    || library.getFolders().stream().anyMatch(f -> f == null || f.getId() == null || f.getName() == null)
                    || library.getRequests().stream().anyMatch(r -> r == null || r.getId() == null || r.getName() == null)) {
                throw new IOException("Invalid API library");
            }
            return library;
        } catch (IOException e) {
            throw new IllegalStateException("读取个人接口库失败，请联系管理员检查存储文件；原文件已保留", e);
        }
    }

    private void write(String user, Library library) {
        Path file = file(user);
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, "saved-api-", ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), library);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("保存个人接口库失败，请联系管理员检查存储目录权限", e);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
            }
        }
    }
}
