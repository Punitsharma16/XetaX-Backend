package com.xetax.crm.agent.service;

import com.xetax.crm.agent.entity.AiAgent;
import com.xetax.crm.agent.repository.AiAgentRepository;
import com.xetax.crm.common.exception.ResourceNotFoundException;
import com.xetax.crm.common.ratelimit.RateLimiterService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The public visitor-facing chat. Completely isolated from the CRM
 * assistant: its own ChatClient with ZERO tools, a strict grounded prompt,
 * and retrieval filtered to one agent's chunks — so a visitor can never
 * reach CRM data, tools, or another agent's knowledge.
 */
@Slf4j
@Service
public class AgentChatService {

    private final AiAgentRepository agentRepository;
    private final AgentKnowledgeService knowledgeService;
    private final RateLimiterService rateLimiter;
    private final com.xetax.crm.billing.AiQuotaService quotaService;
    private final ChatClient chatClient;
    private final com.xetax.crm.desk.BotConversationService conversationService;

    public AgentChatService(AiAgentRepository agentRepository,
                            AgentKnowledgeService knowledgeService,
                            RateLimiterService rateLimiter,
                            ChatClient.Builder builder,
                            MessageChatMemoryAdvisor memoryAdvisor,
                            com.xetax.crm.billing.AiQuotaService quotaService,
                            com.xetax.crm.desk.BotConversationService conversationService) {
        this.agentRepository = agentRepository;
        this.knowledgeService = knowledgeService;
        this.rateLimiter = rateLimiter;
        this.quotaService = quotaService;
        this.conversationService = conversationService;
        // Fresh builder => none of the CRM assistant's system prompt or tools.
        this.chatClient = builder.defaultAdvisors(memoryAdvisor).build();
    }

    public Map<String, Object> info(String publicKey) {
        AiAgent agent = requireActive(publicKey);
        return Map.of(
                "name", agent.getName(),
                "welcomeMessage", agent.getWelcomeMessage() == null
                        ? "Hi! Main aapki kaise help kar sakta hoon?" : agent.getWelcomeMessage(),
                "themeColor", agent.getThemeColor() == null ? "#4f46e5" : agent.getThemeColor()
        );
    }

    public Map<String, Object> chat(String publicKey, String clientIp,
                                    String sessionId, String message) {
        return chat(publicKey, clientIp, sessionId, message, Map.of());
    }

    /** Same as chat(), plus optional visitor prefill from the hosted chat link. */
    public Map<String, Object> chat(String publicKey, String clientIp,
                                    String sessionId, String message, Map<String, String> prefill) {
        AiAgent agent = requireActive(publicKey);
        if (prefill != null && (prefill.get("name") != null || prefill.get("phone") != null
                || prefill.get("email") != null || prefill.get("source") != null)) {
            conversationService.websitePrefill(agent, sessionId, prefill.get("name"),
                    prefill.get("phone"), prefill.get("email"), prefill.get("source"));
        }
        // Visitor spam / quota protection: per-IP and per-agent windows.
        rateLimiter.check("agent:" + publicKey + ":" + clientIp, 15, Duration.ofMinutes(1));
        rateLimiter.check("agent-total:" + publicKey, 300, Duration.ofHours(1));

        String question = message == null ? "" : message.trim();
        if (question.isEmpty() || question.length() > 1500) {
            return Map.of("reply", "Thoda chhota sawal bhejo please.", "mode", "ai");
        }

        // Every visitor chat now runs through the desk orchestrator: persisted
        // history, record creation, pipeline actions, human hand-off. Metering
        // happens there (AI turns only — a human-handled message costs nothing).
        return conversationService.websiteTurn(agent, sessionId, question);
    }

    /** Human-mode polling for the widget: lines the visitor hasn't seen yet. */
    public Map<String, Object> updates(String publicKey, String clientIp, String sessionId, Long after) {
        AiAgent agent = requireActive(publicKey);
        rateLimiter.check("agent-poll:" + publicKey + ":" + (sessionId == null ? clientIp : sessionId), 40, Duration.ofMinutes(1));
        return conversationService.websiteUpdates(agent, sessionId, after);
    }

    private AiAgent requireActive(String publicKey) {
        return agentRepository.findByPublicKey(publicKey)
                .filter(agent -> "ACTIVE".equals(agent.getStatus()))
                .orElseThrow(() -> new ResourceNotFoundException("Agent not found"));
    }
}
