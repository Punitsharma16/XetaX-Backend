package com.xetax.crm.auth.verify;

import com.xetax.crm.auth.user.AuthUserDto;
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
    /** How long an unconfirmed sign-up is kept before it is forgotten. */
    private static final Duration SIGNUP_TTL = Duration.ofHours(24);
    private static final int MAX_ATTEMPTS = 5;

    private final AuthUserRepository userRepository;
    private final EmailVerificationCodeRepository codeRepository;
    private final PendingSignupRepository pendingRepository;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    private final org.springframework.beans.factory.ObjectProvider<
            com.xetax.crm.auth.user.AuthUserService> userService;
    private final EmailService emailService;
    private final SecureRandom random = new SecureRandom();

    /** Whether new sign-ups have to confirm their email before signing in. */
    public boolean verificationRequired() {
        return emailService.isConfigured();
    }

    /**
     * Holds a sign-up until the code is confirmed. Nothing is written to the
     * users table here — that only happens in {@link #confirm}, so an address
     * somebody typed wrongly, or one they do not own, never leaves an account
     * behind holding that email and phone number.
     *
     * @return the address the code was sent to
     */
    @Transactional
    public String startSignup(AuthUserDto signup) {
        String email = signup.getEmail() == null ? "" : signup.getEmail().trim().toLowerCase();
        if (email.isEmpty()) throw new BadRequestException("Email is required");
        if (signup.getPassword() == null || signup.getPassword().isBlank()) {
            throw new BadRequestException("Password is required");
        }
        String phone = signup.getPhone() == null || signup.getPhone().isBlank()
                ? null : signup.getPhone().trim();

        // The same answers a real account would give, given before anything is
        // stored — otherwise the person types a code and only then hears that
        // the email or phone was taken.
        if (userRepository.existsByEmail(email)) {
            throw new BadRequestException("This email is already registered");
        }
        if (phone != null && userRepository.existsByPhone(phone)) {
            throw new BadRequestException("This phone number is already registered");
        }
        pendingRepository.deleteExpired(LocalDateTime.now());
        boolean phoneHeldByAnotherSignup = phone != null && pendingRepository.findByPhone(phone)
                .filter(other -> !other.getEmail().equals(email))
                .isPresent();
        if (phoneHeldByAnotherSignup) {
            throw new BadRequestException("This phone number is already registered");
        }

        // Signing up again simply replaces the earlier attempt.
        pendingRepository.deleteByEmail(email);
        pendingRepository.flush();
        pendingRepository.save(PendingSignup.builder()
                .email(email)
                .name(signup.getName())
                .phone(phone)
                .company(signup.getCompany())
                .passwordHash(passwordEncoder.encode(signup.getPassword()))
                .expiresAt(LocalDateTime.now().plus(SIGNUP_TTL))
                .createdAt(LocalDateTime.now())
                .build());

        issueCode(email, signup.getName());
        return email;
    }

    /**
     * Issue (or re-issue) a code for an unverified account. Returns true when
     * a code was actually sent; false when nothing needed doing (already
     * verified, unknown email) — callers answer generically either way.
     */
    @Transactional
    public boolean sendCode(String email) {
        PendingSignup pending = pendingRepository.findByEmail(email).orElse(null);
        if (pending != null) {
            if (pending.getExpiresAt().isBefore(LocalDateTime.now())) {
                pendingRepository.deleteByEmail(email);
                return false;
            }
            issueCode(email, pending.getName());
            return true;
        }

        // An account from before sign-ups were held back, still unconfirmed.
        AuthUserEntity user = userRepository.findByEmail(email).orElse(null);
        if (user == null || !Boolean.FALSE.equals(user.getEmailVerified())) {
            return false;
        }
        if (!emailService.isConfigured()) {
            // No way to deliver a code — don't strand the user.
            user.setEmailVerified(true);
            userRepository.save(user);
            log.warn("SMTP not configured — auto-verified {}", email);
            return false;
        }
        issueCode(email, user.getName());
        return true;
    }

    /** One live code per address: the old one is dropped and a new one mailed. */
    private void issueCode(String email, String name) {
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
                    "Hi " + (name == null ? "" : name) + ",\n\n"
                    + "Your XetaX verification code is: " + otp + "\n\n"
                    + "Enter it on the sign-up page to activate your workspace. "
                    + "It expires in 15 minutes.\n\n— XetaX");
        } catch (Exception e) {
            log.warn("Verification email failed for {}: {}", email, e.getMessage());
            throw new BadRequestException("Could not send the verification email right now — try again in a minute");
        }
    }

    /**
     * Checks the code. A held sign-up becomes a real account at this moment
     * and not before; an older unconfirmed account is simply marked verified.
     */
    @Transactional
    public AuthUserEntity confirm(String email, String otp) {
        PendingSignup pending = pendingRepository.findByEmail(email).orElse(null);
        if (pending == null) return confirmExistingUser(email, otp);

        if (pending.getExpiresAt().isBefore(LocalDateTime.now())) {
            pendingRepository.deleteByEmail(email);
            throw new BadRequestException("That sign-up has expired — please register again");
        }
        burnCode(email, otp);

        AuthUserDto signup = new AuthUserDto();
        signup.setEmail(pending.getEmail());
        signup.setName(pending.getName());
        signup.setPhone(pending.getPhone());
        signup.setCompany(pending.getCompany());
        signup.setEmailVerified(true);
        signup.setEnable(true);
        signup.setAdmin(false);

        AuthUserDto created = userService.getObject().createVerifiedUser(signup, pending.getPasswordHash());
        pendingRepository.deleteByEmail(email);
        log.info("Sign-up confirmed and account created for {}", email);

        return userRepository.findById(created.getId())
                .orElseThrow(() -> new BadRequestException("Could not open the new account — please sign in"));
    }

    /** An account made before sign-ups were held back, still unconfirmed. */
    private AuthUserEntity confirmExistingUser(String email, String otp) {
        AuthUserEntity user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BadRequestException("That code is invalid or expired — request a new one"));
        if (!Boolean.FALSE.equals(user.getEmailVerified())) {
            return user; // already verified — idempotent
        }
        burnCode(email, otp);
        user.setEmailVerified(true);
        userRepository.save(user);
        log.info("Email verified for {}", email);
        return user;
    }

    /**
     * Spends the code or refuses it. A wrong guess costs an attempt, so a
     * six-digit code cannot be walked through.
     */
    private void burnCode(String email, String otp) {
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
