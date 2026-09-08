package com.xetax.crm.whatsapp.entity;

import com.xetax.crm.data_manager.entity.BaseEntity;
import com.xetax.crm.whatsapp.enums.MessageDirection;
import com.xetax.crm.whatsapp.enums.WhatsAppMessageStatus;
import com.xetax.crm.whatsapp.enums.WhatsAppMessageType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Every WhatsApp message in or out, one row. providerMessageId is the
 * idempotency key for webhook status updates and inbound dedupe (unique;
 * MySQL allows multiple NULLs so locally-failed sends don't clash).
 */
@Entity
@Table(name = "whatsapp_messages",
        uniqueConstraints = @UniqueConstraint(name = "uq_wa_msg_provider_id",
                columnNames = {"provider_message_id"}),
        indexes = {
                @Index(name = "idx_wa_msg_conv", columnList = "conversation_id"),
                @Index(name = "idx_wa_msg_owner", columnList = "owner_user_id"),
                @Index(name = "idx_wa_msg_campaign", columnList = "campaign_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WhatsAppMessage extends BaseEntity {

    @Column(name = "owner_user_id", length = 36, nullable = false)
    private String ownerUserId;

    @Column(name = "conversation_id")
    private Long conversationId;

    @Column(name = "whatsapp_config_id", nullable = false)
    private Long whatsappConfigId;

    /** Mongo record id when the message relates to a CRM record. */
    @Column(name = "record_id", length = 64)
    private String recordId;

    @Column(name = "campaign_id")
    private Long campaignId;

    @Column(name = "to_phone", length = 32)
    private String toPhone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private MessageDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private WhatsAppMessageType messageType;

    @Column(length = 4000)
    private String body;

    /** Meta media id for inbound IMAGE/VIDEO/AUDIO/DOCUMENT messages. */
    @Column(name = "media_id", length = 128)
    private String mediaId;

    @Column(length = 255)
    private String templateName;

    @Column(length = 16)
    private String templateLanguage;

    @Column(name = "provider_message_id", length = 128)
    private String providerMessageId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private WhatsAppMessageStatus status;

    @Column(length = 32)
    private String errorCode;

    @Column(length = 500)
    private String errorMessage;

    private Instant sentAt;

    private Instant deliveredAt;

    private Instant readAt;
}
