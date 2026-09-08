package com.xetax.crm.team.repository;

import com.xetax.crm.team.entity.OrgRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrgRoleRepository extends JpaRepository<OrgRole, Long> {

    List<OrgRole> findByOwnerUserIdOrderByIdAsc(String ownerUserId);

    Optional<OrgRole> findByIdAndOwnerUserId(Long id, String ownerUserId);

    Optional<OrgRole> findByOwnerUserIdAndNameIgnoreCase(String ownerUserId, String name);
}
