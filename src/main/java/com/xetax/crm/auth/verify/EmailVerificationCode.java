package com.xetax.crm.auth.verify;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** One-time 6-digit code proving a new sign-up owns the email address. */
@Entity
@Table(name = "email_verification_codes", indexes = @Index(name = "idx_evc_email", columnList = "email"))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class EmailVerificationCode {

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

    @Column(nullable = false)
    private LocalDateTime createdAt;
}
