package com.xetax.crm.team.entity;

import com.xetax.crm.data_manager.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/** Which role a team member (auth user with parentId=owner) currently holds. */
@Entity
@Table(name = "org_member_roles",
        uniqueConstraints = @UniqueConstraint(name = "uq_member_role",
                columnNames = "member_user_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrgMemberRole extends BaseEntity {

    @Column(name = "owner_user_id", length = 36, nullable = false)
    private String ownerUserId;

    @Column(name = "member_user_id", length = 36, nullable = false)
    private String memberUserId;

    @Column(name = "role_id", nullable = false)
    private Long roleId;
}
