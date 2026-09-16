package com.xetax.crm.whatsapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xetax.crm.whatsapp.entity.WhatsAppConfig;
import com.xetax.crm.whatsapp.entity.WhatsAppConversation;
import com.xetax.crm.whatsapp.entity.WhatsAppMessage;
import com.xetax.crm.whatsapp.enums.WhatsAppMessageType;
import com.xetax.crm.whatsapp.repository.*;
import com.xetax.crm.whatsapp.service.WhatsAppMediaService;
import com.xetax.crm.whatsapp.service.WhatsAppMessagingService;
import com.xetax.crm.whatsapp.webhook.WhatsAppWebhookProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Every shape a customer can send. A thread must never show an empty bubble or
 * raw JSON, the message type must match what actually arrived, and a media
 * message must hand its id to the media service so the file gets a link.
 */
class WhatsAppInboundTypesTest {

    private WhatsAppMessageRepository messageRepository;
    private WhatsAppMediaService mediaService;
    private WhatsAppWebhookProcessor processor;

    @BeforeEach
    void setUp() {
        messageRepository = mock(WhatsAppMessageRepository.class);
        mediaService = mock(WhatsAppMediaService.class);

        WhatsAppConfig config = new WhatsAppConfig();
        config.setOwnerUserId("owner-1");
        config.setPhoneNumberId("PN1");

        WhatsAppConfigRepository configRepository = mock(WhatsAppConfigRepository.class);
        when(configRepository.findFirstByPhoneNumberId("PN1")).thenReturn(Optional.of(config));

        WhatsAppMessagingService messagingService = mock(WhatsAppMessagingService.class);
        when(messagingService.upsertConversation(any(), any(), any()))
                .thenReturn(mock(WhatsAppConversation.class));

        when(messageRepository.existsByProviderMessageId(any())).thenReturn(false);

        processor = new WhatsAppWebhookProcessor(
                mock(com.xetax.crm.notification.NotificationService.class),
                mock(com.xetax.crm.realtime.RealtimeHub.class),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(org.springframework.context.ApplicationEventPublisher.class),
                messageRepository,
                mock(WhatsAppConversationRepository.class),
                configRepository,
                mock(WhatsAppTemplateRepository.class),
                mock(WhatsAppCampaignRepository.class),
                mock(WhatsAppCampaignRecipientRepository.class),
                messagingService,
                new ObjectMapper(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                mediaService);
    }

    /** Feeds one inbound message through the webhook and returns the saved row. */
    private WhatsAppMessage inbound(String messageJson) {
        processor.processRawValue("""
                {"metadata":{"phone_number_id":"PN1"},
                 "contacts":[{"profile":{"name":"Asha"}}],
                 "messages":[%s]}""".formatted(messageJson));
        ArgumentCaptor<WhatsAppMessage> saved = ArgumentCaptor.forClass(WhatsAppMessage.class);
        verify(messageRepository).save(saved.capture());
        return saved.getValue();
    }

    /* ------------------------------------------------------------- the text */

    @Test
    void plainTextKeepsItsBody() {
        WhatsAppMessage m = inbound("""
                {"id":"w1","from":"919000000001","type":"text","text":{"body":"Hello there"}}""");
        assertEquals(WhatsAppMessageType.TEXT, m.getMessageType());
        assertEquals("Hello there", m.getBody());
    }

    @Test
    void quickReplyButtonShowsWhatTheyTapped() {
        WhatsAppMessage m = inbound("""
                {"id":"w2","from":"919000000001","type":"button","button":{"text":"Yes, confirm"}}""");
        assertEquals(WhatsAppMessageType.INTERACTIVE, m.getMessageType());
        assertEquals("Yes, confirm", m.getBody());
    }

    @Test
    void interactiveReplyShowsTheTitleNotTheJson() {
        WhatsAppMessage m = inbound("""
                {"id":"w3","from":"919000000001","type":"interactive",
                 "interactive":{"type":"button_reply","button_reply":{"id":"b1","title":"Book a demo"}}}""");
        assertEquals(WhatsAppMessageType.INTERACTIVE, m.getMessageType());
        assertEquals("Book a demo", m.getBody());
    }

    @Test
    void listReplyShowsTheRowTitle() {
        WhatsAppMessage m = inbound("""
                {"id":"w4","from":"919000000001","type":"interactive",
                 "interactive":{"type":"list_reply","list_reply":{"id":"r1","title":"Premium plan"}}}""");
        assertEquals("Premium plan", m.getBody());
    }

    /* ------------------------------------------------------------ the media */

    @Test
    void imageWithCaptionShowsTheCaptionAndFetchesTheFile() {
        WhatsAppMessage m = inbound("""
                {"id":"w5","from":"919000000001","type":"image",
                 "image":{"id":"MEDIA-IMG","mime_type":"image/jpeg","caption":"My broken tap"}}""");
        assertEquals(WhatsAppMessageType.IMAGE, m.getMessageType());
        assertEquals("My broken tap", m.getBody());
        assertEquals("MEDIA-IMG", m.getMediaId());
        verify(mediaService).fetchAfterCommit(any(), any(), eq("MEDIA-IMG"));
    }

    @Test
    void imageWithoutCaptionStillReadsAsSomething() {
        WhatsAppMessage m = inbound("""
                {"id":"w6","from":"919000000001","type":"image","image":{"id":"MEDIA-IMG2"}}""");
        assertEquals("[photo]", m.getBody());
    }

    @Test
    void documentShowsItsFilenameAndKeepsIt() {
        WhatsAppMessage m = inbound("""
                {"id":"w7","from":"919000000001","type":"document",
                 "document":{"id":"MEDIA-DOC","filename":"invoice-1042.pdf","mime_type":"application/pdf"}}""");
        assertEquals(WhatsAppMessageType.DOCUMENT, m.getMessageType());
        assertEquals("invoice-1042.pdf", m.getBody());
        assertEquals("invoice-1042.pdf", m.getMediaFilename());
        verify(mediaService).fetchAfterCommit(any(), any(), eq("MEDIA-DOC"));
    }

    @Test
    void documentCaptionWinsOverTheFilename() {
        WhatsAppMessage m = inbound("""
                {"id":"w8","from":"919000000001","type":"document",
                 "document":{"id":"MEDIA-DOC2","filename":"scan.pdf","caption":"Signed agreement"}}""");
        assertEquals("Signed agreement", m.getBody());
        assertEquals("scan.pdf", m.getMediaFilename());
    }

    @Test
    void videoIsAVideo() {
        WhatsAppMessage m = inbound("""
                {"id":"w9","from":"919000000001","type":"video",
                 "video":{"id":"MEDIA-VID","caption":"Site walkthrough"}}""");
        assertEquals(WhatsAppMessageType.VIDEO, m.getMessageType());
        assertEquals("Site walkthrough", m.getBody());
        verify(mediaService).fetchAfterCommit(any(), any(), eq("MEDIA-VID"));
    }

    @Test
    void voiceNoteIsLabelledAsOne() {
        WhatsAppMessage m = inbound("""
                {"id":"w10","from":"919000000001","type":"audio",
                 "audio":{"id":"MEDIA-AUD","voice":true,"mime_type":"audio/ogg"}}""");
        assertEquals(WhatsAppMessageType.AUDIO, m.getMessageType());
        assertEquals("[voice message]", m.getBody());
    }

    @Test
    void sharedAudioFileIsNotCalledAVoiceNote() {
        WhatsAppMessage m = inbound("""
                {"id":"w11","from":"919000000001","type":"audio","audio":{"id":"MEDIA-AUD2","voice":false}}""");
        assertEquals("[audio]", m.getBody());
    }

    @Test
    void stickerHasItsOwnType() {
        WhatsAppMessage m = inbound("""
                {"id":"w12","from":"919000000001","type":"sticker","sticker":{"id":"MEDIA-STK"}}""");
        assertEquals(WhatsAppMessageType.STICKER, m.getMessageType());
        assertEquals("[sticker]", m.getBody());
        verify(mediaService).fetchAfterCommit(any(), any(), eq("MEDIA-STK"));
    }

    /* ------------------------------------------------- everything else Meta sends */

    @Test
    void namedLocationShowsThePlace() {
        WhatsAppMessage m = inbound("""
                {"id":"w13","from":"919000000001","type":"location",
                 "location":{"latitude":28.61,"longitude":77.20,"name":"Connaught Place","address":"New Delhi"}}""");
        assertEquals(WhatsAppMessageType.LOCATION, m.getMessageType());
        assertEquals("[location] Connaught Place, New Delhi", m.getBody());
    }

    @Test
    void barePinFallsBackToCoordinates() {
        WhatsAppMessage m = inbound("""
                {"id":"w14","from":"919000000001","type":"location",
                 "location":{"latitude":28.61,"longitude":77.20}}""");
        assertTrue(m.getBody().startsWith("[location] 28.61,77.2"), m.getBody());
    }

    @Test
    void sharedContactShowsTheName() {
        WhatsAppMessage m = inbound("""
                {"id":"w15","from":"919000000001","type":"contacts",
                 "contacts":[{"name":{"formatted_name":"Ravi Kumar"}}]}""");
        assertEquals(WhatsAppMessageType.CONTACTS, m.getMessageType());
        assertEquals("[contact] Ravi Kumar", m.getBody());
    }

    @Test
    void reactionShowsTheEmoji() {
        WhatsAppMessage m = inbound("""
                {"id":"w16","from":"919000000001","type":"reaction",
                 "reaction":{"message_id":"w1","emoji":"👍"}}""");
        assertEquals(WhatsAppMessageType.REACTION, m.getMessageType());
        assertEquals("Reacted 👍", m.getBody());
    }

    @Test
    void removedReactionSaysSo() {
        WhatsAppMessage m = inbound("""
                {"id":"w17","from":"919000000001","type":"reaction","reaction":{"message_id":"w1","emoji":""}}""");
        assertEquals("[reaction removed]", m.getBody());
    }

    @Test
    void catalogueOrderCountsTheItems() {
        WhatsAppMessage m = inbound("""
                {"id":"w18","from":"919000000001","type":"order",
                 "order":{"catalog_id":"c1","product_items":[{"product_retailer_id":"p1"},{"product_retailer_id":"p2"}]}}""");
        assertEquals(WhatsAppMessageType.ORDER, m.getMessageType());
        assertEquals("[order] 2 items", m.getBody());
    }

    @Test
    void systemMessageKeepsItsText() {
        WhatsAppMessage m = inbound("""
                {"id":"w19","from":"919000000001","type":"system",
                 "system":{"body":"Asha changed their phone number","type":"user_changed_number"}}""");
        assertEquals(WhatsAppMessageType.SYSTEM, m.getMessageType());
        assertEquals("Asha changed their phone number", m.getBody());
    }

    @Test
    void unsupportedMessageExplainsItself() {
        WhatsAppMessage m = inbound("""
                {"id":"w20","from":"919000000001","type":"unsupported",
                 "errors":[{"code":131051,"title":"Message type is not currently supported"}]}""");
        assertEquals(WhatsAppMessageType.UNSUPPORTED, m.getMessageType());
        assertEquals("[unsupported] Message type is not currently supported", m.getBody());
    }

    @Test
    void aTextMessageNeverTriggersAMediaDownload() {
        inbound("""
                {"id":"w21","from":"919000000001","type":"text","text":{"body":"just text"}}""");
        verify(mediaService).fetchAfterCommit(any(), any(), eq(null));
    }

    @Test
    void anUnknownFutureTypeStillLands() {
        WhatsAppMessage m = inbound("""
                {"id":"w22","from":"919000000001","type":"something_new"}""");
        assertEquals(WhatsAppMessageType.TEXT, m.getMessageType());
        assertEquals("[something_new message]", m.getBody());
        assertNull(m.getMediaId());
    }
}
