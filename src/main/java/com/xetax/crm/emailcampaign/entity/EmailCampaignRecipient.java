package com.xetax.crm.emailcampaign.entity;

import com.xetax.crm.data_manager.entity.BaseEntity;
import com.xetax.crm.emailcampaign.enums.EmailRecipientStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One campaign target. The unique (campaignId, email) constraint plus the
 * QUEUED-only check in the sender make delivery idempotent — a Kafka
 * redelivery or worker restart can never mail the same address twice.
 */
@Entity
@Table(name = "email_campaign_recipients",
        uniqueConstraints = @UniqueConstraint(name = "uq_email_rcpt",
                columnNames = {"campaign_id", "email"}),
        indexes = @Index(name = "idx_email_rcpt_campaign", columnList = "campaign_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailCampaignRecipient extends BaseEntity {

    @Column(name = "campaign_id", nullable = false)
    private Long campaignId;

    @Column(nullable = false, length = 160)
    private String email;

    @Column(name = "record_id", length = 64)
    private String recordId;

    @Column(name = "contact_id")
    private Long contactId;

    /** Placeholder values for this recipient (record data / contact / CSV row). */
    @Column(name = "payload_json", columnDefinition = "TEXT")
    private String payloadJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private EmailRecipientStatus status;

    @Column(nullable = false)
    private int attemptCount;

    private Instant lastAttemptAt;

    @Column(length = 500)
    private String error;
}
