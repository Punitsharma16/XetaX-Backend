package com.xetax.crm.auth.verify;

import com.xetax.crm.auth.security.JWTService;
import com.xetax.crm.auth.token.AuthTokenRepository;
import com.xetax.crm.auth.token.dto.TokenResponse;
import com.xetax.crm.auth.token.entity.RefreshToken;
import com.xetax.crm.auth.user.AuthUserDto;
import com.xetax.crm.auth.user.AuthUserEntity;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.common.ratelimit.RateLimiterService;
import com.xetax.crm.common.responce.ApiResponse;
import com.xetax.crm.common.responce.ResponseUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.modelmapper.ModelMapper;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Public endpoints behind the sign-up "confirm your email" step. */
@RestController
@RequestMapping("/auth/v1/verify-email")
@RequiredArgsConstructor
public class EmailVerificationController {

    private final EmailVerificationService verificationService;
    private final JWTService jwtService;
    private final AuthTokenRepository refreshTokenRepository;
    private final ModelMapper mapper;
    private final RateLimiterService rateLimiter;

    public record SendRequest(String email) {}
    public record ConfirmRequest(String email, String otp) {}

    /** (Re)send the code. Generic answer — never reveals whether the email exists. */
    @PostMapping("/send")
    public ApiResponse<Map<String, Object>> send(@RequestBody SendRequest request, HttpServletRequest http) {
        rateLimiter.check("verify-send:" + http.getRemoteAddr(), 5, Duration.ofMinutes(15));
        String email = normalize(request.email());
        if (email.isEmpty()) throw new BadRequestException("Email is required");
        verificationService.sendCode(email);
        return ResponseUtil.success("If that account needs verification, a code is on its way.", Map.of("sent", true));
    }

    /** Verify the code and sign the new user straight in (same payload as /auth/v1/login). */
    @PostMapping("/confirm")
    public TokenResponse confirm(@RequestBody ConfirmRequest request, HttpServletRequest http) {
        rateLimiter.check("verify-confirm:" + http.getRemoteAddr(), 10, Duration.ofMinutes(15));
        String email = normalize(request.email());
        String otp = request.otp() == null ? "" : request.otp().trim();
        if (email.isEmpty() || otp.isEmpty()) throw new BadRequestException("Email and code are required");

        AuthUserEntity user = verificationService.confirm(email, otp);

        String jti = UUID.randomUUID().toString();
        RefreshToken refresh = RefreshToken.builder()
                .jti(jti).user(user).createAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(jwtService.getRefreshTtlSeconds()))
                .revoked(false).build();
        refreshTokenRepository.save(refresh);
        return TokenResponse.of(
                jwtService.generateAccessToken(user),
                jwtService.generateRefreshToken(user, refresh.getJti()),
                jwtService.getAccessTtlSeconds(),
                mapper.map(user, AuthUserDto.class));
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }
}
