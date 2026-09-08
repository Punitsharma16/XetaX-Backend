package com.xetax.crm.billing;

import jakarta.persistence.*;
import lombok.*;

/** Per-agent slice of the month's agent messages — feeds the usage table. */
@Entity
@Table(name = "ai_agent_usage",
        uniqueConstraints = @UniqueConstraint(columnNames = {"agentId", "usage_month"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiAgentUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String ownerUserId;

    @Column(nullable = false)
    private Long agentId;

    /** Kept denormalised so the usage table survives an agent's deletion. */
    @Column(length = 160)
    private String agentName;

    /** MySQL reserves YEAR_MONTH, hence the explicit column name. */
    @Column(name = "usage_month", nullable = false)
    private int yearMonth;

    @Column(nullable = false)
    private int messages;
}
