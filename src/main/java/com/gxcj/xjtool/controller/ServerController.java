package com.gxcj.xjtool.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gxcj.xjtool.config.ServerConfig;
import com.gxcj.xjtool.model.CommonWebsite;
import com.gxcj.xjtool.model.ServerGroup;
import com.gxcj.xjtool.model.ServerGroupDTO;
import com.gxcj.xjtool.model.ServerInfo;
import com.gxcj.xjtool.model.ServerInfoDTO;
import com.gxcj.xjtool.config.ExcelServerConfigLoader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import javax.validation.Valid;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/server")
public class ServerController {

    @Autowired
    private ServerConfig serverConfig;

    @Autowired(required = false)
    private ExcelServerConfigLoader excelServerConfigLoader;

    private List<ServerInfo> serverList = new CopyOnWriteArrayList<>();

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @EventListener(ContextRefreshedEvent.class)
    public void init() {
        if (initialized) return;
        initialized = true;
        doLoadServers();
    }

    private boolean initialized = false;

    private void doLoadServers() {
        if (serverConfig.getServerGroups() != null) {
            for (ServerGroup group : serverConfig.getServerGroups()) {
                if (group.getServers() != null) {
                    for (ServerInfo server : group.getServers()) {
                        assignIdIfAbsent(server);
                        serverList.add(server);
                    }
                }
            }
        }
        if (serverConfig.getServers() != null) {
            for (ServerInfo server : serverConfig.getServers()) {
                assignIdIfAbsent(server);
                serverList.add(server);
            }
        }
    }

    private void assignIdIfAbsent(ServerInfo server) {
        if (server.getId() == null) {
            server.setId(UUID.randomUUID().toString());
        }
    }

    @GetMapping("/list")
    public List<ServerGroupDTO> list() {
        List<ServerGroupDTO> result = new ArrayList<>();

        if (serverConfig.getServerGroups() != null) {
            for (ServerGroup group : serverConfig.getServerGroups()) {
                ServerGroupDTO dto = new ServerGroupDTO();
                dto.setName(group.getName());
                dto.setServers(group.getServers().stream()
                        .map(this::toDTO)
                        .collect(Collectors.toList()));
                result.add(dto);
            }
        }

        List<ServerInfoDTO> ungrouped = serverConfig.getServers() == null
                ? Collections.emptyList()
                : serverConfig.getServers().stream()
                        .map(this::toDTO)
                        .collect(Collectors.toList());
        if (!ungrouped.isEmpty()) {
            ServerGroupDTO ungroupedDto = new ServerGroupDTO();
            ungroupedDto.setName(null);
            ungroupedDto.setServers(ungrouped);
            result.add(ungroupedDto);
        }

        return result;
    }

    @GetMapping("/common-websites")
    public List<CommonWebsite> commonWebsites() {
        if (!serverConfig.isCommonWebsitesEnabled()) {
            return Collections.emptyList();
        }
        if (serverConfig.getCommonWebsites() == null) {
            return Collections.emptyList();
        }
        return serverConfig.getCommonWebsites().stream()
                .filter(Objects::nonNull)
                .filter(item -> item.getName() != null && !item.getName().trim().isEmpty())
                .filter(item -> item.getUrl() != null && !item.getUrl().trim().isEmpty())
                .collect(Collectors.toList());
    }

    @GetMapping("/groups")
    public List<String> listGroups() {
        List<String> groups = new ArrayList<>();
        if (serverConfig.getServerGroups() != null) {
            for (ServerGroup g : serverConfig.getServerGroups()) {
                if (g.getName() != null && !g.getName().trim().isEmpty()) {
                    groups.add(g.getName());
                }
            }
        }
        return groups;
    }

