package com.li.jc.webtool.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * SSH安全配置
 * 用于配置SSH终端的安全策略，如危险命令二次确认、脚本执行扫描等
 */
@Component
@ConfigurationProperties(prefix = "audit.ssh")
public class SshSecurityConfig {

    /**
     * 是否启用危险命令二次确认
     */
    private boolean dangerousCommandConfirm = true;

    /**
     * 危险命令列表
     */
    private List<String> dangerousCommands = new ArrayList<>();

    /**
     * 是否启用脚本执行安全扫描
     */
    private boolean scriptScanEnabled = true;

    /**
     * 脚本执行触发命令列表（检测这些命令时进行脚本内容扫描）
     */
    private List<String> scriptExecTriggers = new ArrayList<>();

    /**
     * 是否阻断极高危脚本操作（true=阻断并禁止执行，false=仅警告确认）
     */
    private boolean blockCriticalScriptOps = true;

    public boolean isDangerousCommandConfirm() {
        return dangerousCommandConfirm;
    }

    public void setDangerousCommandConfirm(boolean dangerousCommandConfirm) {
        this.dangerousCommandConfirm = dangerousCommandConfirm;
    }

    public List<String> getDangerousCommands() {
        return dangerousCommands;
    }

    public void setDangerousCommands(List<String> dangerousCommands) {
        this.dangerousCommands = dangerousCommands;
    }

    public boolean isScriptScanEnabled() {
        return scriptScanEnabled;
    }

    public void setScriptScanEnabled(boolean scriptScanEnabled) {
        this.scriptScanEnabled = scriptScanEnabled;
    }

    public List<String> getScriptExecTriggers() {
        return scriptExecTriggers;
    }

    public void setScriptExecTriggers(List<String> scriptExecTriggers) {
        this.scriptExecTriggers = scriptExecTriggers;
    }

    public boolean isBlockCriticalScriptOps() {
        return blockCriticalScriptOps;
    }

    public void setBlockCriticalScriptOps(boolean blockCriticalScriptOps) {
        this.blockCriticalScriptOps = blockCriticalScriptOps;
    }
}
