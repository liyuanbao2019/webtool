package com.gxcj.xjtool.config;

import com.gxcj.xjtool.model.CommonWebsite;
import com.gxcj.xjtool.model.ServerGroup;
import com.gxcj.xjtool.model.ServerInfo;
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
    private boolean commonWebsitesEnabled = true;
    private List<CommonWebsite> commonWebsites = new ArrayList<>();
    private String serversConfigFile;
    private Map<String, String> passwordOverrides = new java.util.HashMap<>();
    private String commonPassword;
    private String defaultGroupName = "Default Group";
}
