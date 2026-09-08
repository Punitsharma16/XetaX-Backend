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
                            AuthUserEntity newUser = AuthUserEntity.builder()
                                    .name(name)
                                    .email(email)
                                    .image(picture)
                                    .provider(LoginProvider.GOOGLE)
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
        response.getWriter().write("Login Successful");
    }
}
