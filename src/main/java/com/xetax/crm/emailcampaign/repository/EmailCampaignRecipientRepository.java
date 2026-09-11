package com.xetax.crm.emailcampaign.repository;

import com.xetax.crm.emailcampaign.entity.EmailCampaignRecipient;
import com.xetax.crm.emailcampaign.enums.EmailRecipientStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EmailCampaignRecipientRepository extends JpaRepository<EmailCampaignRecipient, Long> {

    Page<EmailCampaignRecipient> findByCampaignIdOrderByIdAsc(Long campaignId, Pageable pageable);

    Page<EmailCampaignRecipient> findByCampaignIdAndStatusOrderByIdAsc(
            Long campaignId, EmailRecipientStatus status, Pageable pageable);

    List<EmailCampaignRecipient> findByCampaignIdAndStatus(Long campaignId, EmailRecipientStatus status);

    long countByCampaignIdAndStatusIn(Long campaignId, List<EmailRecipientStatus> statuses);
}
