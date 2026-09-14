package com.xetax.crm.realtime;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registers the panel's live socket. /ws/** is already outside the JWT filter
 * and in SecurityConfig's permitAll list; the handshake interceptor is what
 * actually authenticates the upgrade.
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class AppSocketConfig implements WebSocketConfigurer {

    private final AppSocketHandler appSocketHandler;
    private final AppSocketHandshakeInterceptor handshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(appSocketHandler, "/ws/app")
                .addInterceptors(handshakeInterceptor)
                .setAllowedOriginPatterns("*");
    }
}
