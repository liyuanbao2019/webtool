package com.li.jc.webtool.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Result of deploying to one server node. */
@Data
public class NodeDeployResult {
    private String serverId;
    private String name;
    private String host;
    private String status;
    private boolean success;
    private String message;
    private String uploadStatus;
    private String targetFile;
    private Integer commandExitCode;
    private long durationMs;
    private List<String> logs;

    public void fail(String error) {
        this.success = false;
        this.message = error;
        if (this.logs == null) {
            this.logs = new ArrayList<>();
        }
        this.logs.add("[失败] " + error);
    }
}
