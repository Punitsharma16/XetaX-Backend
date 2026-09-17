package com.xetax.crm.whatsapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xetax.crm.whatsapp.entity.WhatsAppFlow;
import com.xetax.crm.whatsapp.entity.WhatsAppFlowResponse;
import com.xetax.crm.whatsapp.repository.WhatsAppFlowRepository;
import com.xetax.crm.whatsapp.repository.WhatsAppFlowResponseRepository;
import com.xetax.crm.whatsapp.service.WhatsAppFlowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Meta answers a template Flow sent without a token with the literal "unused".
 * The submission used to look that token up and reuse the row it found, so the
 * second customer's answers overwrote the first's. A token we did not mint must
 * never be treated as naming one send.
 */
class FlowSubmissionTokenTest {

    private WhatsAppFlowResponseRepository responseRepository;
    private WhatsAppFlowService service;
    private final Map<String, WhatsAppFlowResponse> byToken = new HashMap<>();
    private final List<WhatsAppFlowResponse> saves = new ArrayList<>();

    @BeforeEach
    void setUp() {
        responseRepository = mock(WhatsAppFlowResponseRepository.class);
        when(responseRepository.findByFlowToken(any()))
                .thenAnswer(inv -> Optional.ofNullable(byToken.get(inv.<String>getArgument(0))));
        when(responseRepository.save(any())).thenAnswer(inv -> {
            WhatsAppFlowResponse row = inv.getArgument(0);
            if (row.getFlowToken() != null) byToken.putIfAbsent(row.getFlowToken(), row);
            saves.add(row);
            return row;
        });

        WhatsAppFlowRepository flowRepository = mock(WhatsAppFlowRepository.class);
        // No form linked, so a submission stores answers and stops — the part under test.
        WhatsAppFlow flow = WhatsAppFlow.builder().ownerUserId("owner-1").name("Book a demo").build();
        flow.setId(5L);
        when(flowRepository.findById(5L)).thenReturn(Optional.of(flow));

        service = new WhatsAppFlowService(flowRepository, responseRepository,
                null, null, null, new ObjectMapper(), null, null, null, null, null);
    }

    private static String answers(String token, String name) {
        return "{\"flow_token\":\"" + token + "\",\"name\":\"" + name + "\"}";
    }

    @Test
    void twoUnusedTokensNeverShareARow() {
        service.recordSubmission("owner-1", "unused", "911111111111", 1L, answers("unused", "First"));
        service.recordSubmission("owner-1", "unused", "922222222222", 2L, answers("unused", "Second"));

        assertEquals(2, saves.stream().distinct().count(), "each customer gets their own row");
        WhatsAppFlowResponse first = saves.get(0);
        WhatsAppFlowResponse second = saves.get(1);
        assertNotSame(first, second);
        assertEquals("911111111111", first.getCustomerPhone());
        assertTrue(first.getAnswersJson().contains("First"), "the first customer's answers survive");
        assertTrue(second.getAnswersJson().contains("Second"));
    }

    @Test
    void ourTokenFillsTheRowThatWasWaiting() {
        WhatsAppFlowResponse waiting = WhatsAppFlowResponse.builder()
                .ownerUserId("owner-1").flowId(5L).flowToken("flw_5_abc").note("waiting").build();
        byToken.put("flw_5_abc", waiting);

        service.recordSubmission("owner-1", "flw_5_abc", "919034908543", 9L, answers("flw_5_abc", "Asha"));

        assertSame(waiting, saves.get(saves.size() - 1));
        assertTrue(waiting.getAnswersJson().contains("Asha"));
        assertEquals("Submitted", waiting.getNote());
    }

    @Test
    void ourTokenStillFindsItsFlowIfTheWaitingRowIsMissing() {
        service.recordSubmission("owner-1", "flw_5_lost", "919034908543", 9L, answers("flw_5_lost", "Asha"));
        assertEquals(5L, saves.get(0).getFlowId(), "the Flow is read from the token itself");
    }

    @Test
    void anotherWorkspacesRowIsNeverReused() {
        WhatsAppFlowResponse theirs = WhatsAppFlowResponse.builder()
                .ownerUserId("someone-else").flowId(5L).flowToken("flw_5_xyz").note("waiting").build();
        byToken.put("flw_5_xyz", theirs);

        service.recordSubmission("owner-1", "flw_5_xyz", "919034908543", 9L, answers("flw_5_xyz", "Asha"));

        assertNull(theirs.getAnswersJson(), "their row is untouched");
        assertEquals("owner-1", saves.get(0).getOwnerUserId());
    }
}
