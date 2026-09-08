package com.xetax.crm.ai.services;

import com.xetax.crm.ai.rag.RagService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;

@Service
public class AiChatServiceImpl implements AiChatService {

    private final ChatClient chatClient;
    private final RagService ragService;

    public AiChatServiceImpl(ChatClient chatClient, RagService ragService) {
        this.chatClient = chatClient;
        this.ragService = ragService;
    }

    @Override
    public String chat(String conversationId, String message , java.util.UUID userId) {

        String context = ragService.retrieveContext(message, userId);

        return chatClient.prompt()
                .user(promptUserSpec ->
                                promptUserSpec
                                        .text("""
                            {context}

                            User question:

                            {question}
                            """).param(
                                        "context",
                                        context
                                ).param(
                                        "question",
                                        message
                                )
                        )
                .advisors(advisorSpec -> advisorSpec.param(
                        ChatMemory.CONVERSATION_ID,
                        conversationId

                ))
                .call()
                .content();

    }

    /**
     * Token-streamed variant — same prompt/memory/RAG, but the reply reaches
     * the browser as it is generated, so perceived latency drops from the
     * full round-trip to the first token.
     */
    @Override
    public reactor.core.publisher.Flux<String> chatStream(String conversationId, String message,
                                                          java.util.UUID userId) {
        String context = ragService.retrieveContext(message, userId);
        return chatClient.prompt()
                .user(spec -> spec.text("""
                            {context}

                            User question:

                            {question}
                            """)
                        .param("context", context)
                        .param("question", message))
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream()
                .content();
    }

}
