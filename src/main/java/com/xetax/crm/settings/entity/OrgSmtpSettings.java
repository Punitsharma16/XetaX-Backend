package com.xetax.crm.settings.entity;

import com.xetax.crm.data_manager.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * One organization's outgoing-email (SMTP) credentials, set by the admin from
 * the panel — no server .env needed. Password is AES-GCM encrypted at rest
 * and never returned by any API.
 */
@Entity
@Table(name = "org_smtp_settings",
        uniqueConstraints = @UniqueConstraint(name = "uq_smtp_owner", columnNames = "owner_user_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrgSmtpSettings extends BaseEntity {

    @Column(name = "owner_user_id", length = 36, nullable = false)
    private String ownerUserId;

    @Column(nullable = false, length = 255)
    private String host;

    @Column(nullable = false)
    private int port;

    /** Login + from-address dono yehi hai. */
    @Column(nullable = false, length = 255)
    private String username;

    @Column(name = "password_encrypted", nullable = false, length = 1024)
    private String passwordEncrypted;
}
