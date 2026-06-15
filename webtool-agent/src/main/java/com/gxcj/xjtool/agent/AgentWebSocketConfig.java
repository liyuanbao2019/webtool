package com.gxcj.xjtool.agent;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class AgentWebSocketConfig implements WebSocketConfigurer {
    @Autowired
    private TerminalStreamHandler terminalStreamHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(terminalStreamHandler, "/api/v1/terminal/stream")
                .setAllowedOrigins("*");
    }
}
