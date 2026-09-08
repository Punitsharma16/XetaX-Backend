package com.xetax.crm.team.repository;

import com.xetax.crm.team.entity.OrgMemberRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrgMemberRoleRepository extends JpaRepository<OrgMemberRole, Long> {

    Optional<OrgMemberRole> findByMemberUserId(String memberUserId);

    List<OrgMemberRole> findByOwnerUserId(String ownerUserId);

    long countByRoleId(Long roleId);
}
