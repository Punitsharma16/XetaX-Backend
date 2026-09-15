package com.xetax.crm.settings.repository;

import com.xetax.crm.settings.entity.DigestPreference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DigestPreferenceRepository extends JpaRepository<DigestPreference, Long> {

    Optional<DigestPreference> findByOwnerUserId(String ownerUserId);
}
