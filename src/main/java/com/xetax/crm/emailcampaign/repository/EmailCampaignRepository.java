package com.xetax.crm.emailcampaign.repository;

import com.xetax.crm.emailcampaign.entity.EmailCampaign;
import com.xetax.crm.emailcampaign.enums.EmailCampaignStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface EmailCampaignRepository extends JpaRepository<EmailCampaign, Long> {

    Optional<EmailCampaign> findByIdAndOwnerUserId(Long id, String ownerUserId);

    Page<EmailCampaign> findByOwnerUserIdOrderByIdDesc(String ownerUserId, Pageable pageable);

    List<EmailCampaign> findByStatusAndScheduledAtLessThanEqual(EmailCampaignStatus status, Instant now);

    /* Atomic counter moves — safe under concurrent Kafka consumers. */

    @Transactional
    @Modifying
    @Query("UPDATE EmailCampaign c SET c.sentCount = c.sentCount + 1, c.queuedCount = c.queuedCount - 1 WHERE c.id = :id")
    int markOneSent(@Param("id") Long id);

    @Transactional
    @Modifying
    @Query("UPDATE EmailCampaign c SET c.failedCount = c.failedCount + 1, c.queuedCount = c.queuedCount - 1 WHERE c.id = :id")
    int markOneFailed(@Param("id") Long id);
}
