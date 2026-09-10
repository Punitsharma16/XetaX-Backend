package com.xetax.crm.meta;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A workspace's link to one Facebook Page (and optionally the ad account that
 * runs its ads). Leads submitted on that Page's lead-form ads land in
 * {@code targetFormId}; ad spend is pulled from {@code adAccountId}.
 *
 * Same shape and safety rules as WhatsAppConfig: tokens are stored encrypted
 * and never leave the server.
 */
@Entity
@Table(name = "meta_connections",
        uniqueConstraints = @UniqueConstraint(name = "uk_meta_conn_page", columnNames = {"owner_user_id", "page_id"}),
        indexes = @Index(name = "idx_meta_conn_owner", columnList = "owner_user_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MetaConnection {

    public static final String CONNECTED = "CONNECTED";
    public static final String DISCONNECTED = "DISCONNECTED";
    public static final String ERROR = "ERROR";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_user_id", nullable = false, length = 36)
    private String ownerUserId;

    @Column(name = "page_id", nullable = false, length = 64)
    private String pageId;

    @Column(name = "page_name", length = 200)
    private String pageName;

    /** Instagram account linked to the Page, when there is one. */
    @Column(name = "ig_user_id", length = 64)
    private String igUserId;

    /** Meta's ad account id, always in the act_<number> form. */
    @Column(name = "ad_account_id", length = 64)
    private String adAccountId;

    @Column(name = "ad_account_name", length = 200)
    private String adAccountName;

    @Column(length = 8)
    private String currency;

    @Lob
    @Column(name = "page_token_encrypted", columnDefinition = "TEXT")
    private String pageTokenEncrypted;

    @Lob
    @Column(name = "user_token_encrypted", columnDefinition = "TEXT")
    private String userTokenEncrypted;

    /** CRM form the leads become records in. */
    @Column(name = "target_form_id")
    private Long targetFormId;

    /** Stage that counts as a sale, so the report can show cost per sale. */
    @Column(name = "won_stage_id")
    private Long wonStageId;

    /** {"full_name":"name","phone_number":"phone"} — Meta field → CRM field key. */
    @Lob
    @Column(name = "field_map_json", columnDefinition = "TEXT")
    private String fieldMapJson;

    /** CONNECTED | DISCONNECTED | ERROR — varchar so new values need no migration. */
    @Column(nullable = false, length = 16)
    private String status;

    @Column(length = 400)
    private String lastError;

    private LocalDateTime lastLeadAt;
    private LocalDateTime lastInsightSyncAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
