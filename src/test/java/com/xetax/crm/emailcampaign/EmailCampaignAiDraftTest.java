package com.xetax.crm.emailcampaign;

import com.xetax.crm.billing.AiQuotaService;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.emailcampaign.service.EmailCampaignAiService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Writing the mail is the work in an email campaign, and the page offered no
 * help with it. The drafter has to hand back something the sender can send:
 * a subject and a body, and only the placeholders that audience actually has.
 */
class EmailCampaignAiDraftTest {

    private static final String OWNER = "11111111-1111-1111-1111-111111111111";

    private final AiQuotaService quota = mock(AiQuotaService.class);
    private final AtomicReference<String> promptSeen = new AtomicReference<>();

    /** A drafter whose model answers with this exact text. */
    private EmailCampaignAiService drafterAnswering(String answer) {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(client);
        when(client.prompt()).thenReturn(request);
        when(request.system(anyString())).thenReturn(request);
        when(request.user(anyString())).thenAnswer(call -> {
            promptSeen.set(call.getArgument(0));
            return request;
        });
        when(request.call().content()).thenReturn(answer);
        return new EmailCampaignAiService(builder, quota);
    }

    @Test
    void itHandsBackASubjectAndABody() {
        Map<String, Object> draft = drafterAnswering(
                "{\"subject\":\"20% off this Diwali\",\"body\":\"Hi {name}, our Diwali offer is live.\"}")
                .draft(OWNER, "Diwali offer, 20% off till 5 Nov", List.of("name"));

        assertEquals("20% off this Diwali", draft.get("subject"));
        assertTrue(String.valueOf(draft.get("body")).contains("{name}"));
    }

    @Test
    void theModelIsToldWhichPlaceholdersExist() {
        drafterAnswering("{\"subject\":\"s\",\"body\":\"b\"}")
                .draft(OWNER, "Offer mail", List.of("name", "city"));

        assertTrue(promptSeen.get().contains("{name}"), promptSeen.get());
        assertTrue(promptSeen.get().contains("{city}"), promptSeen.get());
    }

    @Test
    void anAudienceWithoutPlaceholdersSaysSo() {
        drafterAnswering("{\"subject\":\"s\",\"body\":\"b\"}").draft(OWNER, "Offer mail", List.of());

        assertTrue(promptSeen.get().contains("none"), promptSeen.get());
    }

    @Test
    void itRefusesAnEmptyBriefWithoutSpendingAMessage() {
        EmailCampaignAiService drafter = drafterAnswering("{}");

        assertThrows(BadRequestException.class, () -> drafter.draft(OWNER, "   ", List.of()));
        verify(quota, never()).consumeAssistant(anyString());
    }

    @Test
    void ananswerMissingTheSubjectIsNotPassedOn() {
        EmailCampaignAiService drafter = drafterAnswering("{\"body\":\"only a body\"}");

        assertThrows(BadRequestException.class, () -> drafter.draft(OWNER, "Offer mail", List.of()));
    }

    @Test
    void aFencedAnswerIsStillRead() {
        Map<String, Object> draft = drafterAnswering(
                "```json\n{\"subject\":\"Hello\",\"body\":\"Body text\"}\n```")
                .draft(OWNER, "Offer mail", List.of());

        assertEquals("Hello", draft.get("subject"));
    }

    @Test
    void theDraftIsMetered() {
        drafterAnswering("{\"subject\":\"s\",\"body\":\"b\"}").draft(OWNER, "Offer mail", List.of());

        verify(quota).consumeAssistant(OWNER);
    }
}
