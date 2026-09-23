package com.xetax.crm.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xetax.crm.ai.rag.KnowledgeIndexer;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.whatsapp.client.MetaWhatsAppClient;
import com.xetax.crm.whatsapp.dto.TemplateCreateRequest;
import com.xetax.crm.whatsapp.entity.WhatsAppConfig;
import com.xetax.crm.whatsapp.entity.WhatsAppTemplate;
import com.xetax.crm.whatsapp.repository.WhatsAppTemplateRepository;
import com.xetax.crm.whatsapp.service.WhatsAppConfigService;
import com.xetax.crm.whatsapp.service.WhatsAppTemplateService;
import com.xetax.crm.whatsapp.service.SecretEncryptionService;

/**
 * Editing a template instead of deleting and recreating it keeps its name and
 * its quality history — but Meta only allows it under conditions, and every
 * one of them is cheaper to enforce here than to learn from a rejection.
 */
class TemplateEditRulesTest {

    private WhatsAppTemplateRepository templates;
    private MetaWhatsAppClient client;
    private WhatsAppTemplateService service;
    private WhatsAppTemplate stored;

    @BeforeEach
    void setUp() {
        templates = mock(WhatsAppTemplateRepository.class);
        client = mock(MetaWhatsAppClient.class);
        SecretEncryptionService encryption = mock(SecretEncryptionService.class);
        WhatsAppConfigService configService = mock(WhatsAppConfigService.class);
        KnowledgeIndexer indexer = mock(KnowledgeIndexer.class);

        WhatsAppConfig config = new WhatsAppConfig();
        config.setId(7L);
        config.setOwnerUserId("owner-1");
        config.setWabaId("waba-1");
        config.setAccessTokenEncrypted("enc");
        when(configService.requireConnectedConfig()).thenReturn(config);
        when(encryption.decrypt(anyString())).thenReturn("token");

        stored = new WhatsAppTemplate();
        stored.setId(11L);
        stored.setWhatsappConfigId(7L);
        stored.setOwnerUserId("owner-1");
        stored.setMetaTemplateId("meta-999");
        stored.setName("order_update");
        stored.setLanguage("en");
        stored.setCategory("UTILITY");
        stored.setStatus("APPROVED");
        when(templates.findById(11L)).thenReturn(Optional.of(stored));
        when(templates.save(any(WhatsAppTemplate.class))).thenAnswer(i -> i.getArgument(0));
        when(client.updateTemplate(anyString(), anyString(), any())).thenReturn(
                new ObjectMapper().createObjectNode());

        service = new WhatsAppTemplateService(templates, client, encryption, configService,
                new ObjectMapper(), indexer);
    }

    private TemplateCreateRequest edit(String body) {
        TemplateCreateRequest r = new TemplateCreateRequest();
        r.setName("ignored_by_meta");
        r.setLanguage("fr");
        r.setBodyText(body);
        return r;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sentPayload() {
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(client).updateTemplate(anyString(), anyString(), payload.capture());
        return payload.getValue();
    }

    @Test
    @DisplayName("the edit goes to the template's own Meta id")
    void addressesTheTemplateById() {
        service.updateTemplate(11L, edit("Your order is on its way."));

        verify(client).updateTemplate(org.mockito.ArgumentMatchers.eq("meta-999"), anyString(), any());
    }

    @Test
    @DisplayName("name and language are never sent — Meta cannot change them")
    void neverSendsNameOrLanguage() {
        service.updateTemplate(11L, edit("Your order is on its way."));

        assertThat(sentPayload()).doesNotContainKeys("name", "language");
        assertThat(stored.getName()).isEqualTo("order_update");
        assertThat(stored.getLanguage()).isEqualTo("en");
    }

    @Test
    @DisplayName("an approved template keeps its category — Meta refuses a change")
    void doesNotSendCategoryForApproved() {
        TemplateCreateRequest request = edit("Your order is on its way.");
        request.setCategory("MARKETING");

        service.updateTemplate(11L, request);

        assertThat(sentPayload()).doesNotContainKey("category");
        assertThat(stored.getCategory()).isEqualTo("UTILITY");
    }

    @Test
    @DisplayName("a rejected template may be re-filed under another category")
    void sendsCategoryForRejected() {
        stored.setStatus("REJECTED");
        TemplateCreateRequest request = edit("Your order is on its way.");
        request.setCategory("MARKETING");

        service.updateTemplate(11L, request);

        assertThat(sentPayload()).containsEntry("category", "MARKETING");
        assertThat(stored.getCategory()).isEqualTo("MARKETING");
    }

    @Test
    @DisplayName("a template still under review cannot be edited, and Meta is not called")
    void refusesWhilePending() {
        stored.setStatus("PENDING");

        assertThatThrownBy(() -> service.updateTemplate(11L, edit("anything")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("approved, rejected or paused");

        verify(client, never()).updateTemplate(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("a paused template can be edited — that is how it gets unpaused")
    void allowsPaused() {
        stored.setStatus("PAUSED");

        service.updateTemplate(11L, edit("Kinder wording this time."));

        verify(client).updateTemplate(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("without a Meta id there is nothing to edit, and it says what to do")
    void refusesWithoutAMetaId() {
        stored.setMetaTemplateId(null);

        assertThatThrownBy(() -> service.updateTemplate(11L, edit("anything")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Sync");

        verify(client, never()).updateTemplate(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("another workspace's template is not found")
    void refusesSomebodyElsesTemplate() {
        stored.setWhatsappConfigId(999L);

        assertThatThrownBy(() -> service.updateTemplate(11L, edit("anything")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("not found");

        verify(client, never()).updateTemplate(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("an edited template goes back to review, and an old rejection is cleared")
    void goesBackToPending() {
        stored.setStatus("REJECTED");
        stored.setRejectionReason("Body text was promotional");

        service.updateTemplate(11L, edit("Your order is on its way."));

        assertThat(stored.getStatus()).isEqualTo("PENDING");
        assertThat(stored.getRejectionReason()).isNull();
        assertThat(stored.getComponentsJson()).contains("Your order is on its way.");
    }

    @Test
    @DisplayName("every component travels together — Meta replaces them all")
    void sendsWholeComponents() {
        service.updateTemplate(11L, edit("Your order is on its way."));

        Object components = sentPayload().get("components");
        assertThat(components).isInstanceOf(List.class);
        assertThat(((List<?>) components)).isNotEmpty();
    }
}
