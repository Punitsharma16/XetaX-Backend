package com.xetax.crm.template.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A WhatsApp template the workspace has NOT yet submitted to Meta — packs
 * ship these so the day WhatsApp gets connected, approval is one click.
 * Until then the body doubles as a plain-text message for the playbook
 * inside the 24-hour window.
 */
@Entity
@Table(name = "message_template_drafts",
        indexes = @Index(name = "idx_tpl_draft_owner", columnList = "owner_user_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MessageTemplateDraft {

    public static final String DRAFT = "DRAFT";
    public static final String SUBMITTED = "SUBMITTED";
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_user_id", nullable = false, length = 36)
    private String ownerUserId;

    private Long formId;

    @Column(name = "pack_key", length = 80)
    private String packKey;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 16)
    private String category;

    @Column(nullable = false, length = 16)
    private String language;

    @Column(length = 60)
    private String headerText;

    @Column(nullable = false, length = 1024)
    private String bodyText;

    @Column(length = 60)
    private String footerText;

    @Column(length = 1000)
    private String exampleParamsJson;

    @Column(length = 200)
    private String purpose;

    /** DRAFT | SUBMITTED | FAILED — varchar on purpose (ddl-auto never widens enums). */
    @Column(nullable = false, length = 16)
    private String status;

    @Column(length = 300)
    private String error;

    private LocalDateTime submittedAt;
    private LocalDateTime createdAt;
}
