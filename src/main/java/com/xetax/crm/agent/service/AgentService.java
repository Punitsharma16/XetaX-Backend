package com.xetax.crm.agent.service;

import com.xetax.crm.agent.entity.AgentSource;
import com.xetax.crm.agent.entity.AiAgent;
import com.xetax.crm.agent.repository.AgentSourceRepository;
import com.xetax.crm.agent.repository.AiAgentRepository;
import com.xetax.crm.auth.security.CurrentUserProvider;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.common.exception.ResourceNotFoundException;
import com.xetax.crm.common.exception.UnauthorizedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Owner-scoped CRUD for agents + their knowledge sources. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private final AiAgentRepository agentRepository;
    private final AgentSourceRepository sourceRepository;
    private final AgentKnowledgeService knowledgeService;
    private final CurrentUserProvider currentUserProvider;

    private String ownerId() {
        UUID id = currentUserProvider.currentDataOwnerIdOrNull();
        if (id == null) throw new UnauthorizedException("Not authenticated");
        return id.toString();
    }

    /* -------------------------------------------------------------- agents */

    @Transactional
    public Map<String, Object> create(String name, String persona, String welcomeMessage,
                                      String themeColor) {
        if (name == null || name.isBlank()) {
            throw new BadRequestException("Agent ka naam zaroori hai");
        }
        AiAgent agent = agentRepository.save(AiAgent.builder()
                .ownerUserId(ownerId())
                .name(name.trim())
                .publicKey(UUID.randomUUID().toString().replace("-", ""))
                .persona(blank(persona))
                .welcomeMessage(blank(welcomeMessage))
                .themeColor(blank(themeColor) == null ? "#4f46e5" : themeColor.trim())
                .status("ACTIVE")
                .build());
        return toMap(agent);
    }

    public List<Map<String, Object>> list() {
        return agentRepository.findByOwnerUserIdOrderByIdDesc(ownerId()).stream()
                .map(this::toMap).toList();
    }

    public Map<String, Object> get(Long id) {
        return toMap(requireMine(id));
    }

    @Transactional
    public Map<String, Object> update(Long id, String name, String persona,
                                      String welcomeMessage, String themeColor, String status) {
        AiAgent agent = requireMine(id);
        if (name != null && !name.isBlank()) agent.setName(name.trim());
        if (persona != null) agent.setPersona(blank(persona));
        if (welcomeMessage != null) agent.setWelcomeMessage(blank(welcomeMessage));
        if (themeColor != null && !themeColor.isBlank()) agent.setThemeColor(themeColor.trim());
        if ("ACTIVE".equals(status) || "DISABLED".equals(status)) agent.setStatus(status);
        return toMap(agentRepository.save(agent));
    }

    @Transactional
    public void delete(Long id) {
        AiAgent agent = requireMine(id);
        knowledgeService.deleteAgent(agent.getId());
        sourceRepository.findByAgentIdOrderByIdDesc(agent.getId())
                .forEach(sourceRepository::delete);
        agentRepository.delete(agent);
    }

    /* ------------------------------------------------------------- sources */

    public List<AgentSource> sources(Long agentId) {
        requireMine(agentId);
        return sourceRepository.findByAgentIdOrderByIdDesc(agentId);
    }

    @Transactional
    public AgentSource addPdf(Long agentId, MultipartFile file) {
        requireMine(agentId);
        try {
            String text = knowledgeService.extractPdf(file.getBytes());
            return saveAndIndex(agentId, "PDF",
                    file.getOriginalFilename() == null ? "document.pdf" : file.getOriginalFilename(),
                    text);
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("PDF process nahi ho paya: " + e.getMessage());
        }
    }

    @Transactional
    public AgentSource addUrl(Long agentId, String url) {
        requireMine(agentId);
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            throw new BadRequestException("Valid http/https URL do");
        }
        String text = knowledgeService.extractUrl(url.trim());
        return saveAndIndex(agentId, "URL", url.trim(), text);
    }

    @Transactional
    public AgentSource addText(Long agentId, String name, String text) {
        requireMine(agentId);
        if (text == null || text.isBlank()) {
            throw new BadRequestException("Text khali hai");
        }
        return saveAndIndex(agentId, "TEXT",
                name == null || name.isBlank() ? "Manual text" : name.trim(), text);
    }

    @Transactional
    public void deleteSource(Long agentId, Long sourceId) {
        requireMine(agentId);
        sourceRepository.findById(sourceId)
                .filter(source -> source.getAgentId().equals(agentId))
                .ifPresent(source -> {
                    knowledgeService.deleteSource(sourceId);
                    sourceRepository.delete(source);
                });
    }

    private AgentSource saveAndIndex(Long agentId, String type, String name, String text) {
        AgentSource source = sourceRepository.save(AgentSource.builder()
                .agentId(agentId).type(type).name(name)
                .status("INDEXED").chunkCount(0).contentChars(text.length())
                .build());
        try {
            int chunks = knowledgeService.index(agentId, source, text);
            source.setChunkCount(chunks);
        } catch (Exception e) {
            log.error("Agent source indexing failed: {}", e.getMessage());
            source.setStatus("FAILED");
            source.setError(e.getMessage() == null ? "indexing failed" : e.getMessage());
        }
        return sourceRepository.save(source);
    }

    /* -------------------------------------------------------------- helpers */

    private AiAgent requireMine(Long id) {
        return agentRepository.findByIdAndOwnerUserId(id, ownerId())
                .orElseThrow(() -> new ResourceNotFoundException("Agent not found"));
    }

    private String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Map<String, Object> toMap(AiAgent agent) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", agent.getId());
        map.put("name", agent.getName());
        map.put("publicKey", agent.getPublicKey());
        map.put("persona", agent.getPersona());
        map.put("welcomeMessage", agent.getWelcomeMessage());
        map.put("themeColor", agent.getThemeColor());
        map.put("status", agent.getStatus());
        map.put("sourceCount", sourceRepository.findByAgentIdOrderByIdDesc(agent.getId()).size());
        return map;
    }
}
