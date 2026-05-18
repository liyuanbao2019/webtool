package com.gxcj.xjtool.config;

import com.gxcj.xjtool.websocket.PressureWebSocketHandler;
import com.gxcj.xjtool.websocket.WebSshWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Autowired
    private WebSshWebSocketHandler webSshWebSocketHandler;

    @Autowired
    private PressureWebSocketHandler pressureWebSocketHandler;

    @Autowired
    private WebSocketAuthInterceptor webSocketAuthInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // WebSSH 终端
        registry.addHandler(webSshWebSocketHandler, "/webssh")
                .addInterceptors(webSocketAuthInterceptor)
                .setAllowedOrigins("*");
        
        // 压测进度推送
        registry.addHandler(pressureWebSocketHandler, "/ws/pressure")
                .addInterceptors(webSocketAuthInterceptor)
                .setAllowedOrigins("*");
    }
}
