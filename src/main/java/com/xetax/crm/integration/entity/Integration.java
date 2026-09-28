package com.xetax.crm.integration.entity;

import com.xetax.crm.data_manager.entity.BaseEntity;
import com.xetax.crm.data_manager.entity.FormEntity;
import com.xetax.crm.integration.enums.IntegrationStatus;
import com.xetax.crm.integration.enums.IntegrationType;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "integrations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Integration extends BaseEntity {


    @Column(nullable = false)
    private String name;

    /**
     * Unique Public Key
     * Example:
     * 7d93e81a-a53f-48b7-9e75...
     */
    @Column(nullable = false, unique = true, updatable = false)
    private String integrationKey;

    @Column(nullable = false, unique = true)
    private String apiKey;

    /**
     * Which form should receive data
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "form_id", nullable = false)
    private FormEntity form;

    /**
     * Integration Status
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IntegrationStatus status;


    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IntegrationType type;

    /**
     * Optional Description
     */
    @Column(length = 1000)
    private String description;

    /*
     * Diagnostics from the most recent payload.
     *
     * An unmapped key is dropped by design, but it used to be dropped in
     * silence: the sender saw 200 OK and the record simply came out missing a
     * field. These three columns are what the panel shows so a rename on the
     * sending side is visible instead of being hunted for.
     */

    private LocalDateTime lastPayloadAt;

    /** Comma-separated paths the payload sent that no mapping claimed. */
    @Column(length = 2000)
    private String lastIgnoredKeys;

    /** Comma-separated mapped source fields the payload did not carry. */
    @Column(length = 2000)
    private String lastUnmatchedFields;
}
