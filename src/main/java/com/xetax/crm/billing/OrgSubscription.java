package com.xetax.crm.billing;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One paid plan period for an org. {@code org_plans.plan_key} stays the fast
 * "what plan is this org on right now" answer; this table is the history and
 * the validity — who bought which plan, for how long, for how much.
 *
 * <p>Statuses: ACTIVE (current period), EXPIRED (period ended, org downgraded),
 * CANCELLED (replaced by a newer subscription or cancelled by hand).
 */
@Entity
@Table(name = "org_subscriptions", indexes = {
        @Index(name = "idx_org_subs_owner", columnList = "ownerUserId"),
        @Index(name = "idx_org_subs_status", columnList = "status")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrgSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String ownerUserId;

    /** STARTER | GROWTH | BUSINESS — quotas live in {@link PlanCatalog}. */
    @Column(nullable = false, length = 24)
    private String planKey;

    /** ACTIVE | EXPIRED | CANCELLED */
    @Column(nullable = false, length = 16)
    private String status;

    @Column(nullable = false)
    private LocalDateTime startsAt;

    @Column(nullable = false)
    private LocalDateTime endsAt;

    /** What was actually paid, in paise (0 for a free/comped period). */
    @Column(nullable = false)
    private long amountPaise;

    /** UTR / Razorpay id / cheque no — manual plans are paid outside the app. */
    @Column(length = 128)
    private String paymentRef;

    @Column(length = 255)
    private String note;

    /** "Expires in 7 days" bell+email sent once per period. */
    @Column(nullable = false)
    private boolean renewalReminderSent;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
