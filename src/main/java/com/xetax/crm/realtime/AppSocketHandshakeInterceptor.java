package com.xetax.crm.realtime;

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
 * Gate on the panel's live socket.
 *
 * <p>The credential is the same access token the REST calls carry, passed as
 * a query parameter because a browser cannot set headers on a WebSocket
 * upgrade. It is checked exactly the way JwtAuthenticationFilter checks it —
 * signature, an "access" (never refresh) token, our own issuer, and a user
 * who still exists — so the socket can never be a softer way in than the API.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AppSocketHandshakeInterceptor implements HandshakeInterceptor {

    static final String ATTR_USER_ID = "uid";
    static final String ATTR_OWNER_ID = "oid";

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
            var user = userCacheService.findById(userId).orElse(null);
            if (user == null) return false;

            attributes.put(ATTR_USER_ID, userId.toString());
            attributes.put(ATTR_OWNER_ID, dataOwnerId(user.getParentId(), userId));
            return true;
        } catch (Exception e) {
            log.debug("Realtime handshake rejected: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Team members read their owner's workspace, so workspace events are
     * addressed to the owner's id. Mirrors CurrentUserProvider's rule, where
     * a blank or "#" parent means the user owns their own data.
     */
    private static String dataOwnerId(String parentId, UUID ownId) {
        if (parentId == null || parentId.isBlank() || "#".equals(parentId)) return ownId.toString();
        try {
            return UUID.fromString(parentId).toString();
        } catch (IllegalArgumentException ignored) {
            return ownId.toString();
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}
