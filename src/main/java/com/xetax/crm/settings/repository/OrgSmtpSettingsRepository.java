package com.xetax.crm.settings.repository;

import com.xetax.crm.settings.entity.OrgSmtpSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrgSmtpSettingsRepository extends JpaRepository<OrgSmtpSettings, Long> {

    Optional<OrgSmtpSettings> findByOwnerUserId(String ownerUserId);
}
