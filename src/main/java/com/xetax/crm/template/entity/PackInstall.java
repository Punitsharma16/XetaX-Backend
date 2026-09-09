package com.xetax.crm.template.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** One row per pack application — lets the gallery show "Installed" + what it created. */
@Entity
@Table(name = "pack_installs", indexes = @Index(name = "idx_pack_install_owner", columnList = "owner_user_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PackInstall {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_user_id", nullable = false, length = 36)
    private String ownerUserId;

    @Column(name = "pack_key", nullable = false, length = 80)
    private String packKey;

    private Long formId;
    private Long agentId;
    private Long playbookId;
    private int fieldCount;
    private int stageCount;
    private int automationCount;
    private int draftCount;

    private LocalDateTime installedAt;
}
