package com.xetax.crm.emailcampaign.repository;

import com.xetax.crm.emailcampaign.entity.EmailTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EmailTemplateRepository extends JpaRepository<EmailTemplate, Long> {

    List<EmailTemplate> findByOwnerUserIdOrderByIdDesc(String ownerUserId);

    Optional<EmailTemplate> findByIdAndOwnerUserId(Long id, String ownerUserId);

    boolean existsByOwnerUserIdAndNameIgnoreCase(String ownerUserId, String name);

    Optional<EmailTemplate> findByOwnerUserIdAndNameIgnoreCase(String ownerUserId, String name);
}
