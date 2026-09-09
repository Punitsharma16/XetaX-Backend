package com.xetax.crm.template.repository;

import com.xetax.crm.template.entity.PackInstall;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PackInstallRepository extends JpaRepository<PackInstall, Long> {
    List<PackInstall> findByOwnerUserIdOrderByInstalledAtDesc(String ownerUserId);
    List<PackInstall> findByOwnerUserIdAndPackKeyOrderByInstalledAtDesc(String ownerUserId, String packKey);
}
