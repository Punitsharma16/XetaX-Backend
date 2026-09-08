package com.xetax.crm.auth.verify;

import com.xetax.crm.auth.user.AuthUserEntity;
import com.xetax.crm.auth.user.AuthUserRepository;
import com.xetax.crm.common.email.EmailService;
import com.xetax.crm.common.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;

/**
 * Email verification for self-service sign-ups. Same shape as the password
 * reset flow: 6-digit code, 15-minute TTL, 5 attempts, one active code per
 * address. When no SMTP is configured the account is verified on the spot —
 * a missing mail server must never lock every new customer out.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailVerificationService {

    private static final Duration OTP_TTL = Duration.ofMinutes(15);
    private static final int MAX_ATTEMPTS = 5;

    private final AuthUserRepository userRepository;
    private final EmailVerificationCodeRepository codeRepository;
    private final EmailService emailService;
    private final SecureRandom random = new SecureRandom();

    /** Whether new sign-ups have to confirm their email before signing in. */
    public boolean verificationRequired() {
        return emailService.isConfigured();
    }

    /**
     * Issue (or re-issue) a code for an unverified account. Returns true when
     * a code was actually sent; false when nothing needed doing (already
     * verified, unknown email) — callers answer generically either way.
     */
    @Transactional
    public boolean sendCode(String email) {
        AuthUserEntity user = userRepository.findByEmail(email).orElse(null);
        if (user == null || Boolean.TRUE.equals(user.getEmailVerified()) || user.getEmailVerified() == null) {
            return false;
        }
        if (!emailService.isConfigured()) {
            // No way to deliver a code — don't strand the user.
            user.setEmailVerified(true);
            userRepository.save(user);
            log.warn("SMTP not configured — auto-verified {}", email);
            return false;
        }
        codeRepository.deleteByEmail(email);
        String otp = String.format("%06d", random.nextInt(1_000_000));
        codeRepository.save(EmailVerificationCode.builder()
                .email(email)
                .otpHash(sha256(otp))
                .expiresAt(LocalDateTime.now().plus(OTP_TTL))
                .attempts(0)
                .used(false)
                .createdAt(LocalDateTime.now())
                .build());
        try {
            emailService.send(email, "XetaX: confirm your email",
                    "Hi " + (user.getName() == null ? "" : user.getName()) + ",\n\n"
                    + "Your XetaX verification code is: " + otp + "\n\n"
                    + "Enter it on the sign-up page to activate your workspace. "
                    + "It expires in 15 minutes.\n\n— XetaX");
        } catch (Exception e) {
            log.warn("Verification email failed for {}: {}", email, e.getMessage());
            throw new BadRequestException("Could not send the verification email right now — try again in a minute");
        }
        return true;
    }

    /** Check the code and mark the account verified. */
    @Transactional
    public AuthUserEntity confirm(String email, String otp) {
        AuthUserEntity user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BadRequestException("That code is invalid or expired — request a new one"));
        if (!Boolean.FALSE.equals(user.getEmailVerified())) {
            return user; // already verified — idempotent
        }
        EmailVerificationCode code = codeRepository.findFirstByEmailAndUsedFalseOrderByIdDesc(email)
                .orElseThrow(() -> new BadRequestException("That code is invalid or expired — request a new one"));
        if (code.getExpiresAt().isBefore(LocalDateTime.now()) || code.getAttempts() >= MAX_ATTEMPTS) {
            throw new BadRequestException("That code is invalid or expired — request a new one");
        }
        if (!MessageDigest.isEqual(
                code.getOtpHash().getBytes(StandardCharsets.UTF_8),
                sha256(otp).getBytes(StandardCharsets.UTF_8))) {
            code.setAttempts(code.getAttempts() + 1);
            codeRepository.save(code);
            throw new BadRequestException("Wrong code — check the email and try again");
        }
        code.setUsed(true);
        codeRepository.save(code);
        user.setEmailVerified(true);
        userRepository.save(user);
        log.info("Email verified for {}", email);
        return user;
    }

    static String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
