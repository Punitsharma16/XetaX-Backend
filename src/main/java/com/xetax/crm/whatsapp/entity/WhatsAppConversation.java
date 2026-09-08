package com.xetax.crm.whatsapp.entity;

import com.xetax.crm.data_manager.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** One customer phone number's thread under one WhatsAppConfig. Owner-scoped. */
@Entity
@Table(name = "whatsapp_conversations", uniqueConstraints =
        @UniqueConstraint(name = "uq_wa_conv_config_phone",
                columnNames = {"whatsapp_config_id", "customer_phone"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WhatsAppConversation extends BaseEntity {

    @Column(name = "owner_user_id", length = 36, nullable = false)
    private String ownerUserId;

    @Column(name = "whatsapp_config_id", nullable = false)
    private Long whatsappConfigId;

    @Column(name = "customer_phone", length = 32, nullable = false)
    private String customerPhone;

    @Column(length = 255)
    private String customerName;

    @Column(length = 1000)
    private String lastMessage;

    private Instant lastMessageAt;

    /** Last customer→business message — anchors the 24h service window. */
    private Instant lastInboundAt;

    /** Linked CRM record (set when messaging from a record, or matched later). */
    @Column(name = "record_id", length = 64)
    private String recordId;

    @Column(nullable = false)
    private int unreadCount;

    @Column(length = 20, nullable = false)
    private String status;
}
