package com.xetax.crm.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xetax.crm.whatsapp.entity.WhatsAppFlow;
import com.xetax.crm.whatsapp.entity.WhatsAppFlowResponse;
import com.xetax.crm.whatsapp.entity.WhatsAppMessage;
import com.xetax.crm.whatsapp.entity.WhatsAppTemplate;
import com.xetax.crm.whatsapp.repository.WhatsAppFlowRepository;
import com.xetax.crm.whatsapp.repository.WhatsAppFlowResponseRepository;
import com.xetax.crm.whatsapp.service.TemplateFlowTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * A Flow outside the 24-hour window can only travel as a template button, and
 * without a token of its own every customer's answers came back as "unused".
 * These pin that each Flow button on each send carries a fresh, traceable token.
 */
class TemplateFlowTokensTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private WhatsAppFlowRepository flowRepository;
    private WhatsAppFlowResponseRepository responseRepository;
    private TemplateFlowTokens tokens;
    private final List<WhatsAppFlowResponse> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        flowRepository = mock(WhatsAppFlowRepository.class);
        responseRepository = mock(WhatsAppFlowResponseRepository.class);
        WhatsAppFlow flow = WhatsAppFlow.builder().ownerUserId("owner-1").metaFlowId("META-77").name("Book a demo").build();
        flow.setId(5L);
        when(flowRepository.findByMetaFlowId("META-77")).thenReturn(Optional.of(flow));
        when(responseRepository.save(any())).thenAnswer(inv -> {
            WhatsAppFlowResponse row = inv.getArgument(0);
            row.setId(100L + saved.size());
            saved.add(row);
            return row;
        });
        tokens = new TemplateFlowTokens(flowRepository, responseRepository);
    }

    private static WhatsAppTemplate template(String buttonsJson) {
        WhatsAppTemplate t = new WhatsAppTemplate();
        t.setName("demo_invite");
        t.setComponentsJson("[{\"type\":\"BODY\",\"text\":\"Hi {{1}}\"}," + buttonsJson + "]");
        return t;
    }

    private static final String URL_THEN_FLOW = """
            {"type":"BUTTONS","buttons":[
              {"type":"URL","text":"Site","url":"https://x.com"},
              {"type":"FLOW","text":"Book now","flow_id":"META-77","flow_action":"navigate"}]}""";

    private static WhatsAppMessage message() {
        WhatsAppMessage m = WhatsAppMessage.builder()
                .ownerUserId("owner-1").toPhone("919034908543").conversationId(9L).recordId("rec-1").build();
        m.setId(1L);
        return m;
    }

    private JsonNode flowComponent(String json) throws Exception {
        for (JsonNode c : mapper.readTree(json)) {
            if ("flow".equals(c.path("sub_type").asText())) return c;
        }
        return null;
    }

    @Test
    void aFlowButtonGetsItsOwnToken() throws Exception {
        String body = "[{\"type\":\"body\",\"parameters\":[{\"type\":\"text\",\"text\":\"Asha\"}]}]";
        TemplateFlowTokens.Attached out = tokens.attach(template(URL_THEN_FLOW), message(), body);

        JsonNode sent = mapper.readTree(out.componentsJson());
        assertEquals("body", sent.get(0).path("type").asText(), "the caller's own components are kept");

        JsonNode flow = flowComponent(out.componentsJson());
        assertNotNull(flow, out.componentsJson());
        assertEquals("button", flow.path("type").asText());
        assertEquals("1", flow.path("index").asText(), "the Flow is the second button");
        String token = flow.path("parameters").get(0).path("action").path("flow_token").asText();
        assertTrue(token.startsWith("flw_5_"), token);
        assertEquals("action", flow.path("parameters").get(0).path("type").asText());
        assertEquals(List.of(100L), out.pendingIds());
    }

    @Test
    void aWaitingRowIsSavedForTheToken() throws Exception {
        TemplateFlowTokens.Attached out = tokens.attach(template(URL_THEN_FLOW), message(), null);
        WhatsAppFlowResponse row = saved.get(0);
        String token = flowComponent(out.componentsJson()).path("parameters").get(0)
                .path("action").path("flow_token").asText();
        assertEquals(token, row.getFlowToken());
        assertEquals(5L, row.getFlowId());
        assertEquals("owner-1", row.getOwnerUserId());
        assertEquals("919034908543", row.getCustomerPhone());
        assertEquals(9L, row.getConversationId());
        assertEquals("rec-1", row.getRecordId());
        assertTrue(row.getNote().contains("demo_invite"));
    }

    @Test
    void everySendGetsADifferentToken() throws Exception {
        String first = flowComponent(tokens.attach(template(URL_THEN_FLOW), message(), null).componentsJson())
                .path("parameters").get(0).path("action").path("flow_token").asText();
        String second = flowComponent(tokens.attach(template(URL_THEN_FLOW), message(), null).componentsJson())
                .path("parameters").get(0).path("action").path("flow_token").asText();
        assertNotEquals(first, second);
    }

    @Test
    void aNumericFlowIdFromMetaIsReadToo() throws Exception {
        String synced = """
                {"type":"BUTTONS","buttons":[{"type":"FLOW","text":"Book","flow_id":77}]}""";
        WhatsAppFlow flow = WhatsAppFlow.builder().ownerUserId("owner-1").metaFlowId("77").build();
        flow.setId(6L);
        when(flowRepository.findByMetaFlowId("77")).thenReturn(Optional.of(flow));

        JsonNode component = flowComponent(tokens.attach(template(synced), message(), null).componentsJson());
        assertEquals("0", component.path("index").asText());
        assertTrue(component.path("parameters").get(0).path("action").path("flow_token").asText().startsWith("flw_6_"));
    }

    @Test
    void aTemplateWithoutFlowButtonsIsLeftAlone() {
        String body = "[{\"type\":\"body\",\"parameters\":[]}]";
        TemplateFlowTokens.Attached out = tokens.attach(
                template("{\"type\":\"BUTTONS\",\"buttons\":[{\"type\":\"QUICK_REPLY\",\"text\":\"Stop\"}]}"),
                message(), body);
        assertSame(body, out.componentsJson());
        assertTrue(out.pendingIds().isEmpty());
        verify(responseRepository, never()).save(any());
    }

    @Test
    void aFlowOfAnotherWorkspaceIsNotTouched() {
        WhatsAppFlow foreign = WhatsAppFlow.builder().ownerUserId("someone-else").metaFlowId("META-77").build();
        foreign.setId(8L);
        when(flowRepository.findByMetaFlowId("META-77")).thenReturn(Optional.of(foreign));

        TemplateFlowTokens.Attached out = tokens.attach(template(URL_THEN_FLOW), message(), "[]");
        assertEquals("[]", out.componentsJson());
        verify(responseRepository, never()).save(any());
    }

    @Test
    void aFlowBuiltOutsideXetaXIsNotTouched() {
        when(flowRepository.findByMetaFlowId("META-77")).thenReturn(Optional.empty());
        TemplateFlowTokens.Attached out = tokens.attach(template(URL_THEN_FLOW), message(), "[]");
        assertEquals("[]", out.componentsJson());
        assertTrue(out.pendingIds().isEmpty());
    }

    @Test
    void aButtonTheCallerAlreadyFilledIsKept() throws Exception {
        String mine = """
                [{"type":"button","sub_type":"flow","index":"1",
                  "parameters":[{"type":"action","action":{"flow_token":"mine"}}]}]""";
        TemplateFlowTokens.Attached out = tokens.attach(template(URL_THEN_FLOW), message(), mine);
        assertSame(mine, out.componentsJson());
        verify(responseRepository, never()).save(any());
    }

    @Test
    void noTemplateMeansNoChange() {
        assertEquals("[]", tokens.attach(null, message(), "[]").componentsJson());
    }

    @Test
    void anUndeliveredSendSaysSo() {
        WhatsAppFlowResponse row = WhatsAppFlowResponse.builder().note("waiting").build();
        when(responseRepository.findAllById(List.of(100L))).thenReturn(List.of(row));
        tokens.markUndelivered(List.of(100L), "Template not approved");
        assertEquals("Not delivered — Template not approved", row.getNote());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WhatsAppFlowResponse>> captor = ArgumentCaptor.forClass(List.class);
        verify(responseRepository).saveAll(captor.capture());
        assertEquals(1, captor.getValue().size());
    }
}
