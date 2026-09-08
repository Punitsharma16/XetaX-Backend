package com.xetax.crm.desk;

import com.xetax.crm.agent.entity.AiAgent;
import com.xetax.crm.agent.repository.AiAgentRepository;
import com.xetax.crm.auth.security.CurrentUserProvider;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.common.exception.ResourceNotFoundException;
import com.xetax.crm.data_manager.entity.FormEntity;
import com.xetax.crm.data_manager.entity.FormStage;
import com.xetax.crm.data_manager.entity.StageStatusOption;
import com.xetax.crm.data_manager.repository.FormRepo;
import com.xetax.crm.data_manager.repository.StageStatusOptionRepository;
import com.xetax.crm.data_manager.service.FormMetaCache;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.*;

/** Read/write the per-agent channel + pipeline settings, with validation. */
@Service
@RequiredArgsConstructor
public class ChannelConfigService {

    private final AgentChannelConfigRepository configRepository;
    private final AiAgentRepository agentRepository;
    private final FormRepo formRepo;
    private final FormMetaCache formMetaCache;
    private final StageStatusOptionRepository statusRepository;
    private final CurrentUserProvider currentUserProvider;
    private final ObjectMapper mapper = new ObjectMapper();

    /** A parsed whitelist line: which stage (and optional status) + when to pick it. */
    public record StageHint(Long stageId, Long statusId, String hint) {}

    private String owner() {
        UUID id = currentUserProvider.currentDataOwnerIdOrNull();
        if (id == null) throw new BadRequestException("Not signed in");
        return id.toString();
    }

    public static AgentChannelConfig defaults(Long agentId, String ownerUserId) {
        return AgentChannelConfig.builder()
                .agentId(agentId).ownerUserId(ownerUserId)
                .whatsappEnabled(false).whatsappScope("ALL").pipelineMode("SUGGEST")
                .targetFormId(null).stageHintsJson("[]").handoffKeywords("")
                .maxAiTurns(30).websiteWaitMinutes(3).captureFields(true)
                .build();
    }

    /** Stored config or defaults — callers never see null. */
    public AgentChannelConfig configFor(AiAgent agent) {
        return configRepository.findByAgentId(agent.getId())
                .orElseGet(() -> defaults(agent.getId(), agent.getOwnerUserId()));
    }

    public Optional<AgentChannelConfig> whatsappAgentConfig(String ownerUserId) {
        return configRepository.findFirstByOwnerUserIdAndWhatsappEnabledTrue(ownerUserId);
    }

    public Map<String, Object> get(Long agentId) {
        AiAgent agent = agentRepository.findByIdAndOwnerUserId(agentId, owner())
                .orElseThrow(() -> new ResourceNotFoundException("Agent not found"));
        return toMap(configFor(agent));
    }

    public record SaveRequest(Boolean whatsappEnabled, String whatsappScope, String pipelineMode,
                              Long targetFormId, List<Map<String, Object>> stageHints,
                              String handoffKeywords, Integer maxAiTurns, Integer websiteWaitMinutes,
                              Boolean captureFields) {}

