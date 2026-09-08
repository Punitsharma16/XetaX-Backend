package com.xetax.crm.desk;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Per-agent "where else does this bot work and what may it touch" settings.
 * One row per agent; absent row = website chat only with defaults.
 *
 * <ul>
 *   <li>{@code whatsappEnabled} — this agent answers the org's WhatsApp number
 *       (at most ONE agent per owner may have it on).</li>
 *   <li>{@code whatsappScope} — ALL inbound chats, or only CAMPAIGN replies.</li>
 *   <li>{@code pipelineMode} — SUGGEST (AI proposes stage/status, a human
 *       approves) or AUTO (AI sets it, guards still apply).</li>
 *   <li>{@code targetFormId} — every conversation becomes a record in this
 *       form (null = don't create records).</li>
 *   <li>{@code stageHintsJson} — the whitelist: only stages/statuses listed
 *       here can ever be chosen by the AI, each with a one-line "when".</li>
 * </ul>
 */
@Entity
@Table(name = "agent_channel_configs",
        uniqueConstraints = @UniqueConstraint(name = "uk_agent_channel_agent", columnNames = "agentId"),
        indexes = @Index(name = "idx_agent_channel_owner", columnList = "ownerUserId"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentChannelConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long agentId;

    @Column(nullable = false, length = 64)
    private String ownerUserId;

    @Column(nullable = false)
    private boolean whatsappEnabled;

    /** ALL | CAMPAIGN */
    @Column(nullable = false, length = 16)
    private String whatsappScope;

    /** SUGGEST | AUTO */
    @Column(nullable = false, length = 16)
    private String pipelineMode;

    private Long targetFormId;

    /** JSON: [{"stageId":1,"statusId":null,"hint":"customer shares budget"}] */
    @Column(columnDefinition = "TEXT")
    private String stageHintsJson;

    /** Comma-separated words that force a hand-off ("agent, human, call me"). */
    @Column(length = 500)
    private String handoffKeywords;

    /** AI turns per conversation before it hands off on its own. */
    @Column(nullable = false)
    private int maxAiTurns;

    /** Website visitors wait this long for a human before the AI takes over again. */
    @Column(nullable = false)
    private int websiteWaitMinutes;

    /** Let the AI write captured name/phone/email/etc. into the record. */
    @Column(nullable = false)
    private boolean captureFields;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
