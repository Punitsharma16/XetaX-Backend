package com.xetax.crm.whatsapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xetax.crm.ai.rag.KnowledgeIndexer;
import com.xetax.crm.data_manager.service.RecordService;
import com.xetax.crm.whatsapp.entity.WhatsAppCampaign;
import com.xetax.crm.whatsapp.entity.WhatsAppCampaignRecipient;
import com.xetax.crm.whatsapp.entity.WhatsAppConfig;
import com.xetax.crm.whatsapp.entity.WhatsAppMessage;
import com.xetax.crm.whatsapp.enums.CampaignSourceType;
import com.xetax.crm.whatsapp.enums.CampaignStatus;
import com.xetax.crm.whatsapp.enums.RecipientStatus;
import com.xetax.crm.whatsapp.enums.WhatsAppConnectionStatus;
import com.xetax.crm.whatsapp.kafka.WhatsAppEventPublisher;
import com.xetax.crm.whatsapp.repository.WhatsAppCampaignRecipientRepository;
import com.xetax.crm.whatsapp.repository.WhatsAppCampaignRepository;
import com.xetax.crm.whatsapp.repository.WhatsAppConfigRepository;
import com.xetax.crm.whatsapp.repository.WhatsAppTemplateRepository;
import com.xetax.crm.whatsapp.service.PhoneNumberService;
import com.xetax.crm.whatsapp.service.WhatsAppCampaignService;
import com.xetax.crm.whatsapp.service.WhatsAppConfigService;
import com.xetax.crm.whatsapp.service.WhatsAppMessagingService;
import com.xetax.crm.whatsapp.service.WhatsAppTemplateVariables;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Starting a 100-recipient campaign left the panel on a loader until every
 * message had gone out.
 *
 * <p>When Kafka is unavailable the batch falls back to the whatsappExecutor —
 * except the fallback was invoked as {@code processRecipientsAsync(fallback)},
 * a call on {@code this}. Spring's @Async is proxy-based, so a self-call never
 * reaches the proxy and the annotation did nothing: all of it ran on the HTTP
 * request thread, inside start()'s transaction, each send waiting for a
 * 60-per-minute rate slot. A campaign big enough to outlast the browser's
 * timeout then read as a failure while the messages kept going out.
 */
class CampaignDispatchTest {

    private static final String OWNER = "11111111-1111-1111-1111-111111111111";

    private WhatsAppCampaignRepository campaigns;
    private WhatsAppCampaignRecipientRepository recipients;
    private WhatsAppEventPublisher eventPublisher;
    private WhatsAppMessagingService messagingService;
    private WhatsAppConfigRepository configRepository;

    /** The bean as Spring hands it out — what @Async actually decorates. */
    private WhatsAppCampaignService proxy;
    private WhatsAppCampaignService service;

    private WhatsAppCampaign campaign;

