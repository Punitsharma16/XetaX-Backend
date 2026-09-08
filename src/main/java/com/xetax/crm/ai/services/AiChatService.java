package com.xetax.crm.ai.services;

public interface AiChatService {
    String chat(String conversationId, String message , java.util.UUID userId);

    reactor.core.publisher.Flux<String> chatStream(String conversationId, String message, java.util.UUID userId);
}
