package com.xetax.crm.billing;

import jakarta.persistence.*;
import lombok.*;

/** One org's AI consumption for one calendar month (yearMonth = 202608). */
@Entity
@Table(name = "ai_usage_months",
        uniqueConstraints = @UniqueConstraint(columnNames = {"ownerUserId", "usage_month"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiUsageMonth {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String ownerUserId;

    /** MySQL reserves YEAR_MONTH, hence the explicit column name. */
    @Column(name = "usage_month", nullable = false)
    private int yearMonth;

    @Column(nullable = false)
    private int assistantMessages;

    @Column(nullable = false)
    private int agentMessages;

    /** The 80% warning goes out once per month, not on every message. */
    @Column(nullable = false)
    private boolean alert80Sent;
}
