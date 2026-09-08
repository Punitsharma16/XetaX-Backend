package com.xetax.crm.agent.tools;

import com.xetax.crm.agent.service.AgentService;
import com.xetax.crm.team.service.RequiresPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring AI tools for public chatbot agents. Gated by agents.manage via the
 * same aspect as REST. Knowledge added here goes ONLY into the named agent's
 * public knowledge — never into the CRM's own RAG.
 */
@Component
@RequiredArgsConstructor
public class AgentTools {

    private final AgentService agentService;

    @Value("${app.api-base-url:http://localhost:8085}")
    private String apiBaseUrl;

    @Tool(description = "List the user's public AI chatbot agents with their embed keys "
            + "and knowledge source counts.")
    @RequiresPermission("agents.manage")
    public List<Map<String, Object>> getMyAgents() {
        return agentService.list();
    }

    @Tool(description = "Create a public AI chatbot agent (for embedding on the user's "
            + "website). persona is optional behaviour text like 'polite sales assistant "
            + "for a real-estate firm'. Use ONLY on an explicit user request. Returns the "
            + "agent with its embed key; knowledge is added separately.")
    @RequiresPermission("agents.manage")
    public Map<String, Object> createAgent(
            @ToolParam(description = "Agent name, e.g. 'Pricing Bot'") String name,
            @ToolParam(description = "Persona/instructions (optional)", required = false) String persona,
            @ToolParam(description = "Welcome message visitors see (optional)", required = false) String welcomeMessage) {
        try {
            Map<String, Object> agent =
                    new LinkedHashMap<>(agentService.create(name, persona, welcomeMessage, null));
            agent.put("embedScript", embedSnippet((String) agent.get("publicKey")));
            return agent;
        } catch (Exception e) {
            return Map.of("error", safeMessage(e));
        }
    }

    @Tool(description = "Add pasted text as knowledge to one of the user's agents. The "
            + "agent will answer visitor questions from this content.")
    @RequiresPermission("agents.manage")
    public Map<String, Object> addAgentTextKnowledge(
            @ToolParam(description = "Agent id from getMyAgents") Long agentId,
            @ToolParam(description = "A short name for this knowledge, e.g. 'Pricing FAQ'") String name,
            @ToolParam(description = "The knowledge text itself") String text) {
        try {
            var source = agentService.addText(agentId, name, text);
            return Map.of("indexed", true, "chunks", source.getChunkCount());
        } catch (Exception e) {
            return Map.of("indexed", false, "error", safeMessage(e));
        }
    }

    @Tool(description = "Fetch a public web page and add its content as knowledge to one "
            + "of the user's agents.")
    @RequiresPermission("agents.manage")
    public Map<String, Object> addAgentUrlKnowledge(
            @ToolParam(description = "Agent id from getMyAgents") Long agentId,
            @ToolParam(description = "Full http(s) page URL") String url) {
        try {
            var source = agentService.addUrl(agentId, url);
            return Map.of("indexed", true, "chunks", source.getChunkCount());
        } catch (Exception e) {
            return Map.of("indexed", false, "error", safeMessage(e));
        }
    }

    @Tool(description = "Get the copy-paste embed script for an agent — the user puts this "
            + "one line on any website to show the chatbot.")
    @RequiresPermission("agents.manage")
    public Map<String, Object> getAgentEmbedScript(
            @ToolParam(description = "Agent id from getMyAgents") Long agentId) {
        try {
            Map<String, Object> agent = agentService.get(agentId);
            return Map.of("embedScript", embedSnippet((String) agent.get("publicKey")));
        } catch (Exception e) {
            return Map.of("error", safeMessage(e));
        }
    }

    private String embedSnippet(String publicKey) {
        return "<script src=\"" + apiBaseUrl + "/api/public/agents/" + publicKey
                + "/widget.js\" async></script>";
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? "Action fail ho gaya." : message;
    }
}
