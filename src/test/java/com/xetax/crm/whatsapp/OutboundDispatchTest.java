package com.xetax.crm.whatsapp;

import com.xetax.crm.whatsapp.client.WhatsAppSendResult;
import com.xetax.crm.whatsapp.client.WhatsAppSender;
import com.xetax.crm.whatsapp.config.MetaWhatsAppProperties;
import com.xetax.crm.whatsapp.dto.SendMessageRequest;
import com.xetax.crm.whatsapp.entity.WhatsAppConfig;
import com.xetax.crm.whatsapp.entity.WhatsAppConversation;
import com.xetax.crm.whatsapp.entity.WhatsAppMessage;
import com.xetax.crm.whatsapp.entity.WhatsAppTemplate;
import com.xetax.crm.whatsapp.enums.WhatsAppConnectionStatus;
import com.xetax.crm.whatsapp.enums.WhatsAppMessageStatus;
import com.xetax.crm.whatsapp.repository.WhatsAppConfigRepository;
import com.xetax.crm.whatsapp.repository.WhatsAppConversationRepository;
import com.xetax.crm.whatsapp.repository.WhatsAppMessageRepository;
import com.xetax.crm.whatsapp.repository.WhatsAppTemplateRepository;
import com.xetax.crm.whatsapp.service.PhoneNumberService;
import com.xetax.crm.whatsapp.service.TemplateFlowTokens;
import com.xetax.crm.whatsapp.service.WhatsAppConfigService;
import com.xetax.crm.whatsapp.service.WhatsAppMediaService;
import com.xetax.crm.whatsapp.service.WhatsAppMessagingService;
import com.xetax.crm.whatsapp.service.WhatsAppTemplateVariables;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A send queues the message and nothing else: Meta is called by the executor
 * after the transaction commits, never on the request's own thread.
 *
 * <p>This is what made the record page answer 409 CONFLICT. Dispatch was
 * invoked on {@code this}, so Spring's @Async proxy never saw it: the provider
 * call and the writes that follow it ran inside the caller's transaction, and
 * any constraint or column-size violation there came back as the send's own
 * error — with a message about conflicting data that had nothing to do with
 * sending a WhatsApp template.
 */
class OutboundDispatchTest {

    private WhatsAppMessageRepository messages;
    private WhatsAppConversationRepository conversations;
    private WhatsAppTemplateRepository templates;
    private WhatsAppSender sender;
    private WhatsAppMessagingService proxy;
    private WhatsAppMessagingService service;
    private WhatsAppMessage queued;

    private static final String OWNER = "11111111-1111-1111-1111-111111111111";

