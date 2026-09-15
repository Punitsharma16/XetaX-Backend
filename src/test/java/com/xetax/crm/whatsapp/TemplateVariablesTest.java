package com.xetax.crm.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.whatsapp.dto.TemplateVariables;
import com.xetax.crm.whatsapp.entity.WhatsAppTemplate;
import com.xetax.crm.whatsapp.service.WhatsAppTemplateVariables;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every template format, filled with the right number of values, becomes the
 * exact components Meta expects; the wrong number is refused with a sentence.
 */
class TemplateVariablesTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final WhatsAppTemplateVariables variables = new WhatsAppTemplateVariables(mapper);

    private WhatsAppTemplate template(String category, String componentsJson) {
        WhatsAppTemplate t = new WhatsAppTemplate();
        t.setName("t");
        t.setLanguage("en");
        t.setCategory(category);
        t.setComponentsJson(componentsJson);
        return t;
    }

    private JsonNode built(WhatsAppTemplate t, TemplateVariables v) throws Exception {
        String json = variables.build(t, v);
        return json == null ? mapper.createArrayNode() : mapper.readTree(json);
    }

    private JsonNode ofType(JsonNode components, String type) {
        for (JsonNode c : components) if (type.equals(c.path("type").asText())) return c;
        return null;
    }

    @Test
    void bodyVariablesTakeExactlyThatManyValues() throws Exception {
        WhatsAppTemplate t = template("MARKETING",
                "[{\"type\":\"BODY\",\"text\":\"Hi {{1}}, your order {{2}} shipped.\"}]");
        JsonNode body = ofType(built(t, TemplateVariables.ofBody(List.of("Punit", "ORD-7"))), "body");
        assertEquals("Punit", body.path("parameters").get(0).path("text").asText());
        assertEquals("ORD-7", body.path("parameters").get(1).path("text").asText());

        BadRequestException tooFew = assertThrows(BadRequestException.class,
                () -> variables.build(t, TemplateVariables.ofBody(List.of("Punit"))));
        assertTrue(tooFew.getMessage().contains("needs 2 values, got 1"));

        assertThrows(BadRequestException.class,
                () -> variables.build(t, TemplateVariables.ofBody(List.of("a", "b", "c"))));
    }

    @Test
    void aTemplateWithoutVariablesSendsNoComponentsAndRefusesStrayValues() throws Exception {
        WhatsAppTemplate t = template("UTILITY", "[{\"type\":\"BODY\",\"text\":\"Thanks for shopping.\"}]");
        assertNull(variables.build(t, new TemplateVariables()));
        assertThrows(BadRequestException.class, () -> variables.build(t, TemplateVariables.ofBody(List.of("x"))));
    }

    @Test
    void anEmptyValueIsRefused() {
        WhatsAppTemplate t = template("MARKETING", "[{\"type\":\"BODY\",\"text\":\"Hi {{1}} there\"}]");
        BadRequestException e = assertThrows(BadRequestException.class,
                () -> variables.build(t, TemplateVariables.ofBody(List.of(" "))));
        assertTrue(e.getMessage().contains("{{1}} is empty"));
    }

    @Test
    void aTextHeaderVariableGetsAHeaderParameter() throws Exception {
        WhatsAppTemplate t = template("MARKETING",
                "[{\"type\":\"HEADER\",\"format\":\"TEXT\",\"text\":\"{{1}} sale\",\"example\":{\"header_text\":[\"Diwali\"]}},"
                        + "{\"type\":\"BODY\",\"text\":\"Shop now.\"}]");
        TemplateVariables v = new TemplateVariables();
        v.setHeader(List.of("Diwali"));
        JsonNode header = ofType(built(t, v), "header");
        assertEquals("text", header.path("parameters").get(0).path("type").asText());
        assertEquals("Diwali", header.path("parameters").get(0).path("text").asText());
        assertThrows(BadRequestException.class, () -> variables.build(t, new TemplateVariables()));
    }

    @Test
    void aMediaHeaderUsesTheSavedLinkOrAnOverride() throws Exception {
        WhatsAppTemplate t = template("MARKETING",
                "[{\"type\":\"HEADER\",\"format\":\"IMAGE\"},{\"type\":\"BODY\",\"text\":\"New arrivals.\"}]");
        assertThrows(BadRequestException.class, () -> variables.build(t, new TemplateVariables()));

        t.setHeaderMediaUrl("https://cdn.example/saved.jpg");
        JsonNode saved = ofType(built(t, new TemplateVariables()), "header");
        assertEquals("https://cdn.example/saved.jpg", saved.path("parameters").get(0).path("image").path("link").asText());

        TemplateVariables v = new TemplateVariables();
        v.setHeaderMediaUrl("https://cdn.example/other.jpg");
        JsonNode override = ofType(built(t, v), "header");
        assertEquals("https://cdn.example/other.jpg", override.path("parameters").get(0).path("image").path("link").asText());
    }

    @Test
    void onlyTheUrlButtonWithAVariableTakesAValue() throws Exception {
        WhatsAppTemplate t = template("UTILITY",
                "[{\"type\":\"BODY\",\"text\":\"Your order is on its way.\"},"
                        + "{\"type\":\"BUTTONS\",\"buttons\":[{\"type\":\"QUICK_REPLY\",\"text\":\"Thanks\"},"
                        + "{\"type\":\"URL\",\"text\":\"Track\",\"url\":\"https://x.example/t/{{1}}\"}]}]");
        TemplateVariables v = new TemplateVariables();
        v.setButtons(Map.of(1, "ORD-7"));
        JsonNode components = built(t, v);
        JsonNode button = ofType(components, "button");
        assertEquals("url", button.path("sub_type").asText());
        assertEquals("1", button.path("index").asText());
        assertEquals("ORD-7", button.path("parameters").get(0).path("text").asText());

        assertThrows(BadRequestException.class, () -> variables.build(t, new TemplateVariables()));
        TemplateVariables wrong = new TemplateVariables();
        wrong.setButtons(Map.of(0, "x", 1, "ORD-7"));
        BadRequestException e = assertThrows(BadRequestException.class, () -> variables.build(t, wrong));
        assertTrue(e.getMessage().contains("button 1 takes no value"));
    }

    @Test
    void aCouponCodeButtonSendsACouponParameter() throws Exception {
        WhatsAppTemplate t = template("MARKETING",
                "[{\"type\":\"BODY\",\"text\":\"Save 20% today.\"},"
                        + "{\"type\":\"BUTTONS\",\"buttons\":[{\"type\":\"COPY_CODE\",\"example\":\"SAVE20\"}]}]");
        TemplateVariables v = new TemplateVariables();
        v.setButtons(Map.of(0, "SAVE20"));
        JsonNode button = ofType(built(t, v), "button");
        assertEquals("copy_code", button.path("sub_type").asText());
        assertEquals("SAVE20", button.path("parameters").get(0).path("coupon_code").asText());
    }

    @Test
    void anOtpTemplateSendsTheCodeInBodyAndButton() throws Exception {
        WhatsAppTemplate t = template("AUTHENTICATION",
                "[{\"type\":\"BODY\",\"add_security_recommendation\":true},"
                        + "{\"type\":\"BUTTONS\",\"buttons\":[{\"type\":\"OTP\",\"otp_type\":\"COPY_CODE\"}]}]");
        JsonNode components = built(t, TemplateVariables.ofBody(List.of("482913")));
        assertEquals("482913", ofType(components, "body").path("parameters").get(0).path("text").asText());
        JsonNode button = ofType(components, "button");
        assertEquals("url", button.path("sub_type").asText());
        assertEquals("482913", button.path("parameters").get(0).path("text").asText());
    }

    @Test
    void carouselCardsGetTheirImagesBodiesAndButtons() throws Exception {
        String json = "[{\"type\":\"BODY\",\"text\":\"Pick a plan, {{1}}.\"},"
                + "{\"type\":\"CAROUSEL\",\"cards\":["
                + "{\"components\":[{\"type\":\"HEADER\",\"format\":\"IMAGE\"},{\"type\":\"BODY\",\"text\":\"Starter plan\"},"
                + "{\"type\":\"BUTTONS\",\"buttons\":[{\"type\":\"QUICK_REPLY\",\"text\":\"Choose\"}]}]},"
                + "{\"components\":[{\"type\":\"HEADER\",\"format\":\"IMAGE\"},{\"type\":\"BODY\",\"text\":\"Growth for {{1}} users\"},"
                + "{\"type\":\"BUTTONS\",\"buttons\":[{\"type\":\"QUICK_REPLY\",\"text\":\"Choose\"}]}]}]}]";
        WhatsAppTemplate t = template("MARKETING", json);
        t.setCardMediaJson("[\"https://cdn.example/a.jpg\",\"https://cdn.example/b.jpg\"]");

        TemplateVariables v = TemplateVariables.ofBody(List.of("Punit"));
        TemplateVariables.Card first = new TemplateVariables.Card();
        TemplateVariables.Card second = new TemplateVariables.Card();
        second.setBody(List.of("10"));
        v.setCards(List.of(first, second));

        JsonNode carousel = ofType(built(t, v), "carousel");
        assertEquals(2, carousel.path("cards").size());
        JsonNode card2 = carousel.path("cards").get(1);
        assertEquals(1, card2.path("card_index").asInt());
        assertEquals("https://cdn.example/b.jpg",
                ofType(card2.path("components"), "header").path("parameters").get(0).path("image").path("link").asText());
        assertEquals("10", ofType(card2.path("components"), "body").path("parameters").get(0).path("text").asText());
        assertEquals("quick_reply", ofType(card2.path("components"), "button").path("sub_type").asText());

        TemplateVariables missingCardValue = TemplateVariables.ofBody(List.of("Punit"));
        BadRequestException e = assertThrows(BadRequestException.class, () -> variables.build(t, missingCardValue));
        assertTrue(e.getMessage().contains("card 2 body needs 1 value, got 0"));
    }

    @Test
    void thePanelsJsonWithStringButtonKeysReadsIntoTheDto() throws Exception {
        String json = "{\"header\":[\"Diwali\"],\"body\":[\"Punit\"],\"buttons\":{\"1\":\"ORD-7\"},"
                + "\"cards\":[{\"body\":[]},{\"headerMediaUrl\":\"https://cdn.example/b.jpg\",\"body\":[\"10\"],\"buttons\":{\"0\":\"SAVE20\"}}]}";
        TemplateVariables v = mapper.readValue(json, TemplateVariables.class);
        assertEquals("ORD-7", v.getButtons().get(1));
        assertEquals(List.of("10"), v.getCards().get(1).getBody());
        assertEquals("SAVE20", v.getCards().get(1).getButtons().get(0));
        // Spring Boot 4 reads request bodies with Jackson 3 — check that mapper too.
        tools.jackson.databind.json.JsonMapper j3 = tools.jackson.databind.json.JsonMapper.builder().build();
        TemplateVariables v3 = j3.readValue(json, TemplateVariables.class);
        assertEquals("ORD-7", v3.getButtons().get(1));
        assertEquals("https://cdn.example/b.jpg", v3.getCards().get(1).getHeaderMediaUrl());
        assertEquals("SAVE20", v3.getCards().get(1).getButtons().get(0));
    }

    @Test
    void placeholdersAreResolvedThroughMap() {
        TemplateVariables v = TemplateVariables.ofBody(List.of("{name}", "fixed"));
        v.setButtons(Map.of(1, "{order}"));
        TemplateVariables resolved = variables.map(v, s -> s.replace("{name}", "Punit").replace("{order}", "ORD-7"));
        assertEquals(List.of("Punit", "fixed"), resolved.getBody());
        assertEquals("ORD-7", resolved.getButtons().get(1));
    }
}
