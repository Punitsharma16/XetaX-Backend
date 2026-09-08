package com.xetax.crm.whatsapp.repository;

import com.xetax.crm.whatsapp.entity.WhatsAppCampaignRecipient;
import com.xetax.crm.whatsapp.enums.RecipientStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WhatsAppCampaignRecipientRepository extends JpaRepository<WhatsAppCampaignRecipient, Long> {

    Page<WhatsAppCampaignRecipient> findByCampaignIdOrderByIdAsc(Long campaignId, Pageable pageable);

    Page<WhatsAppCampaignRecipient> findByCampaignIdAndStatusOrderByIdAsc(
            Long campaignId, RecipientStatus status, Pageable pageable);

    List<WhatsAppCampaignRecipient> findByCampaignIdAndStatus(Long campaignId, RecipientStatus status);

    long countByCampaignIdAndStatusIn(Long campaignId, List<RecipientStatus> statuses);

    Optional<WhatsAppCampaignRecipient> findByProviderMessageId(String providerMessageId);
}
