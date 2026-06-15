package com.gxcj.xjtool.agent;

public interface AgentTerminalCallback {
    void onOutput(byte[] data);

    void onError(String message);

    void onClosed(String reason);
}
