package com.xetax.crm.whatsapp.repository;

import com.xetax.crm.whatsapp.entity.WhatsAppMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface WhatsAppMessageRepository extends JpaRepository<WhatsAppMessage, Long> {

    Optional<WhatsAppMessage> findByProviderMessageId(String providerMessageId);

    boolean existsByProviderMessageId(String providerMessageId);

    Page<WhatsAppMessage> findByConversationIdAndOwnerUserIdOrderByIdDesc(
            Long conversationId, String ownerUserId, Pageable pageable);

    List<WhatsAppMessage> findTop50ByRecordIdAndOwnerUserIdOrderByIdDesc(String recordId, String ownerUserId);

    /* -------- usage aggregation (whole rows never loaded, just counts) -------- */

    @Query("SELECT m.status, COUNT(m) FROM WhatsAppMessage m "
            + "WHERE m.ownerUserId = :owner AND m.direction = 'OUTBOUND' AND m.createdAt >= :from "
            + "GROUP BY m.status")
    List<Object[]> countOutboundByStatusSince(@Param("owner") String owner,
                                              @Param("from") LocalDateTime from);

    @Query("SELECT m.messageType, COUNT(m) FROM WhatsAppMessage m "
            + "WHERE m.ownerUserId = :owner AND m.direction = 'OUTBOUND' AND m.createdAt >= :from "
            + "GROUP BY m.messageType")
    List<Object[]> countOutboundByTypeSince(@Param("owner") String owner,
                                            @Param("from") LocalDateTime from);

    @Query("SELECT m.templateName, COUNT(m) FROM WhatsAppMessage m "
            + "WHERE m.ownerUserId = :owner AND m.direction = 'OUTBOUND' "
            + "AND m.templateName IS NOT NULL AND m.createdAt >= :from "
            + "GROUP BY m.templateName")
    List<Object[]> countOutboundByTemplateSince(@Param("owner") String owner,
                                                @Param("from") LocalDateTime from);

    long countByOwnerUserIdAndDirectionAndCreatedAtGreaterThanEqual(
            String ownerUserId, com.xetax.crm.whatsapp.enums.MessageDirection direction,
            LocalDateTime from);

    /**
     * Charge estimate, outbound side — only the columns the rules need, in this
     * order: id, conversationId, messageType, templateName, templateLanguage,
     * status, toPhone, sentAt, deliveredAt, readAt, createdAt.
     */
    @Query("SELECT m.id, m.conversationId, m.messageType, m.templateName, m.templateLanguage, "
            + "m.status, m.toPhone, m.sentAt, m.deliveredAt, m.readAt, m.createdAt "
            + "FROM WhatsAppMessage m WHERE m.whatsappConfigId = :configId "
            + "AND m.direction = 'OUTBOUND' AND m.createdAt >= :from")
    List<Object[]> outboundForCharges(@Param("configId") Long configId, @Param("from") LocalDateTime from);

    /** Charge estimate, inbound side: conversationId, deliveredAt (Meta's time), createdAt, referralSource. */
    @Query("SELECT m.conversationId, m.deliveredAt, m.createdAt, m.referralSource "
            + "FROM WhatsAppMessage m WHERE m.whatsappConfigId = :configId "
            + "AND m.direction = 'INBOUND' AND m.createdAt >= :from")
    List<Object[]> inboundForCharges(@Param("configId") Long configId, @Param("from") LocalDateTime from);

    /** Did this thread ever receive a campaign message? (bot scope = CAMPAIGN replies only) */
    boolean existsByConversationIdAndCampaignIdIsNotNull(Long conversationId);
    /** Public media link → the message that owns the file. */
    Optional<WhatsAppMessage> findByMediaKey(String mediaKey);
}
