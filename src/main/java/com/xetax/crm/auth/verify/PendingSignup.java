package com.xetax.crm.auth.verify;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A sign-up that has been asked for but not confirmed yet.
 *
 * <p>Nothing about the person reaches the users table until they type the code
 * from their email: an address someone else owns, or a typo, would otherwise
 * leave a real account sitting there — signed up, never used, and holding the
 * email and phone number against anyone who later tries to register properly.
 *
 * <p>The password is stored already hashed, exactly as the users table would
 * hold it; the row is replaced whenever the same address signs up again, and
 * it is thrown away once the code is used or has expired.
 */
@Entity
@Table(name = "pending_signups",
        uniqueConstraints = @UniqueConstraint(name = "uq_pending_signup_email", columnNames = {"email"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PendingSignup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 300)
    private String email;

    @Column(length = 500)
    private String name;

    @Column(length = 14)
    private String phone;

    @Column(length = 255)
    private String company;

    /** BCrypt, the same value the users table would carry. */
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
