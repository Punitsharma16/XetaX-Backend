package com.xetax.crm.settings.entity;

import com.xetax.crm.data_manager.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * Where a workspace owner's morning digest goes.
 *
 * <p>There is deliberately no switch for the notification bell: while the
 * digest is on it always lands there. Email and WhatsApp can each be turned
 * off, or the whole digest can be. A workspace with no row gets everything on,
 * except WhatsApp for accounts opened on or after the cut-off date — Meta charges
 * for each of those messages from 1 October 2026 (see DigestPreferenceService).
 */
@Entity
@Table(name = "digest_preferences",
        uniqueConstraints = @UniqueConstraint(name = "uq_digest_owner", columnNames = "owner_user_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DigestPreference extends BaseEntity {

    @Column(name = "owner_user_id", length = 36, nullable = false)
    private String ownerUserId;

    /** Off means no digest at all — not in the bell, not by email, not on WhatsApp. */
    @Builder.Default
    @Column(nullable = false)
    private boolean enabled = true;

    @Builder.Default
    @Column(name = "email_enabled", nullable = false)
    private boolean emailEnabled = true;

    @Builder.Default
    @Column(name = "whatsapp_enabled", nullable = false)
    private boolean whatsappEnabled = true;
}
