package com.xetax.crm.ai.services;

import com.xetax.crm.ai.rag.RagService;
import com.xetax.crm.ai.router.RoutingDecision;
import com.xetax.crm.ai.router.ToolRouter;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;

@Service
public class AiChatServiceImpl implements AiChatService {

    private final ChatClient chatClient;
    private final RagService ragService;
    private final ToolRouter toolRouter;

    public AiChatServiceImpl(ChatClient chatClient, RagService ragService, ToolRouter toolRouter) {
        this.chatClient = chatClient;
        this.ragService = ragService;
        this.toolRouter = toolRouter;
    }

    @Override
    public String chat(String conversationId, String message , java.util.UUID userId) {

        String context = ragService.retrieveContext(message, userId);

        /*
         * The system prompt and the tool schemas are chosen for THIS message
         * rather than pinned to the ChatClient. They used to be builder
         * defaults, so a "how many leads do I have" carried the WhatsApp,
         * meeting, team and agent tools too — ~6,700 tokens of overhead on a
         * tier that allows 8,000 a minute. When the router finds no signal it
         * hands back the full set, so the worst case is the old behaviour.
         */
        RoutingDecision route = toolRouter.route(conversationId, message);

        return chatClient.prompt()
                .system(route.systemPrompt())
                .toolCallbacks(route.tools())
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
        RoutingDecision route = toolRouter.route(conversationId, message);
        return chatClient.prompt()
                .system(route.systemPrompt())
                .toolCallbacks(route.tools())
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
