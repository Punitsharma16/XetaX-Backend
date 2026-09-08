package com.xetax.crm.billing;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** One Razorpay top-up purchase. Credited to the balance only after the
 *  payment signature verifies (status PAID). */
@Entity
@Table(name = "ai_topups", indexes = @Index(name = "idx_topup_owner", columnList = "ownerUserId"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiTopup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String ownerUserId;

    @Column(nullable = false, length = 24)
    private String packKey;

    @Column(nullable = false)
    private int messages;

    @Column(nullable = false)
    private int amountPaise;

    @Column(nullable = false, unique = true, length = 64)
    private String razorpayOrderId;

    @Column(length = 64)
    private String razorpayPaymentId;

    /** CREATED | PAID | FAILED */
    @Column(nullable = false, length = 16)
    private String status;

    private LocalDateTime createdAt;
    private LocalDateTime paidAt;
}
