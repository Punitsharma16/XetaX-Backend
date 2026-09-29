package com.xetax.crm.whatsapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xetax.crm.whatsapp.entity.WhatsAppCampaignRecipient;
import com.xetax.crm.whatsapp.entity.WhatsAppMessage;
import com.xetax.crm.whatsapp.enums.MessageDirection;
import com.xetax.crm.whatsapp.enums.RecipientStatus;
import com.xetax.crm.whatsapp.enums.WhatsAppMessageStatus;
import com.xetax.crm.whatsapp.enums.WhatsAppMessageType;
import com.xetax.crm.whatsapp.repository.*;
import com.xetax.crm.whatsapp.service.WhatsAppMessagingService;
import com.xetax.crm.whatsapp.webhook.WhatsAppWebhookProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * DLR idempotency: statuses only move forward, duplicates are no-ops,
 * campaign counters fire exactly once per transition.
 */
class WhatsAppWebhookProcessorTest {

    private WhatsAppMessageRepository messageRepository;
    private WhatsAppCampaignRepository campaignRepository;
    private WhatsAppCampaignRecipientRepository recipientRepository;
    private WhatsAppWebhookProcessor processor;

    private WhatsAppMessage message;

    @BeforeEach
    void setUp() {
        messageRepository = mock(WhatsAppMessageRepository.class);
        campaignRepository = mock(WhatsAppCampaignRepository.class);
        recipientRepository = mock(WhatsAppCampaignRecipientRepository.class);
        processor = new WhatsAppWebhookProcessor(
                mock(com.xetax.crm.notification.NotificationService.class),
                mock(com.xetax.crm.realtime.RealtimeHub.class),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(org.springframework.context.ApplicationEventPublisher.class),
                messageRepository,
                mock(WhatsAppConversationRepository.class),
                mock(WhatsAppConfigRepository.class),
                mock(com.xetax.crm.whatsapp.repository.WhatsAppTemplateRepository.class),
                campaignRepository,
                recipientRepository,
                mock(WhatsAppMessagingService.class),
                new ObjectMapper(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                mock(com.xetax.crm.whatsapp.service.WhatsAppMediaService.class));

        message = WhatsAppMessage.builder()
                .ownerUserId("u1")
                .whatsappConfigId(1L)
                .direction(MessageDirection.OUTBOUND)
                .messageType(WhatsAppMessageType.TEXT)
                .providerMessageId("wamid.X")
                .campaignId(7L)
                .status(WhatsAppMessageStatus.SENT)
                .build();
        when(messageRepository.findByProviderMessageId("wamid.X")).thenReturn(Optional.of(message));
        when(recipientRepository.findByProviderMessageId("wamid.X")).thenReturn(Optional.empty());
    }

    private void fire(String status) {
        processor.processRawValue(
                "{\"statuses\":[{\"id\":\"wamid.X\",\"status\":\"" + status + "\",\"timestamp\":\"1755758000\"}]}");
    }

    @Test
    void deliveredMovesForwardAndCountsOnce() {
        fire("delivered");
        assertEquals(WhatsAppMessageStatus.DELIVERED, message.getStatus());
        verify(campaignRepository, times(1)).markOneDelivered(7L);

        fire("delivered"); // duplicate DLR
        verify(campaignRepository, times(1)).markOneDelivered(7L);
    }

    @Test
    void staleStatusNeverRegresses() {
        message.setStatus(WhatsAppMessageStatus.READ);
        fire("delivered");
        assertEquals(WhatsAppMessageStatus.READ, message.getStatus());
        verify(messageRepository, never()).save(any());
    }

    @Test
    void readOnSentAlsoBackfillsDelivered() {
        fire("read");
        assertEquals(WhatsAppMessageStatus.READ, message.getStatus());
        verify(campaignRepository).markOneDelivered(7L);
        verify(campaignRepository).markOneRead(7L);
    }

    @Test
    void failedCarriesErrorDetails() {
        processor.processRawValue(
                "{\"statuses\":[{\"id\":\"wamid.X\",\"status\":\"failed\","
                + "\"errors\":[{\"code\":131047,\"title\":\"Re-engagement message\"}]}]}");
        assertEquals(WhatsAppMessageStatus.FAILED, message.getStatus());
        assertEquals("Re-engagement message", message.getErrorMessage());
    }

    @Test
    void recipientStatusFollowsDlr() {
        WhatsAppCampaignRecipient recipient = WhatsAppCampaignRecipient.builder()
                .campaignId(7L).phone("919876543210")
                .status(RecipientStatus.SENT).providerMessageId("wamid.X").build();
        when(recipientRepository.findByProviderMessageId("wamid.X"))
                .thenReturn(Optional.of(recipient));
        fire("delivered");
        assertEquals(RecipientStatus.DELIVERED, recipient.getStatus());
    }

    /* -------------------------------------------------------------------
     * A campaign's Failed card read 0 while the recipient list underneath it
     * showed failures. The recipient row was moved to FAILED by its own block,
     * but the campaign-counter switch right above only handled DELIVERED and
     * READ — a failed DLR fell through its default and nothing was counted.
     * ------------------------------------------------------------------- */

    @Test
    void aFailedReportRaisesTheCampaignsFailedCount() {
        fire("failed");

        assertEquals(WhatsAppMessageStatus.FAILED, message.getStatus());
        verify(campaignRepository).markOneFailedLate(eq(7L), anyInt(), anyInt(), anyInt());
    }

    @Test
    void aMessageThatFailsAfterBeingSentGivesTheSentCountBack() {
        fire("failed");   // the message was SENT when it arrived

        // Otherwise sent + failed adds up to more recipients than the campaign
        // has, while the list shows each recipient exactly once.
        verify(campaignRepository).markOneFailedLate(7L, 1, 0, 0);
    }

    @Test
    void aMessageThatFailsAfterDeliveryGivesBothCountsBack() {
        message.setStatus(WhatsAppMessageStatus.DELIVERED);

        fire("failed");

        verify(campaignRepository).markOneFailedLate(7L, 1, 1, 0);
    }

    @Test
    void aMessageThatFailsAfterBeingReadGivesAllThreeBack() {
        message.setStatus(WhatsAppMessageStatus.READ);

        fire("failed");

        verify(campaignRepository).markOneFailedLate(7L, 1, 1, 1);
    }

    @Test
    void theQueueIsLeftAloneOnALateFailure() {
        fire("failed");

        // markOneFailed also decrements queuedCount, and this recipient left
        // the queue when it was sent.
        verify(campaignRepository, never()).markOneFailed(anyLong());
    }

    @Test
    void aRepeatedFailedReportCountsOnce() {
        fire("failed");
        fire("failed");

        verify(campaignRepository, times(1)).markOneFailedLate(eq(7L), anyInt(), anyInt(), anyInt());
    }

    @Test
    void aFailureOutsideACampaignTouchesNoCounters() {
        message.setCampaignId(null);

        fire("failed");

        assertEquals(WhatsAppMessageStatus.FAILED, message.getStatus());
        verify(campaignRepository, never()).markOneFailedLate(anyLong(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void poisonPayloadNeverThrows() {
        processor.processRawValue("this is not json");
        processor.processRawValue("{\"statuses\":[{}]}");
    }
}
