package com.xetax.crm.whatsapp.repository;

import com.xetax.crm.whatsapp.entity.WhatsAppCampaign;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.xetax.crm.whatsapp.enums.CampaignStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface WhatsAppCampaignRepository extends JpaRepository<WhatsAppCampaign, Long> {

    Optional<WhatsAppCampaign> findByIdAndOwnerUserId(Long id, String ownerUserId);

    Page<WhatsAppCampaign> findByOwnerUserIdOrderByIdDesc(String ownerUserId, Pageable pageable);

    List<WhatsAppCampaign> findByStatusAndScheduledAtLessThanEqual(CampaignStatus status, Instant now);

    /* Atomic counter moves — safe under concurrent Kafka consumers. */

    @Transactional
    @Modifying
    @Query("UPDATE WhatsAppCampaign c SET c.sentCount = c.sentCount + 1, c.queuedCount = c.queuedCount - 1 WHERE c.id = :id")
    int markOneSent(@Param("id") Long id);

    @Transactional
    @Modifying
    @Query("UPDATE WhatsAppCampaign c SET c.failedCount = c.failedCount + 1, c.queuedCount = c.queuedCount - 1 WHERE c.id = :id")
    int markOneFailed(@Param("id") Long id);

    @Transactional
    @Modifying
    @Query("UPDATE WhatsAppCampaign c SET c.deliveredCount = c.deliveredCount + 1 WHERE c.id = :id")
    int markOneDelivered(@Param("id") Long id);

    @Transactional
    @Modifying
    @Query("UPDATE WhatsAppCampaign c SET c.readCount = c.readCount + 1 WHERE c.id = :id")
    int markOneRead(@Param("id") Long id);
}
