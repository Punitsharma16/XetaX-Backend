package com.xetax.crm.playbook;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Execution memory of the playbook: one row per (rule, record). Prevents
 * repeats, honours maxRepeats / repeatEvery, and doubles as the log the
 * panel shows ("Follow-up 2 sent on WhatsApp").
 */
@Entity
@Table(name = "playbook_runs", indexes = {
        @Index(name = "idx_pbrun_rule_record", columnList = "playbook_id, rule_id, record_id"),
        @Index(name = "idx_pbrun_record", columnList = "record_id"),
        @Index(name = "idx_pbrun_next", columnList = "status, next_eligible_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlaybookRun {

    public static final String ACTIVE = "ACTIVE";
    public static final String DONE = "DONE";
    public static final String STOPPED = "STOPPED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "playbook_id", nullable = false)
    private Long playbookId;

    @Column(name = "owner_user_id", nullable = false, length = 36)
    private String ownerUserId;

    @Column(name = "rule_id", nullable = false, length = 64)
    private String ruleId;

    @Column(length = 160)
    private String ruleName;

    @Column(name = "record_id", nullable = false, length = 64)
    private String recordId;

    @Column(nullable = false)
    private int runCount;

    private LocalDateTime lastRunAt;

    /** For scheduled one-offs (assistant booked a follow-up) — null = evaluated by trigger. */
    @Column(name = "next_eligible_at")
    private LocalDateTime nextEligibleAt;

    /** Free-form input for scheduled runs, e.g. the note the assistant left. */
    @Column(length = 1000)
    private String payload;

    /** WHATSAPP | EMAIL | TASK | STAGE | NOTIFY | NONE */
    @Column(length = 16)
    private String lastChannel;

    @Column(length = 300)
    private String lastOutcome;

    /** ACTIVE | DONE | STOPPED */
    @Column(nullable = false, length = 16)
    private String status;

    private LocalDateTime createdAt;
}
