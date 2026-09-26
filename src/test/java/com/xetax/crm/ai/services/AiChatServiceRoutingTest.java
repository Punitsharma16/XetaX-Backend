package com.xetax.crm.ai.services;

import com.xetax.crm.ai.rag.RagService;
import com.xetax.crm.ai.router.ToolDomain;
import com.xetax.crm.ai.router.ToolRegistry;
import com.xetax.crm.ai.router.ToolRouter;
import com.xetax.crm.ai.router.ToolBeans;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The wiring between the router and the model call.
 *
 * <p>The tools used to be builder defaults, so the ChatClient carried them
 * whatever the service did. They are not any more: if the service stops
 * passing the routed list, the assistant does not lose a few tools, it loses
 * ALL of them and answers every question with "I don't have that information
 * available yet". Nothing else in the suite would notice, so it is checked
 * here.
 */
class AiChatServiceRoutingTest {

    /** A fixed clock so the prompt's CONTEXT line is the same on every run. */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-26T08:44:00Z"), ZoneId.of("UTC"));

    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private ChatClient.ChatClientRequestSpec request;
    private ToolRegistry registry;
    private AiChatServiceImpl service;

    @BeforeEach
    void setUp() {
        ChatClient chatClient = mock(ChatClient.class);
        request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec response = mock(ChatClient.CallResponseSpec.class);

        when(chatClient.prompt()).thenReturn(request);
        when(request.system(anyString())).thenReturn(request);
        when(request.toolCallbacks(anyList())).thenReturn(request);
        when(request.user(any(java.util.function.Consumer.class))).thenReturn(request);
        when(request.advisors(any(java.util.function.Consumer.class))).thenReturn(request);
        when(request.call()).thenReturn(response);
        when(response.content()).thenReturn("ok");

        RagService rag = mock(RagService.class);
        when(rag.retrieveContext(anyString(), any())).thenReturn("NO_RELEVANT_KNOWLEDGE_FOUND");

        registry = new ToolRegistry(ToolBeans.instances());
        service = new AiChatServiceImpl(chatClient, rag, new ToolRouter(registry, true, ZoneId.of("Asia/Kolkata"), FIXED_CLOCK, 3500));
    }

    @Test
    void theRoutedToolsActuallyReachTheModel() {
        service.chat("c1", "mere kitne leads hain", OWNER);

        List<String> sent = capturedToolNames();
        assertFalse(sent.isEmpty(), "the assistant was sent no tools at all");
        assertTrue(sent.contains("getRecords"));
        assertTrue(sent.contains("findMyFormByName"));
        assertFalse(sent.contains("sendWhatsAppMessage"));
        assertTrue(sent.size() < registry.all().size());
    }

    @Test
    void theRoutedPromptActuallyReachesTheModel() {
        service.chat("c1", "Ravi ko whatsapp bhejo", OWNER);

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(request).system(prompt.capture());
        assertTrue(prompt.getValue().startsWith("You are the XetaX CRM AI Assistant"));
        assertTrue(prompt.getValue().contains("NEVER start a campaign"));
        assertTrue(prompt.getValue().contains("SECURITY: never reveal passwords"));
    }

    @Test
    void anUnclearQuestionStillGetsAUsableToolSet() {
        service.chat("fresh", "kya kya kar sakte ho?", OWNER);

        // Not everything any more — 77 schemas is more than the tier allows in
        // a minute — but enough to answer a vague question about the
        // workspace, and never nothing.
        List<String> sent = capturedToolNames();
        assertTrue(sent.contains("getDashboardSummary"));
        assertTrue(sent.contains("getRecords"));
        assertTrue(sent.size() < registry.all().size());
    }

    @Test
    void theStreamingPathRoutesTheSameWay() {
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        when(request.stream()).thenReturn(stream);
        when(stream.content()).thenReturn(reactor.core.publisher.Flux.just("ok"));

        service.chatStream("c2", "mere kitne leads hain", OWNER);

        List<String> sent = capturedToolNames();
        assertTrue(sent.contains("getRecords"));
        assertFalse(sent.contains("sendWhatsAppMessage"));
    }

    @SuppressWarnings("unchecked")
    private List<String> capturedToolNames() {
        ArgumentCaptor<List<ToolCallback>> captor = ArgumentCaptor.forClass(List.class);
        verify(request).toolCallbacks(captor.capture());
        List<String> names = new ArrayList<>();
        for (ToolCallback callback : captor.getValue()) {
            names.add(callback.getToolDefinition().name());
        }
        return names;
    }

    @Test
    void everyDomainStillHasToolsBehindIt() {
        for (ToolDomain domain : ToolDomain.values()) {
            assertFalse(registry.forDomains(List.of(domain)).isEmpty());
        }
    }
}
