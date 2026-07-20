package com.li.jc.webtool.agent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {
    private String id = "local-agent";
    private String token = "change-me";
    private Shell shell = new Shell();
    private Command command = new Command();
    private Security security = new Security();

    @Data
    public static class Shell {
        private String linux = "/bin/bash,-i";
        private String windows = "cmd.exe";
    }

    @Data
    public static class Command {
        private int maxSessions = 20;
        private int idleTimeoutSeconds = 1800;
    }

    @Data
    public static class Security {
        private boolean requireToken = true;
        private List<String> allowedClients = new ArrayList<>();
    }
}
