package com.xetax.crm.ai.controller;

import com.xetax.crm.ai.dto.ChatRequestDto;
import com.xetax.crm.ai.rag.EmbeddingTestService;
import com.xetax.crm.ai.services.AiChatService;
import com.xetax.crm.auth.security.CurrentUserProvider;
import com.xetax.crm.billing.AiQuotaService;
import com.xetax.crm.common.ratelimit.RateLimiterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The assistant's tools read the signed-in user from the thread they run on.
 * Handing the answer to Reactor moved them onto a pool thread with no user,
 * and every tool came back "your role does not allow this" — the owner was
 * told the assistant could not read their own records.
 */
class AssistantToolContextTest {

    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private AiChatService chatService;
    private AiChatController controller;

    @BeforeEach
    void setUp() {
        chatService = mock(AiChatService.class);
        CurrentUserProvider users = mock(CurrentUserProvider.class);
        when(users.currentDataOwnerIdOrNull()).thenReturn(OWNER);

        controller = new AiChatController(mock(EmbeddingTestService.class), users);
        controller.aiChatService = chatService;
        controller.rateLimiter = mock(RateLimiterService.class);
        controller.quotaService = mock(AiQuotaService.class);
    }

    @Test
    void theStreamingEndpointAnswersOnTheCallersThread() throws Exception {
        AtomicReference<String> answeredOn = new AtomicReference<>();
        when(chatService.chat(any(), anyString(), any())).thenAnswer(call -> {
            answeredOn.set(Thread.currentThread().getName());
            return "You have 1 record in TEST QA Form.";
        });

        Thread caller = Thread.currentThread();
        controller.chatStream(new ChatRequestDto("c1", "How many records?"));

        assertEquals(caller.getName(), answeredOn.get());
        verify(chatService, never()).chatStream(any(), anyString(), any());
    }

    @Test
    void theAnswerIsStillMeteredAndRateLimited() {
        when(chatService.chat(any(), anyString(), any())).thenReturn("ok");

        controller.chatStream(new ChatRequestDto("c1", "hi"));

        verify(controller.quotaService).consumeAssistant(OWNER.toString());
        verify(controller.rateLimiter).check(eqKey(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    private static String eqKey() {
        return org.mockito.ArgumentMatchers.eq("ai:" + OWNER);
    }
}
