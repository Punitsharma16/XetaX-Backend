package com.xetax.crm.playbook;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * The AI sales playbook of one form (pipeline): what "closing" means, when
 * the assistant follows up on its own, when it sends the quotation and when
 * it hands a lead to a person. Rules are data (see {@link PlaybookRule}) so
 * every industry configures its own without code.
 */
@Entity
@Table(name = "sales_playbooks",
        uniqueConstraints = @UniqueConstraint(name = "uk_playbook_form", columnNames = {"owner_user_id", "form_id"}),
        indexes = @Index(name = "idx_playbook_owner", columnList = "owner_user_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SalesPlaybook {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_user_id", nullable = false, length = 36)
    private String ownerUserId;

    @Column(name = "form_id", nullable = false)
    private Long formId;

    /** Agent whose persona writes AI-composed follow-ups (optional). */
    private Long agentId;

    @Column(nullable = false)
    private boolean active;

    /** What a closed deal looks like — steers the assistant's conversation. */
    @Column(length = 1000)
    private String goal;

    /** Comma-separated field keys that must be filled before a lead counts as qualified. */
    @Column(length = 500)
    private String qualificationKeys;

    /** Local hours (0-23) outside which nothing is sent; equal = no quiet hours. */
    @Column(nullable = false)
    private int quietStart;

    @Column(nullable = false)
    private int quietEnd;

    @Column(length = 40)
    private String timezone;

    /** Hard cap of automated follow-ups per record across all rules. */
    @Column(nullable = false)
    private int maxFollowUps;

    /** Stored document (quotation / brochure) the assistant may send. */
    private Long quotationDocumentId;

    @Lob
    @Column(name = "rules_json", columnDefinition = "LONGTEXT")
    private String rulesJson;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
