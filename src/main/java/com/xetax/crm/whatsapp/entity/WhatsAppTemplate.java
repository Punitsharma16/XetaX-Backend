package com.xetax.crm.whatsapp.entity;

import com.xetax.crm.data_manager.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** Locally synchronized copy of the WABA's Meta message templates. Read-only. */
@Entity
@Table(name = "whatsapp_templates", uniqueConstraints =
        @UniqueConstraint(name = "uq_wa_tpl", columnNames = {"whatsapp_config_id", "name", "language"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WhatsAppTemplate extends BaseEntity {

    @Column(name = "owner_user_id", length = 36, nullable = false)
    private String ownerUserId;

    @Column(name = "whatsapp_config_id", nullable = false)
    private Long whatsappConfigId;

    @Column(name = "meta_template_id", length = 64)
    private String metaTemplateId;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false, length = 16)
    private String language;

    @Column(length = 32)
    private String category;

    /** As returned by Meta: APPROVED / PENDING / REJECTED / PAUSED ... */
    @Column(length = 32)
    private String status;

    @Column(name = "components_json", columnDefinition = "TEXT")
    private String componentsJson;

    @Column(length = 32)
    private String qualityScore;

    /** Meta's stated reason when a template is REJECTED/PAUSED. */
    @Column(length = 300)
    private String rejectionReason;

    private Instant syncedAt;
}
