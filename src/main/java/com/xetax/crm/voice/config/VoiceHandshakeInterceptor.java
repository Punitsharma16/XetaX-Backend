package com.xetax.crm.voice.config;

import com.xetax.crm.auth.security.JWTService;
import com.xetax.crm.auth.security.UserCacheService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;
import java.util.UUID;

/**
 * Gate on the voice socket.
 *
 * <p>Same checks as the panel's live socket, and for the same reason: a
 * microphone that can reach every CRM tool must not be an easier way in than
 * the REST API. The token is read from the query string because neither a
 * browser nor React Native can set headers on a WebSocket upgrade.
 *
 * <p>Only the user's id is put on the session. The acting identity is rebuilt
 * from the user store on every turn, so a user disabled mid-conversation stops
 * being able to do anything.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VoiceHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_USER_ID = "voice.uid";

    private final JWTService jwtService;
    private final UserCacheService userCacheService;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = UriComponentsBuilder.fromUri(request.getURI()).build()
                .getQueryParams().getFirst("token");
        if (token == null || token.isBlank()) return false;

        try {
            Claims payload = jwtService.parse(token).getPayload();
            if (!"access".equals(payload.get("typ"))
                    || !jwtService.getIssuer().equals(payload.getIssuer())) {
                return false;
            }
            UUID userId = UUID.fromString(payload.getSubject());
            if (userCacheService.findById(userId).isEmpty()) return false;

            attributes.put(ATTR_USER_ID, userId.toString());
            return true;
        } catch (Exception e) {
            log.debug("Voice handshake rejected: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}
