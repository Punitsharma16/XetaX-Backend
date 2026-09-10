package com.xetax.crm.meta;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Which ad produced which record. Kept beside the record instead of inside it
 * so the customer's data stays clean, and so the ad report can join spend to
 * real pipeline outcomes.
 */
@Entity
@Table(name = "meta_lead_attribution",
        uniqueConstraints = @UniqueConstraint(name = "uk_meta_leadgen", columnNames = "leadgen_id"),
        indexes = {
                @Index(name = "idx_meta_attr_owner", columnList = "owner_user_id, created_at"),
                @Index(name = "idx_meta_attr_record", columnList = "record_id"),
                @Index(name = "idx_meta_attr_campaign", columnList = "owner_user_id, campaign_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MetaLeadAttribution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_user_id", nullable = false, length = 36)
    private String ownerUserId;

    /** Meta's id for this submission — the natural key that makes retries safe. */
    @Column(name = "leadgen_id", nullable = false, length = 64)
    private String leadgenId;

    @Column(name = "record_id", length = 64)
    private String recordId;

    @Column(name = "page_id", length = 64)
    private String pageId;

    @Column(name = "meta_form_id", length = 64)
    private String metaFormId;

    @Column(name = "meta_form_name", length = 200)
    private String metaFormName;

    @Column(name = "campaign_id", length = 64)
    private String campaignId;

    @Column(name = "campaign_name", length = 300)
    private String campaignName;

    @Column(name = "adset_id", length = 64)
    private String adsetId;

    @Column(name = "ad_id", length = 64)
    private String adId;

    @Column(name = "ad_name", length = 300)
    private String adName;

    @Column(length = 32)
    private String platform;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
