package com.li.jc.webtool.agent;

public interface AgentTerminalCallback {
    void onOutput(byte[] data);

    void onError(String message);

    void onClosed(String reason);
}
