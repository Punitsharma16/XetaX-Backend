package com.xetax.crm.auth.security;

import com.xetax.crm.auth.token.AuthTokenRepository;
import com.xetax.crm.auth.token.entity.LoginProvider;
import com.xetax.crm.auth.token.entity.RefreshToken;
import com.xetax.crm.auth.user.AuthUserEntity;
import com.xetax.crm.auth.user.AuthUserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

@Component
public class Oauth2SuccessHandler implements AuthenticationSuccessHandler {
    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    @Autowired
    AuthUserRepository userRepository;
    @Autowired
    JWTService jwtService;
    @Autowired
    CookieService cookieService;
    @Autowired
    AuthTokenRepository refreshTokenRepository;

    /** Where the SPA lives — the browser is sent back here with the tokens. */
    @Value("${app.public-base-url:http://localhost:5000}")
    String publicBaseUrl;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, FilterChain chain,
                                        Authentication authentication) throws IOException, ServletException {
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        logger.info("Successful Authentication");
        OAuth2User user = (OAuth2User) authentication.getPrincipal();
        String registrationId = "unknown";
        if (authentication instanceof OAuth2AuthenticationToken token) {
            registrationId = token.getAuthorizedClientRegistrationId();
        }
        logger.info("User is : {}", user.getAttributes());
        AuthUserEntity user1;
        switch (registrationId) {
            case "google":
                String email = user.getAttributes().getOrDefault("email", "").toString();
                String name = user.getAttributes().getOrDefault("name", "").toString();
                String picture = user.getAttributes().getOrDefault("picture", "").toString();
                user1 = userRepository.findByEmail(email)
                        .map(existingUser -> {
                            logger.info("User already exists in DB");
                            return existingUser;
                        })
                        .orElseGet(() -> {
                            logger.info("Creating new user");
                            // Google sign-up == self-service register: an enabled
                            // workspace owner (no parent, not system admin). The
                            // random password can never be typed, so the account
                            // is Google-only until the user sets one from Profile.
                            AuthUserEntity newUser = AuthUserEntity.builder()
                                    .name(name == null || name.isBlank() ? email : name)
                                    .email(email)
                                    .image(picture)
                                    .provider(LoginProvider.GOOGLE)
                                    .isEnable(true)
                                    .isAdmin(false)
                                    .parentId(null)
                                    .password(UUID.randomUUID().toString())
                                    .createAt(Instant.now())
                                    .build();
                            return userRepository.save(newUser);
                        });
                break;
            default:
                throw new RuntimeException("Invalid registration id");
        }

        String jti = UUID.randomUUID().toString();
        RefreshToken refreshTokenObj = RefreshToken.builder()
                .jti(jti)
                .user(user1)
                .revoked(false)
                .createAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(jwtService.getRefreshTtlSeconds()))
                .build();
        refreshTokenRepository.save(refreshTokenObj);
        String refreshToken = jwtService.generateRefreshToken(user1, refreshTokenObj.getJti());
        cookieService.attachRefreshCookie(response, refreshToken, (int) jwtService.getRefreshTtlSeconds());

        if (!user1.isEnabled()) {
            response.sendRedirect(publicBaseUrl + "/login?oauth=disabled");
            return;
        }

        // Hand the session to the SPA exactly like /auth/v1/login does — access +
        // refresh token and the user DTO. They travel in the URL *fragment*, which
        // browsers never send to servers (so nothing lands in access logs).
        String accessToken = jwtService.generateAccessToken(user1);
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", user1.getId());
        dto.put("email", user1.getEmail());
        dto.put("name", user1.getName());
        dto.put("image", user1.getImage());
        dto.put("phone", user1.getPhone());
        dto.put("company", user1.getCompany());
        dto.put("parentId", user1.getParentId());
        dto.put("provider", user1.getProvider());
        dto.put("enable", user1.isEnabled());
        dto.put("admin", user1.isAdmin());
        String userJson = new ObjectMapper().writeValueAsString(dto);
        String userB64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(userJson.getBytes(StandardCharsets.UTF_8));

        response.sendRedirect(publicBaseUrl + "/oauth/callback#access_token=" + accessToken
                + "&refresh_token=" + refreshToken + "&user=" + userB64);
    }
}
