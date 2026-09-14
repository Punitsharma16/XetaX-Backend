package com.xetax.crm.whatsapp.entity;

import com.xetax.crm.data_manager.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A WhatsApp Flow — the form-like screens a customer fills inside WhatsApp.
 *
 * <p>The Flow itself lives at Meta; this row is the local handle on it: which
 * workspace owns it, what Meta calls it, whether it is published yet, and
 * which CRM form its answers should land in.
 */
@Entity
@Table(name = "whatsapp_flows",
        uniqueConstraints = @UniqueConstraint(name = "uq_wa_flow_meta_id",
                columnNames = {"meta_flow_id"}),
        indexes = @Index(name = "idx_wa_flow_owner", columnList = "owner_user_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WhatsAppFlow extends BaseEntity {

    @Column(name = "owner_user_id", length = 36, nullable = false)
    private String ownerUserId;

    @Column(name = "whatsapp_config_id", nullable = false)
    private Long whatsappConfigId;

    @Column(name = "meta_flow_id", length = 64)
    private String metaFlowId;

    @Column(nullable = false, length = 200)
    private String name;

    /** Meta's own lifecycle: DRAFT | PUBLISHED | DEPRECATED | BLOCKED | THROTTLED */
    @Column(length = 20, nullable = false)
    private String status;

    /** SIGN_UP, LEAD_GENERATION, APPOINTMENT_BOOKING, CONTACT_US, SURVEY, OTHER… */
    @Column(length = 40)
    private String category;

    /** The Flow JSON as last uploaded — kept so the panel can edit and resubmit. */
    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String flowJson;

    /** Where a submitted Flow's answers are written, when set. */
    @Column(name = "form_id")
    private Long formId;

    /** Flow field name → CRM field key, as JSON. Unmapped answers are still kept. */
    @Column(length = 4000)
    private String fieldMapJson;

    private Instant publishedAt;

    @Column(length = 500)
    private String lastError;
}
