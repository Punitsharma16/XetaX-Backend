package com.xetax.crm.meta;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** One ad's numbers for one day, as Meta reports them. Re-pulled and overwritten. */
@Entity
@Table(name = "meta_ad_daily",
        uniqueConstraints = @UniqueConstraint(name = "uk_meta_ad_day",
                columnNames = {"owner_user_id", "day", "ad_id"}),
        indexes = @Index(name = "idx_meta_ad_daily_owner", columnList = "owner_user_id, day"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MetaAdDaily {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_user_id", nullable = false, length = 36)
    private String ownerUserId;

    @Column(name = "ad_account_id", length = 64)
    private String adAccountId;

    @Column(nullable = false)
    private LocalDate day;

    @Column(name = "campaign_id", length = 64)
    private String campaignId;

    @Column(name = "campaign_name", length = 300)
    private String campaignName;

    @Column(name = "ad_id", nullable = false, length = 64)
    private String adId;

    @Column(name = "ad_name", length = 300)
    private String adName;

    @Column(precision = 14, scale = 2)
    private BigDecimal spend;

    private long impressions;
    private long clicks;

    /** Lead-form submissions Meta counted for this ad on this day. */
    private long leads;

    @Column(length = 8)
    private String currency;

    private LocalDateTime syncedAt;
}
