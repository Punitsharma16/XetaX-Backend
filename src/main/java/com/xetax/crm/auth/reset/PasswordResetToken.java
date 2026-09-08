package com.xetax.crm.auth.reset;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One "forgot password" code. Only the SHA-256 of the 6-digit OTP is stored,
 * so a leaked table row is useless on its own. A fresh request invalidates
 * the previous code (one active token per email), and five wrong guesses
 * burn the token.
 */
@Entity
@Table(name = "password_reset_tokens",
        indexes = @Index(name = "idx_pwd_reset_email", columnList = "email"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 190)
    private String email;

    @Column(nullable = false, length = 64)
    private String otpHash;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    private int attempts;

    @Column(nullable = false)
    private boolean used;

    private LocalDateTime createdAt;
}