    @GetMapping("/{idOrHost}")
    public ServerInfo getById(@PathVariable String idOrHost) {
        // 1. 先从 serverList 中按 ID 或 Host 查找
        for (ServerInfo s : serverList) {
            if (idOrHost.equals(s.getId()) || idOrHost.equals(s.getHost())) {
                return s;
            }
        }
        // 2. fallback: 从 serverConfig 中按 ID 或 Host 查找（覆盖 ExcelServerConfigLoader 后加载的服务器）
        if (serverConfig.getServerGroups() != null) {
            for (ServerGroup group : serverConfig.getServerGroups()) {
                if (group.getServers() != null) {
                    for (ServerInfo s : group.getServers()) {
                        if (idOrHost.equals(s.getId()) || idOrHost.equals(s.getHost())) {
                            return s;
                        }
                    }
                }
            }
        }
        if (serverConfig.getServers() != null) {
            for (ServerInfo s : serverConfig.getServers()) {
                if (idOrHost.equals(s.getId()) || idOrHost.equals(s.getHost())) {
                    return s;
                }
            }
        }
        // 3. 按 host 从 Excel 加载器中查找
        if (excelServerConfigLoader != null) {
            ServerInfo found = excelServerConfigLoader.findServerByHost(idOrHost);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @PostMapping("/reload")
    public void reload() {
        if (excelServerConfigLoader != null) {
            excelServerConfigLoader.reload();
        }
        serverList.clear();
        if (serverConfig.getServerGroups() != null) {
            for (ServerGroup group : serverConfig.getServerGroups()) {
                if (group.getServers() != null) {
                    for (ServerInfo server : group.getServers()) {
                        serverList.add(server);
                    }
                }
            }
        }
        if (serverConfig.getServers() != null) {
            serverList.addAll(serverConfig.getServers());
        }
    }

    @PostMapping("/add")
    public ServerInfoDTO add(@Valid @RequestBody ServerInfo serverInfo) {
        serverInfo.setId(UUID.randomUUID().toString());

        String groupName = serverInfo.getGroupName();
        String defaultGroup = serverConfig.getDefaultGroupName() != null
                ? serverConfig.getDefaultGroupName()
                : "Default Group";
        if (groupName == null || groupName.trim().isEmpty()) {
            groupName = defaultGroup;
            serverInfo.setGroupName(groupName);
        }

        boolean found = false;
        if (serverConfig.getServerGroups() != null) {
            for (ServerGroup g : serverConfig.getServerGroups()) {
                if (groupName.equals(g.getName())) {
                    g.getServers().add(serverInfo);
                    found = true;
                    break;
                }
            }
        }
        if (!found) {
            ServerGroup newGroup = new ServerGroup();
            newGroup.setName(groupName);
            newGroup.setServers(new ArrayList<>());
            newGroup.getServers().add(serverInfo);
            if (serverConfig.getServerGroups() == null) {
                serverConfig.setServerGroups(new ArrayList<>());
            }
            serverConfig.getServerGroups().add(newGroup);
        }

        serverList.add(serverInfo);

        if (excelServerConfigLoader != null) {
            excelServerConfigLoader.saveServer(serverInfo, groupName);
        }

        return toDTO(serverInfo);
    }

    @PutMapping("/{id}")
    public ServerInfoDTO update(@PathVariable String id, @Valid @RequestBody ServerInfo updated) {
        updated.setId(id);

        String groupName = updated.getGroupName();
        String defaultGroup = serverConfig.getDefaultGroupName() != null
                ? serverConfig.getDefaultGroupName()
                : "Default Group";
        if (groupName == null || groupName.trim().isEmpty()) {
            groupName = defaultGroup;
            updated.setGroupName(groupName);
        }

        // 1. 从旧的分组或无分组列表中移除该服务器
        boolean removed = false;
        if (serverConfig.getServerGroups() != null) {
            for (ServerGroup g : serverConfig.getServerGroups()) {
                if (g.getServers() != null) {
                    for (int i = 0; i < g.getServers().size(); i++) {
                        if (id.equals(g.getServers().get(i).getId())) {
                            g.getServers().remove(i);
                            removed = true;
                            break;
                        }
                    }
                }
                if (removed) break;
            }
        }
        if (!removed && serverConfig.getServers() != null) {
            for (int i = 0; i < serverConfig.getServers().size(); i++) {
                if (id.equals(serverConfig.getServers().get(i).getId())) {
                    serverConfig.getServers().remove(i);
                    break;
                }
            }
        }

        // 2. 将更新后的服务器对象加入到目标新分组中
        boolean groupFound = false;
        if (serverConfig.getServerGroups() != null) {
            for (ServerGroup g : serverConfig.getServerGroups()) {
                if (groupName.equals(g.getName())) {
                    if (g.getServers() == null) {
                        g.setServers(new ArrayList<>());
                    }
                    g.getServers().add(updated);
                    groupFound = true;
                    break;
                }
            }
        }
        if (!groupFound) {
            ServerGroup newGroup = new ServerGroup();
            newGroup.setName(groupName);
            newGroup.setServers(new ArrayList<>());
            newGroup.getServers().add(updated);
            if (serverConfig.getServerGroups() == null) {
                serverConfig.setServerGroups(new ArrayList<>());
            }
            serverConfig.getServerGroups().add(newGroup);
        }

        // 3. 清理掉已被移空的分组
        if (serverConfig.getServerGroups() != null) {
            serverConfig.getServerGroups().removeIf(g -> g.getServers() == null || g.getServers().isEmpty());
        }

        // 4. 更新单层集合 serverList
        for (int i = 0; i < serverList.size(); i++) {
            if (id.equals(serverList.get(i).getId())) {
                serverList.set(i, updated);
                break;
            }
        }

        // 5. 保存更新至 Excel
        if (excelServerConfigLoader != null) {
            excelServerConfigLoader.updateServer(updated);
        }

        return toDTO(updated);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) {
        ServerInfo existing = findById(id);
        if (existing == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Server not found");
        }

        boolean removed = removeServerFromConfig(id);
        if (!removed) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Server not found");
        }

        serverList.removeIf(s -> id.equals(s.getId()));

        if (serverConfig.getServerGroups() != null) {
            serverConfig.getServerGroups().removeIf(g -> g.getServers() == null || g.getServers().isEmpty());
        }

        if (excelServerConfigLoader != null) {
            excelServerConfigLoader.deleteServer(id);
        }
    }

    private ServerInfo findById(String id) {
        if (id == null || id.trim().isEmpty()) {
            return null;
        }
        for (ServerInfo s : serverList) {
            if (id.equals(s.getId())) {
                return s;
            }
        }
        if (serverConfig.getServerGroups() != null) {
            for (ServerGroup group : serverConfig.getServerGroups()) {
                if (group.getServers() != null) {
                    for (ServerInfo s : group.getServers()) {
                        if (id.equals(s.getId())) {
                            return s;
                        }
                    }
                }
            }
        }
        if (serverConfig.getServers() != null) {
            for (ServerInfo s : serverConfig.getServers()) {
                if (id.equals(s.getId())) {
                    return s;
                }
            }
        }
        return null;
    }

    private boolean removeServerFromConfig(String id) {
        boolean removed = false;
        if (serverConfig.getServerGroups() != null) {
            for (ServerGroup g : serverConfig.getServerGroups()) {
                if (g.getServers() != null) {
                    removed = g.getServers().removeIf(s -> id.equals(s.getId())) || removed;
                }
            }
        }
        if (serverConfig.getServers() != null) {
            removed = serverConfig.getServers().removeIf(s -> id.equals(s.getId())) || removed;
        }
        return removed;
    }

    private ServerInfoDTO toDTO(ServerInfo serverInfo) {
        try {
            String json = objectMapper.writeValueAsString(withAgentDefaults(serverInfo));
            String encrypted = com.gxcj.xjtool.util.CryptoUtil.encrypt(json);

            ServerInfoDTO dto = new ServerInfoDTO();
            dto.setEncryptedData(encrypted);
            return dto;
        } catch (Exception e) {
            throw new RuntimeException("服务器信息序列化失败", e);
        }
    }

    private ServerInfo withAgentDefaults(ServerInfo source) {
        if (source == null) {
            return null;
        }
        ServerInfo copy = objectMapper.convertValue(source, ServerInfo.class);
        ServerConfig.AgentConfig agent = serverConfig.getAgent();
        if (agent == null || !agent.isEnabled()) {
            return copy;
        }

        copy.setConnectionMode("agent");
        copy.setAgentBaseUrl(buildAgentBaseUrl(copy.getHost(), agent.getPort()));
        copy.setAgentId(copy.getHost());
        copy.setAgentToken(null);
        return copy;
    }

    private String buildAgentBaseUrl(String host, int port) {
        if (isBlank(host)) {
            return null;
        }
        int resolvedPort = port > 0 ? port : 18080;
        return "http://" + host.trim() + ":" + resolvedPort;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

}
