package com.xetax.crm.team.entity;

import com.xetax.crm.data_manager.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * An admin-defined role inside one organization (org == the owner user).
 * permissionsJson is a JSON array of permission keys from the fixed catalog
 * ({@link com.xetax.crm.team.service.PermissionCatalog}) — the ADMIN role is
 * seeded per org, cannot be edited/deleted, and always means "everything".
 */
@Entity
@Table(name = "org_roles",
        uniqueConstraints = @UniqueConstraint(name = "uq_role_owner_name",
                columnNames = {"owner_user_id", "name"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrgRole extends BaseEntity {

    @Column(name = "owner_user_id", length = 36, nullable = false)
    private String ownerUserId;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(name = "permissions_json", length = 2000)
    private String permissionsJson;

    @Column(nullable = false)
    private boolean systemRole;
}
