package com.xetax.crm.whatsapp.entity;

import com.xetax.crm.data_manager.entity.BaseEntity;
import com.xetax.crm.whatsapp.enums.WhatsAppConnectionStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One CRM user's connected WhatsApp Business assets (Meta Cloud API).
 * Owner-scoped: EVERY query must filter by ownerUserId.
 * accessTokenEncrypted is AES-GCM encrypted and is NEVER exposed by any DTO.
 */
@Entity
@Table(name = "whatsapp_configs", uniqueConstraints =
        @UniqueConstraint(name = "uq_wa_owner_waba_phone",
                columnNames = {"owner_user_id", "waba_id", "phone_number_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WhatsAppConfig extends BaseEntity {

    @Column(name = "owner_user_id", length = 36, nullable = false)
    private String ownerUserId;

    @Column(name = "business_id", length = 64)
    private String businessId;

    @Column(name = "waba_id", length = 64)
    private String wabaId;

    @Column(name = "phone_number_id", length = 64)
    private String phoneNumberId;

    @Column(length = 32)
    private String displayPhoneNumber;

    @Column(length = 255)
    private String verifiedName;

    @Column(length = 32)
    private String qualityRating;

    @Column(length = 32)
    private String nameStatus;

    @Column(length = 32)
    private String codeVerificationStatus;

    @Column(length = 32)
    private String accountMode;

    /** Meta tier e.g. TIER_250 / TIER_1K — how many unique customers per day. */
    @Column(length = 32)
    private String messagingLimit;

    @Column(nullable = false)
    private boolean webhookSubscribed;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WhatsAppConnectionStatus status;

    /** AES-GCM ciphertext — never serialized, never logged. */
    @Column(name = "access_token_encrypted", length = 2048)
    private String accessTokenEncrypted;

    private Instant tokenExpiresAt;

    private Instant connectedAt;

    private Instant lastSyncAt;

    @Column(length = 500)
    private String lastError;
}
