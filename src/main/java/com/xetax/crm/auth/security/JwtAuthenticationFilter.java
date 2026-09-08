package com.xetax.crm.auth.security;

import com.xetax.crm.auth.user.AuthUserHelper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Access-token verification for the whole application.
 *
 * <p>Before the merge this validation lived in the API gateway
 * (JwtAuthenticationFilter there checked signature, issuer, expiry and
 * {@code typ=access} before forwarding to any service). With auth folded into
 * this application, the same checks run here: a valid Bearer access token
 * authenticates the request; anything else leaves the context empty and the
 * security chain answers 401 for protected endpoints.
 */
@Component
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Autowired
    JWTService jwtService;
    /* Redis-backed with a 60s TTL — repeat requests stop hitting MySQL for
       the token's user; falls back to the DB when Redis is unavailable. */
    @Autowired
    UserCacheService userCacheService;

    /*
     * Paths the gateway's RouteValidator served without a token, plus the
     * OAuth2 handshake and API docs. Kept in sync with SecurityConfig's
     * permitAll list.
     */
    private static final List<String> SKIP_PREFIXES = List.of(
            "/auth/v1/",
            "/oauth2/",
            "/login/oauth2/",
            "/api/public/integrations/",
            "/api/public/whatsapp/",
            "/api/public/meetings/",
            "/api/public/agents/",
            "/ws/",
            "/actuator",
            "/v3/api-docs",
            "/swagger-ui"
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                Claims payload = jwtService.parse(token).getPayload();
                // Refresh tokens must not open API endpoints — access only.
                if ("access".equals(payload.get("typ"))
                        && jwtService.getIssuer().equals(payload.getIssuer())) {
                    UUID userUuid = AuthUserHelper.parseUUID(payload.getSubject());
                    var userOpt = userCacheService.findById(userUuid);
                    if (userOpt.isPresent()) {
                        var user = userOpt.get();
                        UsernamePasswordAuthenticationToken auth =
                                new UsernamePasswordAuthenticationToken(user, null, List.of());
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    }
                }
            } catch (Exception e) {
                log.warn("Invalid JWT token: {}", e.getMessage());
            }
        }
        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
        if (request.getDispatcherType().name().equals("ERROR")) {
            return true;
        }
        String path = request.getRequestURI();
        if (path.equals("/auth/api/v1/users/register")) {
            return true;
        }
        return SKIP_PREFIXES.stream().anyMatch(path::startsWith);
    }
}
