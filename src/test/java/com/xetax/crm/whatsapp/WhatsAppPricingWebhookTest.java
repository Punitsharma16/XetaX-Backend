package com.xetax.crm.whatsapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xetax.crm.whatsapp.entity.WhatsAppMessage;
import com.xetax.crm.whatsapp.enums.MessageDirection;
import com.xetax.crm.whatsapp.enums.WhatsAppMessageStatus;
import com.xetax.crm.whatsapp.enums.WhatsAppMessageType;
import com.xetax.crm.whatsapp.repository.*;
import com.xetax.crm.whatsapp.service.WhatsAppMessagingService;
import com.xetax.crm.whatsapp.webhook.WhatsAppWebhookProcessor;

/**
 * What Meta says it charged, kept from its own webhook.
 *
 * <p>This matters from 1 October 2026. Until then a service message arrives
 * as billable false, type "free_customer_service"; from that day the same
 * message arrives as billable true, type "regular" — and the category stays
 * "service" either way. Anything that decides "free" by reading the category
 * goes on under-reporting after the change, with nothing to show it is wrong.
 */
class WhatsAppPricingWebhookTest {

    private WhatsAppMessageRepository messageRepository;
    private WhatsAppWebhookProcessor processor;
    private WhatsAppMessage message;

    @BeforeEach
    void setUp() {
        messageRepository = mock(WhatsAppMessageRepository.class);
        processor = new WhatsAppWebhookProcessor(
                mock(com.xetax.crm.notification.NotificationService.class),
                mock(com.xetax.crm.realtime.RealtimeHub.class),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(org.springframework.context.ApplicationEventPublisher.class),
                messageRepository,
                mock(WhatsAppConversationRepository.class),
                mock(WhatsAppConfigRepository.class),
                mock(WhatsAppTemplateRepository.class),
                mock(WhatsAppCampaignRepository.class),
                mock(WhatsAppCampaignRecipientRepository.class),
                mock(WhatsAppMessagingService.class),
                new ObjectMapper(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                mock(com.xetax.crm.whatsapp.service.WhatsAppMediaService.class));

        message = WhatsAppMessage.builder()
                .ownerUserId("u1").whatsappConfigId(1L)
                .direction(MessageDirection.OUTBOUND)
                .messageType(WhatsAppMessageType.TEXT)
                .providerMessageId("wamid.X")
                .status(WhatsAppMessageStatus.SENT)
                .build();
        when(messageRepository.findByProviderMessageId("wamid.X")).thenReturn(Optional.of(message));
    }

    private void fire(String status, String pricingJson) {
        String pricing = pricingJson == null ? "" : ",\"pricing\":" + pricingJson;
        processor.processRawValue("{\"statuses\":[{\"id\":\"wamid.X\",\"status\":\"" + status
                + "\",\"timestamp\":\"1755758000\"" + pricing + "}]}");
    }

    @Test
    @DisplayName("a free reply today is recorded as not billable")
    void freeServiceMessageBeforeTheChange() {
        fire("delivered", "{\"billable\":false,\"pricing_model\":\"PMP\","
                + "\"type\":\"free_customer_service\",\"category\":\"service\"}");

        assertEquals(Boolean.FALSE, message.getPricingBillable());
        assertEquals("service", message.getPricingCategory());
        assertEquals("free_customer_service", message.getPricingType());
        assertEquals("PMP", message.getPricingModel());
    }

    @Test
    @DisplayName("the same reply from 1 October is recorded as billable, category unchanged")
    void chargedServiceMessageAfterTheChange() {
        fire("delivered", "{\"billable\":true,\"pricing_model\":\"PMP\","
                + "\"type\":\"regular\",\"category\":\"service\"}");

        assertEquals(Boolean.TRUE, message.getPricingBillable(),
                "billable is the field that moves — this is what must be read");
        assertEquals("service", message.getPricingCategory(),
                "the category is identical to the free case, so it cannot decide anything");
        assertEquals("regular", message.getPricingType());
    }

    @Test
    @DisplayName("pricing on a status that moves nothing is still kept")
    void pricingOnAStaleStatusIsNotThrownAway() {
        // Meta often attaches pricing to "sent" — which arrives after
        // "delivered" has already been applied, or twice over.
        message.setStatus(WhatsAppMessageStatus.DELIVERED);

        fire("sent", "{\"billable\":true,\"type\":\"regular\",\"category\":\"service\"}");

        assertEquals(Boolean.TRUE, message.getPricingBillable());
        assertEquals(WhatsAppMessageStatus.DELIVERED, message.getStatus(), "the status did not move");
        verify(messageRepository, atLeastOnce()).save(any());
    }

    @Test
    @DisplayName("a charged template is recorded with its own category")
    void templatePricing() {
        fire("delivered", "{\"billable\":true,\"pricing_model\":\"PMP\","
                + "\"type\":\"regular\",\"category\":\"marketing\"}");

        assertEquals(Boolean.TRUE, message.getPricingBillable());
        assertEquals("marketing", message.getPricingCategory());
    }

    @Test
    @DisplayName("a webhook with no pricing leaves the message alone")
    void noPricingObject() {
        fire("delivered", null);

        assertNull(message.getPricingBillable());
        assertNull(message.getPricingCategory());
        assertEquals(WhatsAppMessageStatus.DELIVERED, message.getStatus(), "the status still moves");
    }

    @Test
    @DisplayName("half a pricing object records the half that is there")
    void partialPricingObject() {
        fire("delivered", "{\"billable\":true}");

        assertEquals(Boolean.TRUE, message.getPricingBillable());
        assertNull(message.getPricingCategory());
    }

    @Test
    @DisplayName("a later webhook corrects an earlier one")
    void laterPricingWins() {
        fire("sent", "{\"billable\":false,\"type\":\"free_customer_service\",\"category\":\"service\"}");
        assertEquals(Boolean.FALSE, message.getPricingBillable());

        fire("delivered", "{\"billable\":true,\"type\":\"regular\",\"category\":\"service\"}");
        assertEquals(Boolean.TRUE, message.getPricingBillable());
        assertTrue("regular".equals(message.getPricingType()));
    }
}