    @BeforeEach
    void setUp() {
        messages = mock(WhatsAppMessageRepository.class);
        conversations = mock(WhatsAppConversationRepository.class);
        templates = mock(WhatsAppTemplateRepository.class);
        sender = mock(WhatsAppSender.class);
        proxy = mock(WhatsAppMessagingService.class);

        WhatsAppConfig config = new WhatsAppConfig();
        config.setId(7L);
        config.setOwnerUserId(OWNER);
        config.setStatus(WhatsAppConnectionStatus.CONNECTED);

        WhatsAppConfigService configService = mock(WhatsAppConfigService.class);
        when(configService.requireConnectedConfig()).thenReturn(config);

        WhatsAppConfigRepository configRepository = mock(WhatsAppConfigRepository.class);
        when(configRepository.findById(7L)).thenReturn(Optional.of(config));

        when(conversations.findByWhatsappConfigIdAndCustomerPhone(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        when(conversations.save(any())).thenAnswer(call -> {
            WhatsAppConversation saved = call.getArgument(0);
            saved.setId(31L);
            return saved;
        });
        when(messages.save(any())).thenAnswer(call -> {
            WhatsAppMessage saved = call.getArgument(0);
            if (saved.getId() == null) saved.setId(99L);
            queued = saved;
            return saved;
        });
        // The dispatcher looks the message up by id: findable, so a dispatch on
        // the caller's thread would reach Meta and be caught by this test.
        when(messages.findById(99L)).thenAnswer(call -> Optional.ofNullable(queued));
        when(sender.sendTemplate(any(), anyString(), anyString(), anyString(), any()))
                .thenReturn(WhatsAppSendResult.ok("wamid.TEST"));

        WhatsAppTemplate template = new WhatsAppTemplate();
        template.setId(3L);
        template.setName("image_template");
        template.setLanguage("en");
        template.setCategory("MARKETING");
        template.setHeaderFormat("IMAGE");
        template.setHeaderMediaUrl("https://example.com/promo.jpg");
        template.setComponentsJson(
                "[{\"type\":\"HEADER\",\"format\":\"IMAGE\"},{\"type\":\"BODY\",\"text\":\"Hi {{1}}\"}]");
        when(templates.findByWhatsappConfigIdAndNameAndLanguage(7L, "image_template", "en"))
                .thenReturn(Optional.of(template));

        MetaWhatsAppProperties properties = new MetaWhatsAppProperties();
        com.xetax.crm.common.ratelimit.RateLimiterService rateLimiter =
                mock(com.xetax.crm.common.ratelimit.RateLimiterService.class);
        when(rateLimiter.allow(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                any(java.time.Duration.class))).thenReturn(true);

        ObjectProvider<WhatsAppMessagingService> self = mock(ObjectProvider.class);
        when(self.getObject()).thenReturn(proxy);

        service = new WhatsAppMessagingService(messages, conversations, configRepository,
                configService, sender, new PhoneNumberService(properties), rateLimiter,
                properties, null, new SimpleMeterRegistry(), templates,
                new WhatsAppTemplateVariables(new com.fasterxml.jackson.databind.ObjectMapper()),
                mock(WhatsAppMediaService.class), mock(TemplateFlowTokens.class), self);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private SendMessageRequest templateSend() {
        SendMessageRequest request = new SendMessageRequest();
        request.setPhone("9896458807");
        request.setTemplateName("image_template");
        request.setTemplateLanguage("en");
        request.setRecordId("6aa8d720e6f1b75bde3b3850");
        request.setTemplateVariables(
                com.xetax.crm.whatsapp.dto.TemplateVariables.ofBody(List.of("Vanshu")));
        return request;
    }

    @Test
    void sendQueuesWithoutCallingMeta() {
        TransactionSynchronizationManager.initSynchronization();

        var response = service.send(templateSend());

        assertNotNull(response);
        // The request's own thread must not talk to Meta — that is what pulled
        // the provider call, and every write after it, into this transaction.
        verifyNoInteractions(sender);
        verify(proxy, never()).dispatchAsync(anyLong(), anyString());
        assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());
    }

    @Test
    void dispatchRunsOnceTheTransactionHasCommitted() {
        TransactionSynchronizationManager.initSynchronization();

        service.send(templateSend());
        List<TransactionSynchronization> registered =
                TransactionSynchronizationManager.getSynchronizations();
        registered.forEach(TransactionSynchronization::afterCommit);

        // Through the proxy, so @Async applies and the executor picks it up.
        verify(proxy, times(1)).dispatchAsync(eq(99L), anyString());
    }

    @Test
    void dispatchesStraightAwayWithNoTransaction() {
        service.send(templateSend());

        verify(proxy, times(1)).dispatchAsync(eq(99L), anyString());
    }

    /**
     * Meta answers a retried send with the id it already gave — our own client
     * retries a timeout, so this arrives in the ordinary course of business.
     * The id is unique in our table, and letting the duplicate through failed
     * the whole dispatch: the customer had the message and the CRM called it
     * FAILED (and, before dispatch moved off the request thread, answered the
     * send with 409 CONFLICT).
     */
    @Test
    void aProviderIdMetaHasAlreadyGivenUsDoesNotFailTheSend() {
        WhatsAppMessage earlier = WhatsAppMessage.builder()
                .ownerUserId(OWNER).whatsappConfigId(7L)
                .status(WhatsAppMessageStatus.SENT).providerMessageId("wamid.SAME").build();
        earlier.setId(50L);
        WhatsAppMessage retried = WhatsAppMessage.builder()
                .ownerUserId(OWNER).whatsappConfigId(7L)
                .status(WhatsAppMessageStatus.QUEUED).build();
        retried.setId(99L);
        queued = retried;
        when(messages.findByProviderMessageId("wamid.SAME")).thenReturn(Optional.of(earlier));

        service.applySendResult(99L, WhatsAppSendResult.ok("wamid.SAME"));

        assertEquals(WhatsAppMessageStatus.SENT, retried.getStatus());
        // The id stays where it landed first, so status webhooks still match.
        assertNull(retried.getProviderMessageId());
        assertEquals("wamid.SAME", earlier.getProviderMessageId());
    }

    @Test
    void anIdNobodyElseHasIsKept() {
        WhatsAppMessage message = WhatsAppMessage.builder()
                .ownerUserId(OWNER).whatsappConfigId(7L)
                .status(WhatsAppMessageStatus.QUEUED).build();
        message.setId(99L);
        queued = message;
        when(messages.findByProviderMessageId("wamid.NEW")).thenReturn(Optional.empty());

        service.applySendResult(99L, WhatsAppSendResult.ok("wamid.NEW"));

        assertEquals("wamid.NEW", message.getProviderMessageId());
        assertEquals(WhatsAppMessageStatus.SENT, message.getStatus());
    }

    /**
     * error_message is 500 characters and error_code 32. MySQL refuses a longer
     * value instead of cutting it, and that refusal is a data-integrity error —
     * the same 409 the user saw, caused by nothing but a wordy reply from Meta.
     */
    @Test
    void longProviderErrorIsTrimmedToTheColumn() {
        queued = WhatsAppMessage.builder()
                .ownerUserId(OWNER).whatsappConfigId(7L)
                .status(WhatsAppMessageStatus.QUEUED).build();
        queued.setId(99L);

        service.applySendResult(99L, WhatsAppSendResult.failed(
                "META_".repeat(20), "x".repeat(900)));

        assertEquals(WhatsAppMessageStatus.FAILED, queued.getStatus());
        assertEquals(32, queued.getErrorCode().length());
        assertEquals(500, queued.getErrorMessage().length());
        assertTrue(queued.getErrorMessage().startsWith("xxx"));
    }
}
