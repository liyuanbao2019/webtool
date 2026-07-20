package com.li.jc.webtool.config;

import com.li.jc.webtool.model.CommonWebsite;
import com.li.jc.webtool.model.ServerGroup;
import com.li.jc.webtool.model.ServerInfo;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "app")
public class ServerConfig {
    private List<ServerInfo> servers = new ArrayList<>();
    private List<ServerGroup> serverGroups = new ArrayList<>();
    private AgentConfig agent = new AgentConfig();
    private boolean commonWebsitesEnabled = true;
    private List<CommonWebsite> commonWebsites = new ArrayList<>();
    private String serversConfigFile;
    private Map<String, String> passwordOverrides = new java.util.HashMap<>();
    private String commonPassword;
    private String defaultGroupName = "Default Group";

    @Data
    public static class AgentConfig {
        private boolean enabled = false;
        private int port = 18080;
        private String token;
    }
}
