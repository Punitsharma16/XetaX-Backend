package com.xetax.crm.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.data_manager.dto.FieldResponse;
import com.xetax.crm.data_manager.enums.FieldType;
import com.xetax.crm.whatsapp.service.WhatsAppFlowBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Flow JSON generated from a CRM form.
 *
 * <p>Meta validates this file on upload and refuses to publish a Flow whose
 * screens are malformed, so the structure is pinned here: one terminal screen,
 * a Form, a control per field, and a complete action naming every answer.
 */
class FlowBuilderTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final WhatsAppFlowBuilder builder = new WhatsAppFlowBuilder(mapper);

    private FieldResponse field(String key, String label, FieldType type, boolean required) {
        FieldResponse f = new FieldResponse();
        f.setFieldKey(key);
        f.setLabel(label);
        f.setFieldType(type);
        f.setRequired(required);
        return f;
    }

    private JsonNode build(List<FieldResponse> fields) throws Exception {
        return mapper.readTree(builder.buildFromFields("Book a demo", fields));
    }

    @Test
    void aShortFormStaysOnOneTerminalScreen() throws Exception {
        JsonNode flow = build(List.of(
                field("name", "Your name", FieldType.TEXT, true),
                field("EMAIL", "Email", FieldType.EMAIL, false)));

        assertEquals("7.0", flow.path("version").asText());
        assertEquals(1, flow.path("screens").size());
        assertTrue(flow.path("routing_model").isMissingNode(),
                "a single screen needs no routing model");

        JsonNode screen = flow.path("screens").get(0);
        assertEquals("FIRST_ENTRY_SCREEN", screen.path("id").asText());
        assertTrue(screen.path("terminal").asBoolean(), "the screen must be terminal to send answers back");
        assertEquals("SingleColumnLayout", screen.path("layout").path("type").asText());
        assertEquals("Form", screen.path("layout").path("children").get(0).path("type").asText());
    }

    @Test
    void eachFieldTypeBecomesTheControlThatFitsIt() throws Exception {
        JsonNode children = build(List.of(
                field("name", "Your name", FieldType.TEXT, true),
                field("EMAIL", "Email", FieldType.EMAIL, false),
                field("PHONE", "Phone", FieldType.PHONE, true),
                field("notes", "Anything else", FieldType.TEXTAREA, false),
                field("visit", "Preferred date", FieldType.DATE, false),
                field("agree", "I agree to be contacted", FieldType.BOOLEAN, false)))
                .path("screens").get(0).path("layout").path("children").get(0).path("children");

        // children[0] is the heading, so controls start at 1
        assertEquals("TextInput", children.get(1).path("type").asText());
        assertEquals("text", children.get(1).path("input-type").asText());
        assertTrue(children.get(1).path("required").asBoolean());

        assertEquals("email", children.get(2).path("input-type").asText());
        assertEquals("phone", children.get(3).path("input-type").asText());
        assertEquals("TextArea", children.get(4).path("type").asText());
        assertEquals("DatePicker", children.get(5).path("type").asText());
        assertEquals("OptIn", children.get(6).path("type").asText());
    }

    @Test
    void theSubmitActionSendsEveryAnswerBack() throws Exception {
        JsonNode children = build(List.of(
                field("name", "Your name", FieldType.TEXT, true),
                field("EMAIL", "Email", FieldType.EMAIL, false)))
                .path("screens").get(0).path("layout").path("children").get(0).path("children");

        JsonNode footer = children.get(children.size() - 1);
        assertEquals("Footer", footer.path("type").asText());
        assertEquals("complete", footer.path("on-click-action").path("name").asText());

        JsonNode payload = footer.path("on-click-action").path("payload");
        assertEquals("${form.name}", payload.path("name").asText());
        assertEquals("${form.EMAIL}", payload.path("EMAIL").asText());
    }

    @Test
    void aChoiceFieldBecomesRadioButtonsFromItsOptions() throws Exception {
        FieldResponse interest = field("interest", "Interested in", FieldType.SELECT, true);
        interest.setOptionsJson("[\"Starter\",\"Growth\",\"Enterprise\"]");

        JsonNode control = build(List.of(interest))
                .path("screens").get(0).path("layout").path("children").get(0)
                .path("children").get(1);

        assertEquals("RadioButtonsGroup", control.path("type").asText());
        assertEquals(3, control.path("data-source").size());
        assertEquals("Starter", control.path("data-source").get(0).path("id").asText());
        assertEquals("Starter", control.path("data-source").get(0).path("title").asText());
    }

    @Test
    void aChoiceFieldWithNoOptionsIsRefusedRatherThanShippedBroken() {
        FieldResponse interest = field("interest", "Interested in", FieldType.SELECT, true);
        BadRequestException error = assertThrows(BadRequestException.class,
                () -> builder.buildFromFields("Book a demo", List.of(interest)));
        assertTrue(error.getMessage().contains("no options"));
    }

    @Test
    void fieldsWhatsAppCannotAskForAreLeftOut() throws Exception {
        JsonNode children = build(List.of(
                field("name", "Your name", FieldType.TEXT, true),
                field("photo", "Photo", FieldType.IMAGE, false),
                field("meta", "Raw", FieldType.JSON, false)))
                .path("screens").get(0).path("layout").path("children").get(0).path("children");

        // heading + one control + footer
        assertEquals(3, children.size());
        assertEquals("name", children.get(1).path("name").asText());
    }

    @Test
    void aLongFormIsSplitAcrossScreensThatChainTogether() throws Exception {
        java.util.List<FieldResponse> many = new java.util.ArrayList<>();
        for (int i = 1; i <= 13; i++) {
            many.add(field("f" + i, "Question " + i, FieldType.TEXT, false));
        }
        JsonNode flow = build(many);

        assertEquals(3, flow.path("screens").size(), "13 questions across 6 per screen");
        assertEquals("FIRST_ENTRY_SCREEN", flow.path("screens").get(0).path("id").asText());
        assertEquals("SCREEN_1", flow.path("screens").get(1).path("id").asText());
        assertEquals("SCREEN_2", flow.path("screens").get(2).path("id").asText());

        // routing model names where each screen may go
        JsonNode routing = flow.path("routing_model");
        assertEquals("SCREEN_1", routing.path("FIRST_ENTRY_SCREEN").get(0).asText());
        assertEquals("SCREEN_2", routing.path("SCREEN_1").get(0).asText());
        assertEquals(0, routing.path("SCREEN_2").size(), "the last screen goes nowhere");

        // only the last screen is terminal
        assertTrue(flow.path("screens").get(0).path("terminal").isMissingNode());
        assertTrue(flow.path("screens").get(2).path("terminal").asBoolean());
    }

    @Test
    void earlierAnswersAreCarriedForwardToTheFinalSubmit() throws Exception {
        java.util.List<FieldResponse> many = new java.util.ArrayList<>();
        for (int i = 1; i <= 13; i++) {
            many.add(field("f" + i, "Question " + i, FieldType.TEXT, false));
        }
        JsonNode flow = build(many);

        JsonNode firstFooter = footerOf(flow.path("screens").get(0));
        assertEquals("navigate", firstFooter.path("on-click-action").path("name").asText());
        assertEquals("SCREEN_1",
                firstFooter.path("on-click-action").path("next").path("name").asText());
        assertEquals("${form.f1}",
                firstFooter.path("on-click-action").path("payload").path("f1").asText());

        // screen two passes screen one's answers on as data, plus its own
        JsonNode secondFooter = footerOf(flow.path("screens").get(1));
        JsonNode secondPayload = secondFooter.path("on-click-action").path("payload");
        assertEquals("${data.f1}", secondPayload.path("f1").asText(),
                "an answer from an earlier screen travels as data");
        assertEquals("${form.f7}", secondPayload.path("f7").asText());
        assertTrue(flow.path("screens").get(1).path("data").has("f1"),
                "the screen declares the data it receives");

        // the last screen submits everything
        JsonNode lastFooter = footerOf(flow.path("screens").get(2));
        assertEquals("complete", lastFooter.path("on-click-action").path("name").asText());
        JsonNode finalPayload = lastFooter.path("on-click-action").path("payload");
        for (int i = 1; i <= 13; i++) {
            assertTrue(finalPayload.has("f" + i), "f" + i + " reaches the final submit");
        }
    }

    @Test
    void aFormTooLongForWhatsAppIsRefusedInsteadOfLosingQuestions() {
        java.util.List<FieldResponse> tooMany = new java.util.ArrayList<>();
        for (int i = 1; i <= 60; i++) {
            tooMany.add(field("f" + i, "Question " + i, FieldType.TEXT, false));
        }
        BadRequestException error = assertThrows(BadRequestException.class,
                () -> builder.buildFromFields("Long form", tooMany));
        assertTrue(error.getMessage().contains("screens"));
    }

    /** The Footer is always the last child of a screen's Form. */
    private JsonNode footerOf(JsonNode screen) {
        JsonNode children = screen.path("layout").path("children").get(0).path("children");
        return children.get(children.size() - 1);
    }

    @Test
    void aFormWithNothingAskableIsRefused() {
        FieldResponse onlyFile = field("photo", "Photo", FieldType.IMAGE, false);
        assertThrows(BadRequestException.class,
                () -> builder.buildFromFields("Book a demo", List.of(onlyFile)));
    }
}
