package com.xetax.crm.menu.entity;

import com.xetax.crm.data_manager.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * One workspace's public menu: the page customers order from.
 *
 * <p>The catalogue (categories and items) lives in its own tables and is never
 * a record. The only thing that reaches the CRM is a placed order, which
 * becomes a record in {@link #formId} — the form the Restaurant pack
 * installed — so the order pipeline, its stages and its WhatsApp updates carry
 * nothing but real customer orders.
 */
@Entity
@Table(name = "menu_stores",
        uniqueConstraints = @UniqueConstraint(name = "uq_menu_store_key", columnNames = {"public_key"}),
        indexes = @Index(name = "idx_menu_store_owner", columnList = "owner_user_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MenuStore extends BaseEntity {

    @Column(name = "owner_user_id", length = 36, nullable = false)
    private String ownerUserId;

    /** The Restaurant-pack form every order is created in. */
    @Column(name = "form_id")
    private Long formId;

    /** The only credential the public page has; regenerating it kills old links and QR codes. */
    @Column(name = "public_key", length = 40, nullable = false)
    private String publicKey;

    /** Off until the owner turns it on — a half-built menu is never public. */
    @Column(nullable = false)
    private boolean enabled;

    @Column(length = 120)
    private String title;

    @Column(length = 255)
    private String tagline;

    @Column(length = 8)
    private String currency;

    @Column(name = "dine_in", nullable = false)
    private boolean dineIn;

    @Column(nullable = false)
    private boolean takeaway;

    @Column(nullable = false)
    private boolean delivery;
}
