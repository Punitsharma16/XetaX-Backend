package com.xetax.crm.menu.entity;

import com.xetax.crm.data_manager.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * One dish. An offer is an optional lower price plus a short label; when the
 * offer price is set and below the regular price, that is what the customer
 * pays — computed on the server from these columns, never taken from the page.
 */
@Entity
@Table(name = "menu_items",
        indexes = {
                @Index(name = "idx_menu_item_store", columnList = "store_id"),
                @Index(name = "idx_menu_item_cat", columnList = "category_id"),
                @Index(name = "idx_menu_item_image", columnList = "image_key")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MenuItem extends BaseEntity {

    @Column(name = "owner_user_id", length = 36, nullable = false)
    private String ownerUserId;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(length = 120, nullable = false)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(precision = 10, scale = 2, nullable = false)
    private BigDecimal price;

    /** The discounted price while an offer runs; null when there is none. */
    @Column(name = "offer_price", precision = 10, scale = 2)
    private BigDecimal offerPrice;

    /** e.g. "20% OFF", "Chef's special" — shown as a badge. */
    @Column(name = "offer_label", length = 40)
    private String offerLabel;

    /** true veg, false non-veg, null when the owner did not say. */
    private Boolean veg;

    /** Sold out today — stays on the page, cannot be ordered. */
    @Column(nullable = false)
    private boolean available;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /** Unguessable key in the photo's public link. */
    @Column(name = "image_key", length = 64)
    private String imageKey;

    @Column(name = "image_path", length = 512)
    private String imagePath;

    @Column(name = "image_mime", length = 64)
    private String imageMime;
}
