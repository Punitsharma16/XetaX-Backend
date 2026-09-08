package com.xetax.crm.whatsapp.entity;

import com.xetax.crm.data_manager.entity.BaseEntity;
import com.xetax.crm.whatsapp.enums.RecipientStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One campaign target. The unique (campaignId, phone) constraint plus the
 * status check in the sender make delivery idempotent — a Kafka redelivery
 * or worker restart can never send the same recipient twice.
 */
@Entity
@Table(name = "whatsapp_campaign_recipients",
        uniqueConstraints = @UniqueConstraint(name = "uq_wa_rcpt",
                columnNames = {"campaign_id", "phone"}),
        indexes = @Index(name = "idx_wa_rcpt_campaign", columnList = "campaign_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WhatsAppCampaignRecipient extends BaseEntity {

    @Column(name = "campaign_id", nullable = false)
    private Long campaignId;

    @Column(nullable = false, length = 32)
    private String phone;

    @Column(name = "record_id", length = 64)
    private String recordId;

    /** Placeholder values for this recipient (CSV row / record snapshot). */
    @Column(name = "payload_json", columnDefinition = "TEXT")
    private String payloadJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private RecipientStatus status;

    @Column(name = "provider_message_id", length = 128)
    private String providerMessageId;

    @Column(nullable = false)
    private int attemptCount;

    private Instant lastAttemptAt;

    @Column(length = 500)
    private String error;
}
