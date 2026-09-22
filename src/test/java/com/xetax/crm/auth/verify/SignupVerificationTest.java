package com.xetax.crm.auth.verify;

import com.xetax.crm.auth.user.AuthUserDto;
import com.xetax.crm.auth.user.AuthUserEntity;
import com.xetax.crm.auth.user.AuthUserRepository;
import com.xetax.crm.auth.user.AuthUserService;
import com.xetax.crm.common.email.EmailService;
import com.xetax.crm.common.exception.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Signing up no longer creates an account. Until the code from the email is
 * typed back, the address and phone number are only held: a typo, or somebody
 * else's address, must not leave a real user behind that nobody can sign into
 * and that blocks the rightful owner from ever registering.
 */
class SignupVerificationTest {

    private AuthUserRepository users;
    private EmailVerificationCodeRepository codes;
    private PendingSignupRepository pendings;
    private EmailService email;
    private AuthUserService userService;
    private EmailVerificationService service;

    /** The codes the service mailed, newest last. */
    private final List<EmailVerificationCode> issued = new ArrayList<>();
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        users = mock(AuthUserRepository.class);
        codes = mock(EmailVerificationCodeRepository.class);
        pendings = mock(PendingSignupRepository.class);
        email = mock(EmailService.class);
        userService = mock(AuthUserService.class);

        when(email.isConfigured()).thenReturn(true);
        when(users.existsByEmail(anyString())).thenReturn(false);
        when(users.existsByPhone(anyString())).thenReturn(false);
        when(pendings.findByEmail(anyString())).thenReturn(Optional.empty());
        when(pendings.findByPhone(anyString())).thenReturn(Optional.empty());
        when(codes.save(any())).thenAnswer(call -> {
            EmailVerificationCode code = call.getArgument(0);
            issued.add(code);
            return code;
        });
        when(codes.findFirstByEmailAndUsedFalseOrderByIdDesc(anyString()))
                .thenAnswer(call -> issued.isEmpty() ? Optional.empty()
                        : Optional.of(issued.get(issued.size() - 1)));

        ObjectProvider<AuthUserService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(userService);

