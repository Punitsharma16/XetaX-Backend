package com.xetax.crm.auth.reset;

import com.xetax.crm.auth.user.AuthUserEntity;
import com.xetax.crm.auth.user.AuthUserRepository;
import com.xetax.crm.common.email.EmailService;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.common.ratelimit.RateLimiterService;
import com.xetax.crm.common.responce.ApiResponse;
import com.xetax.crm.common.responce.ResponseUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

/**
 * Forgot password with a 6-digit email code.
 *
 * <p>Anti-abuse: the forgot endpoint always answers the same generic success
 * whether or not the email exists (no account probing), both endpoints are
 * rate-limited per IP, a code lives 15 minutes, and 5 wrong guesses burn it.
 * The code email goes out via the global SMTP (.env) — org SMTP belongs to
 * customers and is irrelevant before sign-in.
 */
@RestController
@RequestMapping("/auth/v1")
@RequiredArgsConstructor
@Slf4j
public class PasswordResetController {

    private static final Duration OTP_TTL = Duration.ofMinutes(15);
    private static final int MAX_ATTEMPTS = 5;
    private static final String GENERIC_OK =
            "If an account exists for that email, a reset code has been sent.";

    private final AuthUserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final RateLimiterService rateLimiter;
    private final SecureRandom random = new SecureRandom();

    public record ForgotRequest(String email) {}

    @PostMapping("/forgot-password")
    @Transactional
    public ApiResponse<Map<String, Object>> forgot(@RequestBody ForgotRequest request,
                                                   HttpServletRequest servletRequest) {
        rateLimiter.check("pwd-forgot:" + servletRequest.getRemoteAddr(), 5, Duration.ofMinutes(15));

        String email = normalize(request.email());
        if (email.isEmpty()) throw new BadRequestException("Email is required");

        Optional<AuthUserEntity> user = userRepository.findByEmail(email);
        if (user.isPresent()) {
            // One active code per email — a new request replaces the old one.
            tokenRepository.deleteByEmail(email);

            String otp = String.format("%06d", random.nextInt(1_000_000));
            tokenRepository.save(PasswordResetToken.builder()
                    .email(email)
                    .otpHash(sha256(otp))
                    .expiresAt(LocalDateTime.now().plus(OTP_TTL))
                    .attempts(0)
                    .used(false)
                    .createdAt(LocalDateTime.now())
                    .build());

            try {
                emailService.send(email, "XetaX: your password reset code",
                        "Hi,\n\nYour XetaX password reset code is: " + otp + "\n\n"
                        + "It expires in 15 minutes. If you didn't request this, "
                        + "you can safely ignore this email — your password is unchanged.\n\n— XetaX");
            } catch (Exception e) {
                log.warn("Password reset email failed for {}: {}", email, e.getMessage());
            }
            if (!emailService.isConfigured()) {
                log.warn("SMTP not configured — password reset code for {} was only logged", email);
            }
        }
        return ResponseUtil.success(GENERIC_OK, Map.of("sent", true));
    }

    public record ResetRequest(String email, String otp, String newPassword) {}

    @PostMapping("/reset-password")
    @Transactional
    public ApiResponse<Map<String, Object>> reset(@RequestBody ResetRequest request,
                                                  HttpServletRequest servletRequest) {
        rateLimiter.check("pwd-reset:" + servletRequest.getRemoteAddr(), 10, Duration.ofMinutes(15));

        String email = normalize(request.email());
        String otp = request.otp() == null ? "" : request.otp().trim();
        String password = request.newPassword() == null ? "" : request.newPassword();
        if (email.isEmpty() || otp.isEmpty()) throw new BadRequestException("Email and code are required");
        if (password.length() < 6) throw new BadRequestException("Use at least 6 characters for the new password");

        PasswordResetToken token = tokenRepository.findFirstByEmailAndUsedFalseOrderByIdDesc(email)
                .orElseThrow(() -> new BadRequestException("That code is invalid or expired — request a new one"));
        if (token.getExpiresAt().isBefore(LocalDateTime.now()) || token.getAttempts() >= MAX_ATTEMPTS) {
            throw new BadRequestException("That code is invalid or expired — request a new one");
        }

        if (!MessageDigest.isEqual(
                token.getOtpHash().getBytes(StandardCharsets.UTF_8),
                sha256(otp).getBytes(StandardCharsets.UTF_8))) {
            token.setAttempts(token.getAttempts() + 1);
            tokenRepository.save(token);
            throw new BadRequestException("Wrong code — check the email and try again");
        }

        AuthUserEntity user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BadRequestException("That code is invalid or expired — request a new one"));
        user.setPassword(passwordEncoder.encode(password));
        userRepository.save(user);

        token.setUsed(true);
        tokenRepository.save(token);

        log.info("Password reset completed for {}", email);
        return ResponseUtil.success("Password changed — sign in with the new password.", Map.of("reset", true));
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
