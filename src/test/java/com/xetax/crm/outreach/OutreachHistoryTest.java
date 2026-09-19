package com.xetax.crm.outreach;

import com.xetax.crm.auth.security.CurrentUserProvider;
import com.xetax.crm.contact.EmailLogRepository;
import com.xetax.crm.settings.service.OrgSmtpService;
import com.xetax.crm.team.service.PermissionService;
import com.xetax.crm.whatsapp.config.MetaWhatsAppProperties;
import com.xetax.crm.whatsapp.entity.WhatsAppConfig;
import com.xetax.crm.whatsapp.entity.WhatsAppConversation;
import com.xetax.crm.whatsapp.entity.WhatsAppMessage;
import com.xetax.crm.whatsapp.enums.MessageDirection;
import com.xetax.crm.whatsapp.enums.WhatsAppMessageStatus;
import com.xetax.crm.whatsapp.enums.WhatsAppMessageType;
import com.xetax.crm.whatsapp.repository.WhatsAppConfigRepository;
import com.xetax.crm.whatsapp.repository.WhatsAppConversationRepository;
import com.xetax.crm.whatsapp.repository.WhatsAppMessageRepository;
import com.xetax.crm.whatsapp.service.PhoneNumberService;
import com.xetax.crm.whatsapp.service.WhatsAppMediaService;
import com.xetax.crm.whatsapp.service.WhatsAppMessagingService;
import com.xetax.crm.whatsapp.service.WhatsAppTemplateVariables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The chat a record page shows carries why a send failed. Beside it, the email
 * history has always shown its reason; a WhatsApp message only said "FAILED",
 * so nobody could tell a wrong number from an unapproved template without
 * opening the inbox — or the database.
 */
class OutreachHistoryTest {

    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private WhatsAppMessageRepository messages;
    private OutreachService service;

    @BeforeEach
    void setUp() {
        CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
        when(currentUser.currentDataOwnerIdOrNull()).thenReturn(OWNER);

        WhatsAppConfig config = new WhatsAppConfig();
        config.setId(7L);
        config.setOwnerUserId(OWNER.toString());

        WhatsAppConfigRepository configs = mock(WhatsAppConfigRepository.class);
        when(configs.findFirstByOwnerUserIdOrderByIdDesc(OWNER.toString()))
                .thenReturn(Optional.of(config));

        WhatsAppConversation conversation = WhatsAppConversation.builder()
                .ownerUserId(OWNER.toString()).whatsappConfigId(7L)
                .customerPhone("919896458807").unreadCount(0).status("OPEN").build();
        conversation.setId(31L);

        WhatsAppConversationRepository conversations = mock(WhatsAppConversationRepository.class);
        when(conversations.findByWhatsappConfigIdAndCustomerPhone(eq(7L), anyString()))
                .thenReturn(Optional.of(conversation));

        messages = mock(WhatsAppMessageRepository.class);

        service = new OutreachService(currentUser, mock(PermissionService.class),
                mock(EmailLogRepository.class), mock(OrgSmtpService.class),
                mock(WhatsAppMessagingService.class), configs, conversations, messages,
                new PhoneNumberService(new MetaWhatsAppProperties()),
                mock(com.xetax.crm.activity.RecordActivityService.class),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(WhatsAppTemplateVariables.class), mock(WhatsAppMediaService.class));
    }

    private void givenMessage(WhatsAppMessageStatus status, String code, String reason) {
        WhatsAppMessage message = WhatsAppMessage.builder()
                .ownerUserId(OWNER.toString()).whatsappConfigId(7L).conversationId(31L)
                .direction(MessageDirection.OUTBOUND).messageType(WhatsAppMessageType.TEMPLATE)
                .templateName("image_template").status(status)
                .errorCode(code).errorMessage(reason).build();
        message.setId(99L);
        Page<WhatsAppMessage> page = new PageImpl<>(List.of(message));
        when(messages.findByConversationIdAndOwnerUserIdOrderByIdDesc(
                anyLong(), anyString(), any())).thenReturn(page);
    }

    @Test
    void failedMessageCarriesItsReason() {
        givenMessage(WhatsAppMessageStatus.FAILED, "META_131047",
                "Message failed to send because more than 24 hours have passed since the customer last replied.");

        Map<String, Object> row = service.whatsappHistory("9896458807").get(0);

        assertEquals(WhatsAppMessageStatus.FAILED, row.get("status"));
        assertEquals("META_131047", row.get("errorCode"));
        assertEquals("Message failed to send because more than 24 hours have passed "
                + "since the customer last replied.", row.get("errorMessage"));
    }

    @Test
    void aSentMessageHasNothingToExplain() {
        givenMessage(WhatsAppMessageStatus.SENT, null, null);

        Map<String, Object> row = service.whatsappHistory("9896458807").get(0);

        assertEquals(WhatsAppMessageStatus.SENT, row.get("status"));
        assertNull(row.get("errorCode"));
        assertNull(row.get("errorMessage"));
    }
}
