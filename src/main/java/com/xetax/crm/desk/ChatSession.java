package com.xetax.crm.desk;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One customer conversation with a bot, on either channel. This is the desk's
 * unit of work: who is talking, which record/contact they map to, whether the
 * AI or a human is driving, and the running summary.
 *
 * <p>{@code externalKey}: {@code web:<agentId>:<sid>} for the website widget,
 * {@code wa:<conversationId>} for WhatsApp — one session per customer thread.
 */
@Entity
@Table(name = "bot_chat_sessions",
        uniqueConstraints = @UniqueConstraint(name = "uk_bot_session_key", columnNames = "externalKey"),
        indexes = {
                @Index(name = "idx_bot_session_owner_status", columnList = "ownerUserId,status"),
                @Index(name = "idx_bot_session_record", columnList = "recordId"),
                @Index(name = "idx_bot_session_last", columnList = "lastMessageAt")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatSession {

    public static final String STATUS_AI = "AI";
    public static final String STATUS_WAITING = "WAITING_HUMAN";
    public static final String STATUS_HUMAN = "HUMAN";
    public static final String STATUS_RESOLVED = "RESOLVED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String ownerUserId;

    @Column(nullable = false)
    private Long agentId;

    /** WEBSITE | WHATSAPP */
    @Column(nullable = false, length = 16)
    private String channel;

    @Column(nullable = false, length = 160)
    private String externalKey;

    private Long whatsappConversationId;

    @Column(length = 32)
    private String customerPhone;

    @Column(length = 160)
    private String customerName;

    @Column(length = 190)
    private String customerEmail;

    /** Optional attribution from the hosted chat link (?src=diwali-campaign). */
    @Column(length = 80)
    private String sourceTag;

    @Column(length = 64)
    private String recordId;

    private Long contactId;

    /** AI | WAITING_HUMAN | HUMAN | RESOLVED */
    @Column(nullable = false, length = 16)
    private String status;

    @Column(nullable = false)
    private int aiTurns;

    /** Member currently handling the chat (status HUMAN). */
    @Column(length = 64)
    private String acceptedBy;

    private LocalDateTime acceptedAt;

    /** Stage/status the AI proposed in SUGGEST mode, awaiting a human click. */
    private Long pendingStageId;
    private Long pendingStatusId;

    @Column(columnDefinition = "TEXT")
    private String summary;

    private LocalDateTime summaryAt;

    /** Id of the newest line a team member has opened in the desk — unread = customer lines after it. */
    private Long lastSeenMessageId;

    private LocalDateTime lastCustomerAt;
    private LocalDateTime lastMessageAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