        service = new EmailVerificationService(users, codes, pendings, encoder, provider, email);
    }

    private AuthUserDto signup() {
        AuthUserDto dto = new AuthUserDto();
        dto.setEmail("  Priya@Example.com ");
        dto.setName("Priya");
        dto.setPhone("9896458807");
        dto.setPassword("Secret@123");
        dto.setCompany("Glow Salon");
        return dto;
    }

    private PendingSignup captureHeldSignup() {
        var captor = org.mockito.ArgumentCaptor.forClass(PendingSignup.class);
        verify(pendings).save(captor.capture());
        return captor.getValue();
    }

    /* ------------------------------------------------------------ signing up */

    @Test
    void signingUpHoldsTheDetailsAndCreatesNoAccount() {
        String held = service.startSignup(signup());

        assertEquals("priya@example.com", held);
        verifyNoInteractions(userService);
        verify(users, never()).save(any());

        PendingSignup pending = captureHeldSignup();
        assertEquals("priya@example.com", pending.getEmail());
        assertEquals("Priya", pending.getName());
        assertEquals("9896458807", pending.getPhone());
        // Never in the clear, even for the few minutes it is held.
        assertNotEquals("Secret@123", pending.getPasswordHash());
        assertTrue(encoder.matches("Secret@123", pending.getPasswordHash()));
        assertEquals(1, issued.size(), "a code should have been mailed");
    }

    @Test
    void anEmailThatAlreadyHasAnAccountIsRefusedBeforeAnythingIsHeld() {
        when(users.existsByEmail("priya@example.com")).thenReturn(true);

        BadRequestException refused = assertThrows(BadRequestException.class, () -> service.startSignup(signup()));

        assertTrue(refused.getMessage().contains("already registered"));
        verify(pendings, never()).save(any());
        assertTrue(issued.isEmpty());
    }

    @Test
    void aPhoneNumberSomebodyElseIsWaitingOnIsRefused() {
        PendingSignup other = PendingSignup.builder().email("someone@else.com").phone("9896458807")
                .passwordHash("x").expiresAt(LocalDateTime.now().plusHours(1))
                .createdAt(LocalDateTime.now()).build();
        when(pendings.findByPhone("9896458807")).thenReturn(Optional.of(other));

        assertThrows(BadRequestException.class, () -> service.startSignup(signup()));
        verify(pendings, never()).save(any());
    }

    @Test
    void signingUpAgainReplacesTheEarlierAttempt() {
        service.startSignup(signup());

        verify(pendings).deleteByEmail("priya@example.com");
    }

    @Test
    void aSignupWithNoPasswordIsRefused() {
        AuthUserDto dto = signup();
        dto.setPassword("  ");

        assertThrows(BadRequestException.class, () -> service.startSignup(dto));
        verify(pendings, never()).save(any());
    }

    /* ----------------------------------------------------------- confirming */

    private PendingSignup heldSignup(String hash) {
        PendingSignup pending = PendingSignup.builder()
                .email("priya@example.com").name("Priya").phone("9896458807")
                .company("Glow Salon").passwordHash(hash)
                .expiresAt(LocalDateTime.now().plusHours(2)).createdAt(LocalDateTime.now()).build();
        pending.setId(3L);
        when(pendings.findByEmail("priya@example.com")).thenReturn(Optional.of(pending));
        return pending;
    }

    @Test
    void theRightCodeCreatesTheAccountWithTheSamePassword() {
        service.startSignup(signup());
        String hash = captureHeldSignup().getPasswordHash();
        heldSignup(hash);

        // startSignup replaced any earlier attempt; watch only what confirm does.
        clearInvocations(pendings);

        UUID id = UUID.randomUUID();
        AuthUserDto created = new AuthUserDto();
        created.setId(id);
        created.setEmail("priya@example.com");
        when(userService.createVerifiedUser(any(), eq(hash))).thenReturn(created);
        AuthUserEntity saved = new AuthUserEntity();
        saved.setId(id);
        saved.setEmail("priya@example.com");
        when(users.findById(id)).thenReturn(Optional.of(saved));

        AuthUserEntity user = service.confirm("priya@example.com", currentOtp());

        assertEquals("priya@example.com", user.getEmail());
        var captor = org.mockito.ArgumentCaptor.forClass(AuthUserDto.class);
        verify(userService).createVerifiedUser(captor.capture(), eq(hash));
        assertEquals("Priya", captor.getValue().getName());
        assertEquals("9896458807", captor.getValue().getPhone());
        assertEquals(Boolean.TRUE, captor.getValue().getEmailVerified());
        // The hold is released once the account exists.
        verify(pendings).deleteByEmail("priya@example.com");
    }

    @Test
    void aWrongCodeCreatesNothingAndCostsAnAttempt() {
        service.startSignup(signup());
        heldSignup(captureHeldSignup().getPasswordHash());

        assertThrows(BadRequestException.class, () -> service.confirm("priya@example.com", "000000"));

        verify(userService, never()).createVerifiedUser(any(), anyString());
        assertEquals(1, issued.get(0).getAttempts());
    }

    @Test
    void aSignupLeftTooLongIsForgottenInsteadOfBecomingAnAccount() {
        service.startSignup(signup());
        PendingSignup stale = heldSignup(captureHeldSignup().getPasswordHash());
        stale.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        clearInvocations(pendings);

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> service.confirm("priya@example.com", currentOtp()));

        assertTrue(refused.getMessage().contains("expired"));
        verify(userService, never()).createVerifiedUser(any(), anyString());
        verify(pendings).deleteByEmail("priya@example.com");
    }

    /* ------------------------------------------- accounts from the old flow */

    @Test
    void anOlderUnconfirmedAccountIsStillVerifiedTheOldWay() {
        AuthUserEntity legacy = new AuthUserEntity();
        legacy.setEmail("old@example.com");
        legacy.setEmailVerified(false);
        when(users.findByEmail("old@example.com")).thenReturn(Optional.of(legacy));
        service.sendCode("old@example.com");

        AuthUserEntity user = service.confirm("old@example.com", currentOtp());

        assertTrue(user.getEmailVerified());
        verify(users).save(legacy);
        verifyNoInteractions(userService);
    }

    @Test
    void resendingBeforeConfirmingSendsAFreshCodeForTheHeldSignup() {
        service.startSignup(signup());
        heldSignup(captureHeldSignup().getPasswordHash());

        assertTrue(service.sendCode("priya@example.com"));
        assertEquals(2, issued.size());
        assertNotEquals(issued.get(0).getOtpHash(), issued.get(1).getOtpHash());
    }

    /** The plain code behind the last hash the service mailed. */
    private String currentOtp() {
        String hash = issued.get(issued.size() - 1).getOtpHash();
        for (int candidate = 0; candidate < 1_000_000; candidate++) {
            String otp = String.format("%06d", candidate);
            if (EmailVerificationService.sha256(otp).equals(hash)) return otp;
        }
        throw new IllegalStateException("code not found");
    }
}
