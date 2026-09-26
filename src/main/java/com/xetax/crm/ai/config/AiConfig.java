package com.xetax.crm.ai.config;


import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.redis.RedisChatMemoryRepository;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.RedisClient;

import java.time.Duration;
import java.util.Map;

@Configuration
public class AiConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder , MessageChatMemoryAdvisor messageChatMemoryAdvisor) {
        return builder
                /*
                 * No defaultSystem and no defaultTools here on purpose.
                 *
                 * Both used to be pinned to the builder, so every message
                 * carried the full prompt and all 49 tool schemas — ~6,700
                 * tokens before the question was even read, which is what the
                 * panel's production 413s were on an 8,000 token/minute cap.
                 *
                 * They are now chosen per request by ToolRouter and applied
                 * in AiChatServiceImpl. Note that ChatClientRequestSpec.tools
                 * APPENDS to the builder's defaults rather than replacing them
                 * (the javadoc says otherwise; DefaultChatClient.tools is the
                 * truth), so leaving defaults here would have made the routing
                 * a no-op.
                 */
                .defaultOptions(
                        OpenAiChatOptions.builder()
                                /* Model comes from application.yaml
                                   (spring.ai.openai.chat.model) — hardcoding
                                   it here silently overrode the yaml. */
                                .extraBody(
                                        Map.of(
                                                "include_reasoning", false
                                        )
                                )
                )
                .defaultAdvisors(new SimpleLoggerAdvisor() , messageChatMemoryAdvisor)
                .build();
    }


    @Bean
    public ChatMemory chatMemory(RedisClient redisClient) {
        org.springframework.ai.chat.memory.ChatMemoryRepository repository;
        try {
            // Redis Stack keeps conversations across restarts and instances.
            repository = RedisChatMemoryRepository.builder()
                    .jedisClient(redisClient)
                    .indexName("xetax-chat-memory")
                    .keyPrefix("xetax-chat:")
                    .timeToLive(Duration.ofDays(1))
                    .build();
        } catch (Exception e) {
            // Redis being down must never keep the whole panel from booting —
            // degrade to in-memory conversation memory and keep serving.
            org.slf4j.LoggerFactory.getLogger(AiConfig.class)
                    .warn("Redis unavailable — AI chat memory running IN-MEMORY: {}", e.getMessage());
            repository = new org.springframework.ai.chat.memory.InMemoryChatMemoryRepository();
        }
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                // 10 messages ≈ 5 exchanges of context — halves the memory
                // tokens sent with every request without hurting continuity.
                /* 6 messages: Groq free tier caps 8k tokens/min and every
                   request already carries ~7k of system+tools — trimming
                   memory is the cheapest way to stay under the cap. */
                .maxMessages(6)
                .build();
    }

    @Bean
    public MessageChatMemoryAdvisor messageChatMemoryAdvisor(ChatMemory chatMemory) {
        return MessageChatMemoryAdvisor.builder(chatMemory).build();
    }

    /**
     * Jedis client for the AI chat-memory store. Same REDIS_HOST/REDIS_PORT as
     * spring.data.redis — a hardcoded "localhost" here silently pointed the AI
     * memory at the wrong box in Docker while everything else used the env.
     */
    @Bean
    public RedisClient redisClient(
            @org.springframework.beans.factory.annotation.Value("${spring.data.redis.host:localhost}") String host,
            @org.springframework.beans.factory.annotation.Value("${spring.data.redis.port:6379}") int port) {
        return RedisClient.builder()
                .hostAndPort(host, port)
                .build();
    }




}

