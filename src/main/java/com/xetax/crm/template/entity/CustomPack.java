package com.xetax.crm.template.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A pack a workspace made itself — exported from one of its forms or pasted
 * as JSON. Same shape as the built-in packs, private to the owner.
 */
@Entity
@Table(name = "vertical_packs",
        uniqueConstraints = @UniqueConstraint(name = "uk_pack_owner_key", columnNames = {"owner_user_id", "pack_key"}),
        indexes = @Index(name = "idx_pack_owner", columnList = "owner_user_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomPack {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_user_id", nullable = false, length = 36)
    private String ownerUserId;

    @Column(name = "pack_key", nullable = false, length = 80)
    private String packKey;

    @Column(nullable = false, length = 160)
    private String name;

    /** EXPORT (from a form) | IMPORT (pasted JSON) */
    @Column(nullable = false, length = 16)
    private String source;

    @Lob
    @Column(name = "definition_json", nullable = false, columnDefinition = "LONGTEXT")
    private String definitionJson;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
