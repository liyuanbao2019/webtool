package com.li.jc.webtool.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.li.jc.webtool.config.ServerConfig;
import com.li.jc.webtool.service.CommonWebsiteService;
import com.li.jc.webtool.model.CommonWebsite;
import com.li.jc.webtool.model.ServerGroup;
import com.li.jc.webtool.model.ServerGroupDTO;
import com.li.jc.webtool.model.ServerInfo;
import com.li.jc.webtool.model.ServerInfoDTO;
import com.li.jc.webtool.config.ExcelServerConfigLoader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import javax.validation.Valid;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/server")
public class ServerController {

    @Autowired
    private ServerConfig serverConfig;

    @Autowired
    private CommonWebsiteService commonWebsiteService;

    @Autowired(required = false)
    private ExcelServerConfigLoader excelServerConfigLoader;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @ModelAttribute
    public void disableCaching(javax.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public java.util.Map<String, String> configurationError(IllegalStateException exception) {
        return Collections.singletonMap("message", exception.getMessage());
    }

    private void assignIdIfAbsent(ServerInfo server) {
        if (server.getId() == null) {
            server.setId(UUID.randomUUID().toString());
        }
    }

    @GetMapping("/list")
    public synchronized List<ServerGroupDTO> list() {
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
        return commonWebsiteService.visibleWebsites();
    }

    @GetMapping("/groups")
    public synchronized List<String> listGroups() {
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
    public synchronized ServerInfo getById(@PathVariable String idOrHost) {
        List<ServerInfo> servers = allServers();
        for (ServerInfo server : servers) {
            if (idOrHost.equals(server.getId())) return server;
        }
        for (ServerInfo server : servers) {
            if (idOrHost.equals(server.getHost())) return server;
        }
        return null;
    }

    @PostMapping("/reload")
    public synchronized void reload() {
        if (excelServerConfigLoader != null) excelServerConfigLoader.reload();
    }

    @PostMapping("/add")
    public synchronized ServerInfoDTO add(@Valid @RequestBody ServerInfo serverInfo) {
        serverInfo.setId(UUID.randomUUID().toString());
        return saveChange(serverInfo, null);
    }

    @PutMapping("/{id}")
    public synchronized ServerInfoDTO update(@PathVariable String id, @Valid @RequestBody ServerInfo updated) {
        requireServer(id);
        updated.setId(id);
        return saveChange(updated, id);
    }

    private ServerInfoDTO saveChange(ServerInfo server, String replacedId) {
        if (isBlank(server.getGroupName())) {
            server.setGroupName(serverConfig.getDefaultGroupName() != null
                    ? serverConfig.getDefaultGroupName() : "Default Group");
        }
        List<ServerGroup> groups = copyGroupsWithout(replacedId);
        ServerGroup target = groups.stream().filter(g -> server.getGroupName().equals(g.getName()))
                .findFirst().orElse(null);
        if (target == null) {
            target = new ServerGroup();
            target.setName(server.getGroupName());
            target.setServers(new ArrayList<>());
            groups.add(target);
        }
        target.getServers().add(server);
        publishConfiguration(groups, copyUngroupedWithout(replacedId));
        return toDTO(server);
    }

    @DeleteMapping("/{id}")
    public synchronized void delete(@PathVariable String id) {
        requireServer(id);
        publishConfiguration(copyGroupsWithout(id), copyUngroupedWithout(id));
    }

    private void requireServer(String id) {
        if (allServers().stream().noneMatch(s -> id.equals(s.getId()))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Server not found; refresh the server list");
        }
    }

    private List<ServerGroup> copyGroupsWithout(String id) {
        List<ServerGroup> groups = new ArrayList<>();
        if (serverConfig.getServerGroups() != null) {
            for (ServerGroup source : serverConfig.getServerGroups()) {
                ServerGroup copy = new ServerGroup();
                copy.setName(source.getName());
                copy.setServers(source.getServers() == null ? new ArrayList<>() : source.getServers().stream()
                        .filter(s -> id == null || !id.equals(s.getId())).collect(Collectors.toList()));
                if (!copy.getServers().isEmpty()) groups.add(copy);
            }
        }
        return groups;
    }

    private List<ServerInfo> copyUngroupedWithout(String id) {
        return serverConfig.getServers() == null ? new ArrayList<>() : serverConfig.getServers().stream()
                .filter(s -> id == null || !id.equals(s.getId())).collect(Collectors.toList());
    }

    private void publishConfiguration(List<ServerGroup> groups, List<ServerInfo> ungrouped) {
        // Persist the candidate before publishing. A failed save leaves the live configuration intact.
        if (excelServerConfigLoader != null) excelServerConfigLoader.persistServers(groups, ungrouped);
        serverConfig.setServerGroups(groups);
        serverConfig.setServers(ungrouped);
    }

    private List<ServerInfo> allServers() {
        List<ServerInfo> servers = copyUngroupedWithout(null);
        if (serverConfig.getServerGroups() != null) {
            for (ServerGroup group : serverConfig.getServerGroups()) {
                if (group.getServers() != null) servers.addAll(group.getServers());
            }
        }
        servers.forEach(this::assignIdIfAbsent);
        return servers;
    }

    private ServerInfoDTO toDTO(ServerInfo serverInfo) {
        assignIdIfAbsent(serverInfo);
        try {
            String json = objectMapper.writeValueAsString(withAgentDefaults(serverInfo));
            String encrypted = com.li.jc.webtool.util.CryptoUtil.encrypt(json);

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
