package com.xetax.crm.auth.token;

import com.xetax.crm.auth.security.CookieService;
import com.xetax.crm.auth.security.JWTService;
import com.xetax.crm.auth.token.dto.LoginRequest;
import com.xetax.crm.auth.token.dto.RefreshTokenReq;
import com.xetax.crm.auth.token.dto.TokenResponse;
import com.xetax.crm.auth.token.entity.RefreshToken;
import com.xetax.crm.auth.user.AuthUserDto;
import com.xetax.crm.auth.user.AuthUserEntity;
import com.xetax.crm.auth.user.AuthUserRepository;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.modelmapper.ModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

/**
 * Login / refresh / logout — same routes and same response shapes the
 * standalone auth service exposed (raw TokenResponse, no ApiResponse
 * envelope), so the UI keeps working with only a base-URL change.
 */
@RestController
@RequestMapping("/auth/v1")
public class AuthTokenController {
    private static final Logger logger = LoggerFactory.getLogger(AuthTokenController.class);

    private final AuthUserRepository userRepository;
    private final JWTService jwtService;
    private final ModelMapper mapper;
    private final AuthenticationManager authenticationManager;
    private final AuthTokenRepository refreshTokenRepository;
    private final CookieService cookieService;

    @org.springframework.beans.factory.annotation.Autowired
    private com.xetax.crm.common.ratelimit.RateLimiterService rateLimiter;

    public AuthTokenController(
            AuthUserRepository userRepository,
            JWTService jwtService,
            ModelMapper mapper,
            AuthenticationManager authenticationManager,
            AuthTokenRepository refreshTokenRepository,
            CookieService cookieService
    ) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.mapper = mapper;
        this.authenticationManager = authenticationManager;
        this.refreshTokenRepository = refreshTokenRepository;
        this.cookieService = cookieService;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest loginRequest, HttpServletResponse response,
                                   HttpServletRequest servletRequest) {
        // Brute-force guard: 10 attempts per IP per minute.
        rateLimiter.check("login:" + servletRequest.getRemoteAddr(), 10, java.time.Duration.ofMinutes(1));
        logger.info("Login request received for email: {}", loginRequest.email());

        authenticate(loginRequest);
        AuthUserEntity user = userRepository.findByEmail(loginRequest.email())
                .orElseThrow(() -> new BadCredentialsException("Invalid Username"));
        if (!user.isEnabled()) {
            logger.warn("User is disabled: {}", user.getEmail());
            throw new DisabledException("User is disabled");
        }

        String jti = UUID.randomUUID().toString();
        var refreshTokenOb = RefreshToken.builder()
                .jti(jti).user(user).createAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(jwtService.getRefreshTtlSeconds()))
                .revoked(false).build();
        refreshTokenRepository.save(refreshTokenOb);

        String accesstoken = jwtService.generateAccessToken(user);
        String refreshToken = jwtService.generateRefreshToken(user, refreshTokenOb.getJti());

        TokenResponse tokenResponse = TokenResponse.of(accesstoken, refreshToken,
                jwtService.getAccessTtlSeconds(), mapper.map(user, AuthUserDto.class));
        return ResponseEntity.ok(tokenResponse);
    }

    private Authentication authenticate(LoginRequest loginRequest) {
        try {
            return authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            loginRequest.email(),
                            loginRequest.password()
                    )
            );
        } catch (AuthenticationException e) {
            logger.error("Authentication failed", e);
            throw e;
        }
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refreshToken(@RequestBody(required = false) RefreshTokenReq body,
                                                      HttpServletResponse response, HttpServletRequest request) {
        String refreshToken = readRefreshTokenFromRequest(body, request)
                .orElseThrow(() -> new BadCredentialsException("Invalid Refresh Token"));
        if (!jwtService.isRefreshToken(refreshToken)) {
            throw new BadCredentialsException("Invalid Refresh Token type");
        }
        String jti = jwtService.getJti(refreshToken);
        UUID userId = jwtService.getUserId(refreshToken);
        RefreshToken storedRefreshToken = refreshTokenRepository.findByJti(jti)
                .orElseThrow(() -> new BadCredentialsException("Invalid Refresh Token "));
        if (storedRefreshToken.isRevoked()) {
            throw new BadCredentialsException("Token already revoked");
        }
        if (storedRefreshToken.getExpiresAt().isBefore(Instant.now())) {
            throw new BadCredentialsException("Referesh Token is expired");
        }
        if (!storedRefreshToken.getUser().getId().equals(userId)) {
            throw new BadCredentialsException("Refresh token does not belong to this user");
        }

        // rotate: revoke the old token, issue a fresh pair
        storedRefreshToken.setRevoked(true);
        String newJti = UUID.randomUUID().toString();
        storedRefreshToken.setReplacedByToken(newJti);
        refreshTokenRepository.save(storedRefreshToken);
        AuthUserEntity user = storedRefreshToken.getUser();
        var newRefreshTokenDb = RefreshToken.builder()
                .jti(newJti)
                .user(user)
                .createAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(jwtService.getRefreshTtlSeconds()))
                .revoked(false)
                .build();
        refreshTokenRepository.save(newRefreshTokenDb);
        String newAccessToken = jwtService.generateAccessToken(user);
        String newRefreshToken = jwtService.generateRefreshToken(user, newRefreshTokenDb.getJti());
        cookieService.attachRefreshCookie(response, newRefreshToken, (int) jwtService.getRefreshTtlSeconds());
        return ResponseEntity.ok(TokenResponse.of(newAccessToken, newRefreshToken,
                jwtService.getAccessTtlSeconds(), mapper.map(user, AuthUserDto.class)));
    }

    private Optional<String> readRefreshTokenFromRequest(RefreshTokenReq body, HttpServletRequest request) {
        // 1. cookie
        if (request.getCookies() != null) {
            Optional<String> cookies = Arrays.stream(request.getCookies())
                    .filter(c -> cookieService.getRefreshTokenCookieName().equals(c.getName()))
                    .map(Cookie::getValue).filter(v -> !v.isBlank()).findFirst();
            if (cookies.isPresent()) {
                return cookies;
            }
        }
        // 2. body
        if (body != null && body.refreshToken() != null && !body.refreshToken().isBlank()) {
            return Optional.of(body.refreshToken());
        }
        // 3. custom header
        String refreshHeader = request.getHeader("X-RefreshToken");
        if (refreshHeader != null && !refreshHeader.isBlank()) {
            return Optional.of(refreshHeader.trim());
        }
        // 4. Authorization bearer
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String candidate = authHeader.substring(7).trim();
            if (!candidate.isEmpty()) {
                try {
                    if (jwtService.isRefreshToken(candidate)) {
                        return Optional.of(candidate);
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return Optional.empty();
    }

    @PostMapping("/logoutRefreshToken")
    public ResponseEntity<Void> logout(HttpServletRequest request,
                                       HttpServletResponse response) {

        readRefreshTokenFromRequest(null, request).ifPresent(token -> {
            try {
                if (jwtService.isRefreshToken(token)) {
                    String jti = jwtService.getJti(token);
                    refreshTokenRepository.findByJti(jti)
                            .ifPresent(refreshToken -> {
                                refreshToken.setRevoked(true);
                                refreshTokenRepository.save(refreshToken);
                            });
                }
            } catch (JwtException ignored) {
            } catch (RuntimeException ignored) {
            }
        });

        Cookie cookie = new Cookie(cookieService.getRefreshTokenCookieName(), null);
        cookie.setHttpOnly(true);
        cookie.setSecure(true);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
        return ResponseEntity.noContent().build();
    }
}
