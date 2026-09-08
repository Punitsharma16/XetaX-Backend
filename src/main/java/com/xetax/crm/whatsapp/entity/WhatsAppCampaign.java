package com.xetax.crm.whatsapp.entity;

import com.xetax.crm.data_manager.entity.BaseEntity;
import com.xetax.crm.whatsapp.enums.CampaignSourceType;
import com.xetax.crm.whatsapp.enums.CampaignStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A bulk WhatsApp send. Counters are updated ONLY through atomic
 * repository UPDATE ... SET x = x + 1 statements — never read-modify-write.
 */
@Entity
@Table(name = "whatsapp_campaigns",
        indexes = @Index(name = "idx_wa_camp_owner", columnList = "owner_user_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WhatsAppCampaign extends BaseEntity {

    @Column(name = "owner_user_id", length = 36, nullable = false)
    private String ownerUserId;

    @Column(name = "whatsapp_config_id", nullable = false)
    private Long whatsappConfigId;

    @Column(nullable = false, length = 255)
    private String name;

    /** Free text with {placeholder} tokens, or a template reference. */
    @Column(name = "message_template", length = 4000)
    private String messageTemplate;

    @Column(length = 255)
    private String templateName;

    @Column(length = 16)
    private String templateLanguage;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 10)
    private CampaignSourceType sourceType;

    /** Human description of the audience (form name + filters, or CSV file name). */
    @Column(name = "target_description", length = 500)
    private String targetDescription;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private CampaignStatus status;

    @Column(nullable = false) private int totalCount;
    @Column(nullable = false) private int queuedCount;
    @Column(nullable = false) private int sentCount;
    @Column(nullable = false) private int deliveredCount;
    @Column(nullable = false) private int readCount;
    @Column(nullable = false) private int failedCount;

    /** Ordered payload keys mapped to the template's {{1}},{{2}}… body variables. */
    @Column(name = "template_params_json", length = 1000)
    private String templateParamsJson;

    /** When set with status SCHEDULED, the scheduler starts it at this time. */
    private Instant scheduledAt;

    private Instant startedAt;

    private Instant completedAt;
}
