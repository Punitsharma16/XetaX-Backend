package com.xetax.crm.billing;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One org's plan. Created lazily as TRIAL on first use; upgrades are applied
 * MANUALLY (SQL / admin) until the payment gateway covers plans too — only
 * AI top-ups go through Razorpay for now.
 */
@Entity
@Table(name = "org_plans", uniqueConstraints = @UniqueConstraint(columnNames = "ownerUserId"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrgPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String ownerUserId;

    /** TRIAL | STARTER | GROWTH | BUSINESS — quotas live in {@link PlanCatalog}. */
    @Column(nullable = false, length = 24)
    private String planKey;

    /** Only meaningful while planKey is TRIAL. */
    private LocalDateTime trialEndsAt;

    /** Purchased AI messages still unused — shared by assistant and agents. */
    @Column(nullable = false)
    private int topupBalance;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
