package com.xetax.crm.emailcampaign.entity;

import com.xetax.crm.data_manager.entity.BaseEntity;
import com.xetax.crm.emailcampaign.enums.EmailCampaignSourceType;
import com.xetax.crm.emailcampaign.enums.EmailCampaignStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A bulk email send from the org's own SMTP account (Profile → Email).
 * Counters move ONLY through the repository's atomic UPDATE statements —
 * never read-modify-write — so concurrent Kafka consumers stay correct.
 */
@Entity
@Table(name = "email_campaigns",
        indexes = @Index(name = "idx_email_camp_owner", columnList = "owner_user_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailCampaign extends BaseEntity {

    @Column(name = "owner_user_id", length = 36, nullable = false)
    private String ownerUserId;

    @Column(nullable = false, length = 255)
    private String name;

    /** Subject line; {placeholder} tokens resolve per recipient. */
    @Column(nullable = false, length = 500)
    private String subject;

    /** Plain-text body with {placeholder} tokens. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 10)
    private EmailCampaignSourceType sourceType;

    /** Human description of the audience (form name, "all contacts", CSV file name). */
    @Column(name = "target_description", length = 500)
    private String targetDescription;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private EmailCampaignStatus status;

    @Column(nullable = false) private int totalCount;
    @Column(nullable = false) private int queuedCount;
    @Column(nullable = false) private int sentCount;
    @Column(nullable = false) private int failedCount;

    /** When set with status SCHEDULED, the scheduler starts it at this time. */
    private Instant scheduledAt;

    private Instant startedAt;

    private Instant completedAt;
}
