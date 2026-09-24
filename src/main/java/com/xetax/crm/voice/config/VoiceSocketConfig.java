package com.xetax.crm.voice.config;

import com.xetax.crm.voice.VoiceProperties;
import com.xetax.crm.voice.socket.VoiceSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;

/**
 * Registers /ws/voice.
 *
 * <p>/ws/** is already outside the JWT filter and permitted in SecurityConfig;
 * the handshake interceptor is what actually authenticates the upgrade.
 */
@Configuration
@EnableWebSocket
@EnableConfigurationProperties(VoiceProperties.class)
@ConditionalOnProperty(name = "voice.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class VoiceSocketConfig implements WebSocketConfigurer {

    private final VoiceSocketHandler voiceSocketHandler;
    private final VoiceHandshakeInterceptor handshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(voiceSocketHandler, "/ws/voice")
                .addInterceptors(handshakeInterceptor)
                .setAllowedOriginPatterns("*");
    }

    /**
     * Audio frames are far larger than the 8 KB the container allows by
     * default — without this every chunk of speech would close the socket.
     *
     * <p>Set on the server as it is built rather than through a
     * ServletServerContainerFactoryBean: that bean demands a live
     * ServerContainer, which a @SpringBootTest has no reason to start, and
     * asking for one broke every context test in the application.
     */
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> voiceWebSocketBuffers() {
        return factory -> factory.addInitializers(servletContext -> {
            servletContext.setInitParameter(
                    "org.apache.tomcat.websocket.binaryBufferSize", String.valueOf(512 * 1024));
            servletContext.setInitParameter(
                    "org.apache.tomcat.websocket.textBufferSize", String.valueOf(64 * 1024));
        });
    }
}
