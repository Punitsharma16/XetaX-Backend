package com.xetax.crm.whatsapp;

import com.xetax.crm.whatsapp.entity.WhatsAppCampaign;
import com.xetax.crm.whatsapp.entity.WhatsAppCampaignRecipient;
import com.xetax.crm.whatsapp.enums.CampaignStatus;
import com.xetax.crm.whatsapp.enums.RecipientStatus;
import com.xetax.crm.whatsapp.repository.WhatsAppCampaignRecipientRepository;
import com.xetax.crm.whatsapp.repository.WhatsAppCampaignRepository;
import com.xetax.crm.whatsapp.repository.WhatsAppConfigRepository;
import com.xetax.crm.whatsapp.service.WhatsAppCampaignService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How a campaign ends is read off the campaign page: "completed" is taken as
 * "the messages went out". A run whose every recipient failed was being closed
 * as COMPLETED, because the closing check read tallies the bulk counter update
 * had not made visible to this entity yet.
 */
class CampaignOutcomeTest {

    private WhatsAppCampaignRepository campaigns;
    private WhatsAppCampaignRecipientRepository recipients;
    private WhatsAppCampaignService service;
    private WhatsAppCampaign campaign;

    @BeforeEach
    void setUp() {
        campaigns = mock(WhatsAppCampaignRepository.class);
        recipients = mock(WhatsAppCampaignRecipientRepository.class);
        WhatsAppConfigRepository configs = mock(WhatsAppConfigRepository.class);

        campaign = new WhatsAppCampaign();
        campaign.setId(6L);
        campaign.setStatus(CampaignStatus.RUNNING);
        campaign.setWhatsappConfigId(7L);
        when(campaigns.findById(6L)).thenReturn(Optional.of(campaign));
        // The number never connected: every recipient fails before Meta is called.
        when(configs.findById(7L)).thenReturn(Optional.empty());

        WhatsAppCampaignRecipient recipient = new WhatsAppCampaignRecipient();
        recipient.setId(11L);
        recipient.setCampaignId(6L);
        recipient.setPhone("919896458807");
        recipient.setStatus(RecipientStatus.QUEUED);
        when(recipients.findById(11L)).thenReturn(Optional.of(recipient));
        when(recipients.save(any())).thenAnswer(call -> call.getArgument(0));

        service = new WhatsAppCampaignService(campaigns, recipients, configs,
                mock(com.xetax.crm.whatsapp.service.WhatsAppConfigService.class),
                mock(com.xetax.crm.whatsapp.service.WhatsAppMessagingService.class),
                mock(com.xetax.crm.whatsapp.service.PhoneNumberService.class),
                mock(com.xetax.crm.data_manager.service.RecordService.class),
                mock(com.xetax.crm.whatsapp.kafka.WhatsAppEventPublisher.class),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                mock(com.xetax.crm.ai.rag.KnowledgeIndexer.class),
                mock(com.xetax.crm.whatsapp.repository.WhatsAppTemplateRepository.class),
                mock(com.xetax.crm.whatsapp.service.WhatsAppTemplateVariables.class));
    }

    /** Nothing is left open; the rows say one failed and none were sent. */
    private void recipientRowsSay(long failed, long sent) {
        when(recipients.countByCampaignIdAndStatusIn(anyLong(), any())).thenAnswer(call -> {
            List<?> statuses = call.getArgument(1);
            if (statuses.contains(RecipientStatus.PENDING)) return 0L;
            if (statuses.contains(RecipientStatus.FAILED)) return failed;
            return sent;
        });
    }

    @Test
    void aRunWhereEveryMessageFailedIsNotCalledCompleted() {
        recipientRowsSay(1, 0);

        service.processRecipient(11L);

        ArgumentCaptor<WhatsAppCampaign> saved = ArgumentCaptor.forClass(WhatsAppCampaign.class);
        verify(campaigns, times(1)).save(saved.capture());
        assertEquals(CampaignStatus.FAILED, saved.getValue().getStatus());
    }

    @Test
    void aRunWithBothOutcomesIsPartial() {
        recipientRowsSay(1, 2);

        service.processRecipient(11L);

        ArgumentCaptor<WhatsAppCampaign> saved = ArgumentCaptor.forClass(WhatsAppCampaign.class);
        verify(campaigns).save(saved.capture());
        assertEquals(CampaignStatus.PARTIAL, saved.getValue().getStatus());
    }

    @Test
    void theFailureIsCountedOnTheCampaign() {
        recipientRowsSay(1, 0);

        service.processRecipient(11L);

        verify(campaigns).markOneFailed(6L);
    }
}
