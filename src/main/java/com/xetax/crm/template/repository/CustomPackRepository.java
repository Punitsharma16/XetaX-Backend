package com.xetax.crm.template.repository;

import com.xetax.crm.template.entity.CustomPack;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CustomPackRepository extends JpaRepository<CustomPack, Long> {
    List<CustomPack> findByOwnerUserIdOrderByNameAsc(String ownerUserId);
    Optional<CustomPack> findByOwnerUserIdAndPackKey(String ownerUserId, String packKey);
    Optional<CustomPack> findByIdAndOwnerUserId(Long id, String ownerUserId);
}
