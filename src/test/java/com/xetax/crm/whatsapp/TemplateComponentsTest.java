package com.xetax.crm.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.whatsapp.client.MetaWhatsAppClient;
import com.xetax.crm.whatsapp.dto.TemplateButton;
import com.xetax.crm.whatsapp.dto.TemplateCard;
import com.xetax.crm.whatsapp.dto.TemplateCreateRequest;
import com.xetax.crm.whatsapp.entity.WhatsAppConfig;
import com.xetax.crm.whatsapp.enums.WhatsAppConnectionStatus;
import com.xetax.crm.whatsapp.repository.WhatsAppTemplateRepository;
import com.xetax.crm.whatsapp.service.SecretEncryptionService;
import com.xetax.crm.whatsapp.service.WhatsAppConfigService;
import com.xetax.crm.whatsapp.service.WhatsAppTemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The exact JSON we hand Meta when a template is submitted.
 *
 * <p>These payloads cannot be checked against the real Graph API from a test,
 * and Meta's errors for a malformed component are generic, so the shape is
 * pinned here against what its documentation specifies.
 */
class TemplateComponentsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private MetaWhatsAppClient client;
    private WhatsAppTemplateService service;

    @BeforeEach
    void setUp() {
        client = mock(MetaWhatsAppClient.class);
        WhatsAppTemplateRepository repository = mock(WhatsAppTemplateRepository.class);
        SecretEncryptionService encryption = mock(SecretEncryptionService.class);
        WhatsAppConfigService configService = mock(WhatsAppConfigService.class);

        WhatsAppConfig config = new WhatsAppConfig();
        config.setId(1L);
        config.setOwnerUserId("owner-1");
        config.setWabaId("waba-1");
        config.setPhoneNumberId("pnid-1");
        config.setStatus(WhatsAppConnectionStatus.CONNECTED);
        config.setAccessTokenEncrypted("enc");

        when(configService.requireConnectedConfig()).thenReturn(config);
        when(encryption.decrypt(anyString())).thenReturn("token");
        when(repository.findByWhatsappConfigIdAndNameAndLanguage(anyLong(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(client.createTemplate(anyString(), anyString(), any()))
                .thenReturn(mapper.createObjectNode().put("id", "tpl-1").put("status", "PENDING"));

        service = new WhatsAppTemplateService(repository, client, encryption, configService,
                mapper, mock(com.xetax.crm.ai.rag.KnowledgeIndexer.class));
    }

    /** Runs a create and returns the components array Meta would receive. */
    @SuppressWarnings("unchecked")
    private JsonNode submit(TemplateCreateRequest request) {
        service.createTemplate(request);
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(client).createTemplate(anyString(), anyString(), captor.capture());
        return mapper.valueToTree(captor.getValue()).path("components");
    }

    private TemplateCreateRequest base() {
        TemplateCreateRequest request = new TemplateCreateRequest();
        request.setName("promo_one");
        request.setCategory("MARKETING");
        request.setLanguage("en");
        request.setBodyText("Hi {{1}}, take a look at what we built.");
        request.setExampleParams(List.of("Punit"));
        return request;
    }

    @Test
    void imageHeaderCarriesTheSampleHandleAndNoText() {
        TemplateCreateRequest request = base();
        request.setHeaderFormat("IMAGE");
        request.setHeaderHandle("4::aW1hZ2UvanBlZw==:ARZsample");

        JsonNode header = submit(request).get(0);
        assertEquals("HEADER", header.path("type").asText());
        assertEquals("IMAGE", header.path("format").asText());
        assertEquals("4::aW1hZ2UvanBlZw==:ARZsample",
                header.path("example").path("header_handle").get(0).asText());
        assertTrue(header.path("text").isMissingNode(), "a media header carries no text");
    }

    @Test
    void anImageHeaderWithoutASampleIsRefusedBeforeMetaSeesIt() {
        TemplateCreateRequest request = base();
        request.setHeaderFormat("IMAGE");
        BadRequestException error = assertThrows(BadRequestException.class, () -> service.createTemplate(request));
        assertTrue(error.getMessage().toLowerCase().contains("sample"));
        verify(client, never()).createTemplate(anyString(), anyString(), any());
    }

    @Test
    void urlPhoneAndQuickReplyButtonsAreBuiltInMetaSShape() {
        TemplateButton website = new TemplateButton();
        website.setType("URL");
        website.setText("Visit website");
        website.setUrl("https://xetacrm.pro");

        TemplateButton call = new TemplateButton();
        call.setType("PHONE_NUMBER");
        call.setText("Call us");
        call.setPhoneNumber("919876543210");

        TemplateButton stop = new TemplateButton();
        stop.setType("QUICK_REPLY");
        stop.setText("Stop promotions");

        TemplateCreateRequest request = base();
        request.setButtons(List.of(website, call, stop));

        JsonNode components = submit(request);
        JsonNode buttons = null;
        for (JsonNode c : components) if ("BUTTONS".equals(c.path("type").asText())) buttons = c;
        assertNotNull(buttons, "a BUTTONS component is present");

        assertEquals("URL", buttons.path("buttons").get(0).path("type").asText());
        assertEquals("https://xetacrm.pro", buttons.path("buttons").get(0).path("url").asText());
        assertEquals("PHONE_NUMBER", buttons.path("buttons").get(1).path("type").asText());
        assertEquals("+919876543210", buttons.path("buttons").get(1).path("phone_number").asText(),
                "a phone button is normalised to international form");
        assertEquals("QUICK_REPLY", buttons.path("buttons").get(2).path("type").asText());
    }

    @Test
    void aThirdUrlButtonIsRefused() {
        TemplateButton one = new TemplateButton();
        one.setType("URL"); one.setText("A"); one.setUrl("https://a.example");
        TemplateButton two = new TemplateButton();
        two.setType("URL"); two.setText("B"); two.setUrl("https://b.example");
        TemplateButton three = new TemplateButton();
        three.setType("URL"); three.setText("C"); three.setUrl("https://c.example");

        TemplateCreateRequest request = base();
        request.setButtons(List.of(one, two, three));
        BadRequestException error = assertThrows(BadRequestException.class,
                () -> service.createTemplate(request));
        assertTrue(error.getMessage().contains("2 URL buttons"));
    }

    @Test
    void aUrlButtonWithAVariableNeedsAnExample() {
        TemplateButton tracked = new TemplateButton();
        tracked.setType("URL");
        tracked.setText("Track order");
        tracked.setUrl("https://xetacrm.pro/t/{{1}}");

        TemplateCreateRequest request = base();
        request.setButtons(List.of(tracked));
        assertThrows(BadRequestException.class, () -> service.createTemplate(request));

        tracked.setUrlExample("https://xetacrm.pro/t/ORD-1042");
        JsonNode components = submit(base2(tracked));
        JsonNode button = null;
        for (JsonNode c : components) if ("BUTTONS".equals(c.path("type").asText())) button = c.path("buttons").get(0);
        assertNotNull(button);
        assertEquals("https://xetacrm.pro/t/ORD-1042", button.path("example").get(0).asText());
    }

    private TemplateCreateRequest base2(TemplateButton button) {
        TemplateCreateRequest request = base();
        request.setButtons(List.of(button));
        return request;
    }

    @Test
    void carouselCardsAreIndexedAndEachCarriesItsOwnSample() {
        TemplateCard first = card("Plan A {{1}}", "4::handleA");
        TemplateCard second = card("Plan B {{1}}", "4::handleB");

        TemplateCreateRequest request = base();
        request.setCards(List.of(first, second));

        JsonNode components = submit(request);
        JsonNode carousel = null;
        for (JsonNode c : components) if ("CAROUSEL".equals(c.path("type").asText())) carousel = c;
        assertNotNull(carousel, "a CAROUSEL component is present");
        assertEquals(2, carousel.path("cards").size());

        JsonNode cardOne = carousel.path("cards").get(0);
        assertEquals(0, cardOne.path("card_index").asInt());
        JsonNode cardHeader = cardOne.path("components").get(0);
        assertEquals("IMAGE", cardHeader.path("format").asText());
        assertEquals("4::handleA", cardHeader.path("example").path("header_handle").get(0).asText());
        assertEquals(1, carousel.path("cards").get(1).path("card_index").asInt());
    }

    @Test
    void carouselCardsThatDoNotMatchAreRefused() {
        TemplateCard first = card("Plan A", "4::handleA");
        TemplateCard second = card("Plan B", "4::handleB");
        TemplateButton extra = new TemplateButton();
        extra.setType("QUICK_REPLY");
        extra.setText("Pick this");
        second.setButtons(List.of(extra));

        TemplateCreateRequest request = base();
        request.setCards(List.of(first, second));
        BadRequestException error = assertThrows(BadRequestException.class,
                () -> service.createTemplate(request));
        assertTrue(error.getMessage().contains("same number of buttons"));
    }

    private TemplateCard card(String body, String handle) {
        TemplateCard card = new TemplateCard();
        card.setHeaderFormat("IMAGE");
        card.setHeaderHandle(handle);
        card.setBodyText(body);
        card.setExampleParams(List.of("Punit"));
        return card;
    }
}
