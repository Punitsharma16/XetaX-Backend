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
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE WhatsAppCampaign c SET c.sentCount = c.sentCount + 1, c.queuedCount = c.queuedCount - 1 WHERE c.id = :id")
    int markOneSent(@Param("id") Long id);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE WhatsAppCampaign c SET c.failedCount = c.failedCount + 1, c.queuedCount = c.queuedCount - 1 WHERE c.id = :id")
    int markOneFailed(@Param("id") Long id);

    /**
     * A message Meta accepted and then reported undeliverable.
     *
     * <p>Not markOneFailed: that one also decrements queuedCount, and this
     * recipient left the queue when it was sent. The success buckets it was
     * already tallied in are given back, because the recipient list shows one
     * status per row — counting it as both sent and failed made the cards add
     * up to more than the campaign had recipients.
     *
     * @param sentBack      1 if it had been counted as sent, else 0
     * @param deliveredBack 1 if it had been counted as delivered, else 0
     * @param readBack      1 if it had been counted as read, else 0
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE WhatsAppCampaign c SET c.failedCount = c.failedCount + 1,"
            + " c.sentCount = CASE WHEN c.sentCount >= :sentBack THEN c.sentCount - :sentBack ELSE 0 END,"
            + " c.deliveredCount = CASE WHEN c.deliveredCount >= :deliveredBack"
            + " THEN c.deliveredCount - :deliveredBack ELSE 0 END,"
            + " c.readCount = CASE WHEN c.readCount >= :readBack THEN c.readCount - :readBack ELSE 0 END"
            + " WHERE c.id = :id")
    int markOneFailedLate(@Param("id") Long id,
                          @Param("sentBack") int sentBack,
                          @Param("deliveredBack") int deliveredBack,
                          @Param("readBack") int readBack);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE WhatsAppCampaign c SET c.deliveredCount = c.deliveredCount + 1 WHERE c.id = :id")
    int markOneDelivered(@Param("id") Long id);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE WhatsAppCampaign c SET c.readCount = c.readCount + 1 WHERE c.id = :id")
    int markOneRead(@Param("id") Long id);
}