    @Transactional
    public Map<String, Object> save(Long agentId, SaveRequest in) {
        String own = owner();
        AiAgent agent = agentRepository.findByIdAndOwnerUserId(agentId, own)
                .orElseThrow(() -> new ResourceNotFoundException("Agent not found"));
        AgentChannelConfig cfg = configRepository.findByAgentId(agentId)
                .orElseGet(() -> defaults(agentId, own));

        if (in.targetFormId() != null) {
            FormEntity form = formRepo.findById(in.targetFormId())
                    .filter(f -> own.equals(f.getOwnerUserId()))
                    .orElseThrow(() -> new BadRequestException("That form does not belong to this workspace"));
            cfg.setTargetFormId(form.getId());
        } else {
            cfg.setTargetFormId(null);
        }

        // Whitelist must point at real stages/statuses of the target form.
        List<StageHint> hints = new ArrayList<>();
        if (in.stageHints() != null && cfg.getTargetFormId() != null) {
            Set<Long> stageIds = new HashSet<>();
            for (FormStage s : formMetaCache.getStages(cfg.getTargetFormId())) stageIds.add(s.getId());
            Map<Long, Long> statusToStage = new HashMap<>();
            for (StageStatusOption st : statusRepository.findByFormIdOrderBySequenceAsc(cfg.getTargetFormId())) {
                statusToStage.put(st.getId(), st.getStageId());
            }
            for (Map<String, Object> h : in.stageHints()) {
                Long stageId = asLong(h.get("stageId"));
                Long statusId = asLong(h.get("statusId"));
                String hint = h.get("hint") == null ? "" : String.valueOf(h.get("hint")).trim();
                if (stageId == null || !stageIds.contains(stageId)) continue;
                if (statusId != null && !stageId.equals(statusToStage.get(statusId))) statusId = null;
                if (hint.isEmpty()) continue;
                hints.add(new StageHint(stageId, statusId, hint.length() > 200 ? hint.substring(0, 200) : hint));
            }
        }
        cfg.setStageHintsJson(writeHints(hints));

        boolean waOn = Boolean.TRUE.equals(in.whatsappEnabled());
        if (waOn) {
            configRepository.findFirstByOwnerUserIdAndWhatsappEnabledTrue(own)
                    .filter(other -> !other.getAgentId().equals(agentId))
                    .ifPresent(other -> {
                        throw new BadRequestException("Another agent already answers WhatsApp for this "
                                + "workspace — turn it off there first (only one agent per number).");
                    });
        }
        cfg.setWhatsappEnabled(waOn);
        cfg.setWhatsappScope("CAMPAIGN".equalsIgnoreCase(in.whatsappScope()) ? "CAMPAIGN" : "ALL");
        cfg.setPipelineMode("AUTO".equalsIgnoreCase(in.pipelineMode()) ? "AUTO" : "SUGGEST");
        cfg.setHandoffKeywords(in.handoffKeywords() == null ? "" : in.handoffKeywords().trim());
        cfg.setMaxAiTurns(clamp(in.maxAiTurns(), 5, 200, 30));
        cfg.setWebsiteWaitMinutes(clamp(in.websiteWaitMinutes(), 1, 30, 3));
        cfg.setCaptureFields(in.captureFields() == null || in.captureFields());
        if (cfg.getCreatedAt() == null) cfg.setCreatedAt(LocalDateTime.now());
        cfg.setUpdatedAt(LocalDateTime.now());
        return toMap(configRepository.save(cfg));
    }

    public void deleteFor(Long agentId) {
        configRepository.deleteByAgentId(agentId);
    }

    public List<StageHint> hintsOf(AgentChannelConfig cfg) {
        List<StageHint> out = new ArrayList<>();
        if (cfg.getStageHintsJson() == null || cfg.getStageHintsJson().isBlank()) return out;
        try {
            for (JsonNode n : mapper.readTree(cfg.getStageHintsJson())) {
                out.add(new StageHint(n.path("stageId").asLong(),
                        n.hasNonNull("statusId") ? n.path("statusId").asLong() : null,
                        n.path("hint").asText("")));
            }
        } catch (Exception ignored) { }
        return out;
    }

    private String writeHints(List<StageHint> hints) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (StageHint h : hints) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stageId", h.stageId()); m.put("statusId", h.statusId()); m.put("hint", h.hint());
            rows.add(m);
        }
        return mapper.writeValueAsString(rows);
    }

    public Map<String, Object> toMap(AgentChannelConfig cfg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("agentId", cfg.getAgentId());
        m.put("whatsappEnabled", cfg.isWhatsappEnabled());
        m.put("whatsappScope", cfg.getWhatsappScope());
        m.put("pipelineMode", cfg.getPipelineMode());
        m.put("targetFormId", cfg.getTargetFormId());
        List<Map<String, Object>> hints = new ArrayList<>();
        for (StageHint h : hintsOf(cfg)) {
            Map<String, Object> hm = new LinkedHashMap<>();
            hm.put("stageId", h.stageId()); hm.put("statusId", h.statusId()); hm.put("hint", h.hint());
            hints.add(hm);
        }
        m.put("stageHints", hints);
        m.put("handoffKeywords", cfg.getHandoffKeywords() == null ? "" : cfg.getHandoffKeywords());
        m.put("maxAiTurns", cfg.getMaxAiTurns());
        m.put("websiteWaitMinutes", cfg.getWebsiteWaitMinutes());
        m.put("captureFields", cfg.isCaptureFields());
        return m;
    }

    private static Long asLong(Object o) {
        if (o == null) return null;
        try { return Long.valueOf(String.valueOf(o).trim()); } catch (Exception e) { return null; }
    }

    private static int clamp(Integer v, int min, int max, int dflt) {
        if (v == null) return dflt;
        return Math.max(min, Math.min(max, v));
    }
}
