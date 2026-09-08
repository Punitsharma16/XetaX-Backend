package com.xetax.crm.ai.controller;

import com.xetax.crm.team.service.RequiresPermission;

import com.xetax.crm.ai.dto.ChatRequestDto;
import com.xetax.crm.ai.dto.ChatResponseDto;
import com.xetax.crm.ai.rag.EmbeddingTestService;
import com.xetax.crm.ai.services.AiChatService;
import com.xetax.crm.auth.security.CurrentUserProvider;
import com.xetax.crm.common.exception.UnauthorizedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/ai")
public class AiChatController {

    @Autowired
    AiChatService aiChatService;

    private final EmbeddingTestService embeddingTestService;

    private final CurrentUserProvider currentUserProvider;

    @Autowired
    com.xetax.crm.common.ratelimit.RateLimiterService rateLimiter;

    @Autowired
    com.xetax.crm.billing.AiQuotaService quotaService;


    public AiChatController(EmbeddingTestService embeddingTestService,
                            CurrentUserProvider currentUserProvider) {
        this.embeddingTestService = embeddingTestService;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping("/chat")
    @RequiresPermission("ai.use")
    public ResponseEntity<ChatResponseDto> chat(@RequestBody ChatRequestDto chatRequestDto) {
        /*
         * The user identity comes from the verified JWT in the SecurityContext
         * — never from a header/param/body the client controls.
         */
        UUID userId = currentUserProvider.currentDataOwnerIdOrNull();
        if (userId == null) {
            throw new UnauthorizedException("Not authenticated");
        }
        // Per-user AI quota: 10 requests per minute (also protects the LLM
        // provider's token budget).
        rateLimiter.check("ai:" + userId, 10, java.time.Duration.ofMinutes(1));
        // Metered: one assistant message off the month's plan quota / top-ups.
        quotaService.consumeAssistant(userId.toString());
        return ResponseEntity.ok(new ChatResponseDto(aiChatService.chat(chatRequestDto.conversationId(),chatRequestDto.message(), userId)));

    }

    /**
     * Streaming chat (SSE). Same auth/rate-limit as /chat; the browser paints
     * tokens as they arrive instead of waiting for the whole answer.
     */
    @PostMapping(value = "/chat/stream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    @RequiresPermission("ai.use")
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter chatStream(
            @RequestBody ChatRequestDto chatRequestDto) {
        UUID userId = currentUserProvider.currentDataOwnerIdOrNull();
        if (userId == null) {
            throw new UnauthorizedException("Not authenticated");
        }
        rateLimiter.check("ai:" + userId, 10, java.time.Duration.ofMinutes(1));
        quotaService.consumeAssistant(userId.toString());

        var emitter = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(120_000L);
        aiChatService.chatStream(chatRequestDto.conversationId(), chatRequestDto.message(), userId)
                .subscribe(
                        token -> {
                            try {
                                emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter
                                        .event().data(token));
                            } catch (Exception e) {
                                emitter.completeWithError(e);
                            }
                        },
                        emitter::completeWithError,
                        emitter::complete);
        return emitter;
    }

    @GetMapping("/embedding-test")
    public String embeddingTest() {

        return String.valueOf(
                embeddingTestService.getDimension(
                        "XetaX CRM manages leads and customers."
                )
        );
    }
}
