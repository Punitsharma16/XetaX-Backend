package com.xetax.crm.menu.entity;

import com.xetax.crm.data_manager.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/** A heading on the menu — Starters, Main course, Drinks. */
@Entity
@Table(name = "menu_categories",
        indexes = @Index(name = "idx_menu_cat_store", columnList = "store_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MenuCategory extends BaseEntity {

    @Column(name = "owner_user_id", length = 36, nullable = false)
    private String ownerUserId;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(length = 80, nullable = false)
    private String name;

    @Column(length = 255)
    private String description;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /** A hidden category hides every item in it, without losing them. */
    @Column(nullable = false)
    private boolean active;
}