    @BeforeEach
    void setUp() {
        campaigns = mock(WhatsAppCampaignRepository.class);
        recipients = mock(WhatsAppCampaignRecipientRepository.class);
        eventPublisher = mock(WhatsAppEventPublisher.class);
        messagingService = mock(WhatsAppMessagingService.class);
        proxy = mock(WhatsAppCampaignService.class);
        configRepository = mock(WhatsAppConfigRepository.class);

        WhatsAppConfigService configService = mock(WhatsAppConfigService.class);
        when(configService.currentUserId()).thenReturn(OWNER);

        campaign = new WhatsAppCampaign();
        campaign.setId(6L);
        campaign.setOwnerUserId(OWNER);
        campaign.setName("Diwali offer");
        campaign.setStatus(CampaignStatus.DRAFT);
        campaign.setSourceType(CampaignSourceType.RECORDS);
        campaign.setWhatsappConfigId(7L);

        when(campaigns.findByIdAndOwnerUserId(6L, OWNER)).thenReturn(Optional.of(campaign));
        when(campaigns.save(any())).thenAnswer(call -> call.getArgument(0));
        when(recipients.save(any())).thenAnswer(call -> call.getArgument(0));
        when(recipients.findByCampaignIdAndStatus(6L, RecipientStatus.PENDING))
                .thenReturn(threeRecipients());

        @SuppressWarnings("unchecked")
        ObjectProvider<WhatsAppCampaignService> self = mock(ObjectProvider.class);
        when(self.getObject()).thenReturn(proxy);

        service = new WhatsAppCampaignService(campaigns, recipients,
                configRepository, configService, messagingService,
                mock(PhoneNumberService.class), mock(RecordService.class), eventPublisher,
                new ObjectMapper(), mock(KnowledgeIndexer.class),
                mock(WhatsAppTemplateRepository.class), mock(WhatsAppTemplateVariables.class),
                self);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static List<WhatsAppCampaignRecipient> threeRecipients() {
        List<WhatsAppCampaignRecipient> list = new ArrayList<>();
        for (long id = 11L; id <= 13L; id++) {
            WhatsAppCampaignRecipient recipient = new WhatsAppCampaignRecipient();
            recipient.setId(id);
            recipient.setCampaignId(6L);
            recipient.setPhone("91989645880" + id);
            recipient.setStatus(RecipientStatus.PENDING);
            list.add(recipient);
        }
        return list;
    }

    /** Broker down: every publish is refused, so the whole batch falls back. */
    private void kafkaIsDown() {
        when(eventPublisher.publishCampaignSend(anyLong(), anyString())).thenReturn(false);
    }

    // ------------------------------------------------------ the actual fix

    @Test
    void theFallbackBatchGoesToTheExecutorNotTheRequestThread() {
        kafkaIsDown();

        service.start(6L, null);

        // Through the proxy — the only route on which @Async is honoured.
        verify(proxy).processRecipientsAsync(List.of(11L, 12L, 13L));
    }

    /**
     * Everything a recipient needs in order to actually be sent, so that an
     * inline run would reach Meta. Without this the recipient falls out early
     * — no connected number — and the test would pass even with the bug.
     */
    private void theNumberIsConnectedAndReadyToSend() {
        WhatsAppConfig config = new WhatsAppConfig();
        config.setId(7L);
        config.setOwnerUserId(OWNER);
        config.setStatus(WhatsAppConnectionStatus.CONNECTED);
        when(configRepository.findById(7L)).thenReturn(Optional.of(config));

        campaign.setMessageTemplate("Hello");
        when(messagingService.isWindowOpen(anyLong(), anyString())).thenReturn(true);

        WhatsAppMessage queued = new WhatsAppMessage();
        queued.setId(99L);
        when(messagingService.queueOutbound(any(), anyString(), any(), any(), any(),
                any(), any(), any(), anyLong())).thenReturn(queued);
    }

    @Test
    void startingACampaignSendsNothingOnTheCallersThread() {
        kafkaIsDown();
        theNumberIsConnectedAndReadyToSend();

        service.start(6L, null);

        // The request hands the batch over and returns. Meta is reached by the
        // executor afterwards, never while the browser is still waiting.
        verify(messagingService, never()).dispatchNow(anyLong(), any());
        verify(messagingService, never()).queueOutbound(any(), anyString(), any(),
                any(), any(), any(), any(), any(), anyLong());
        verify(proxy).processRecipientsAsync(List.of(11L, 12L, 13L));
    }

    @Test
    void nothingIsDispatchedUntilTheTransactionCommits() {
        kafkaIsDown();
        TransactionSynchronizationManager.initSynchronization();

        service.start(6L, null);

        verify(proxy, never()).processRecipientsAsync(any());

        for (TransactionSynchronization sync :
                new ArrayList<>(TransactionSynchronizationManager.getSynchronizations())) {
            sync.afterCommit();
        }

        // The worker finds each recipient by id; those rows only exist for it
        // once the transaction that queued them has committed.
        verify(proxy).processRecipientsAsync(List.of(11L, 12L, 13L));
    }

    @Test
    void aWorkingBrokerNeedsNoFallbackAtAll() {
        when(eventPublisher.publishCampaignSend(anyLong(), anyString())).thenReturn(true);

        service.start(6L, null);

        verify(proxy, never()).processRecipientsAsync(any());
    }

    // ------------------------------------------------- unchanged behaviour

    @Test
    void theCampaignStillGoesRunningWithEveryoneQueued() {
        kafkaIsDown();

        service.start(6L, null);

        assertEquals(CampaignStatus.RUNNING, campaign.getStatus());
        assertEquals(3, campaign.getQueuedCount());
        verify(eventPublisher, times(3)).publishCampaignSend(anyLong(), anyString());
    }
}
