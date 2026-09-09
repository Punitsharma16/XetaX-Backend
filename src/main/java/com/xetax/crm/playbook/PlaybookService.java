package com.xetax.crm.playbook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xetax.crm.agent.repository.AiAgentRepository;
import com.xetax.crm.auth.security.CurrentUserProvider;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.common.exception.ResourceNotFoundException;
import com.xetax.crm.data_manager.entity.FormEntity;
import com.xetax.crm.data_manager.entity.FormStage;
import com.xetax.crm.data_manager.repository.FormRepo;
import com.xetax.crm.data_manager.service.FormMetaCache;
import com.xetax.crm.document.DocumentFileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/** Owner-facing CRUD + preview for playbooks; also the entry point packs use. */
@Service
@RequiredArgsConstructor
public class PlaybookService {

    private final SalesPlaybookRepository playbooks;
    private final PlaybookRunRepository runs;
    private final FormRepo formRepo;
    private final FormMetaCache formMetaCache;
    private final AiAgentRepository agentRepository;
    private final DocumentFileRepository documents;
    private final CurrentUserProvider currentUserProvider;
    private final PlaybookEngine engine;
    private final ObjectMapper mapper = new ObjectMapper();

    private String owner() {
        UUID id = currentUserProvider.currentDataOwnerIdOrNull();
        if (id == null) throw new BadRequestException("Not signed in");
        return id.toString();
    }

    public List<Map<String, Object>> list() {
        String own = owner();
        List<Map<String, Object>> out = new ArrayList<>();
        for (SalesPlaybook pb : playbooks.findByOwnerUserIdOrderByIdDesc(own)) out.add(toMap(pb, true));
        return out;
    }

    /** Stored playbook of a form, or sensible defaults (not persisted) so the editor always has something. */
    public Map<String, Object> forForm(Long formId) {
        String own = owner();
        FormEntity form = ownedForm(formId, own);
        return playbooks.findByOwnerUserIdAndFormId(own, form.getId())
                .map(pb -> toMap(pb, true))
                .orElseGet(() -> toMap(defaults(own, form.getId()), false));
    }

    public static SalesPlaybook defaults(String owner, Long formId) {
        return SalesPlaybook.builder().ownerUserId(owner).formId(formId).active(false)
                .goal("").qualificationKeys("").quietStart(21).quietEnd(9).timezone("Asia/Kolkata")
                .maxFollowUps(3).rulesJson("[]").build();
    }

    @Transactional
    public Map<String, Object> save(Long formId, PlaybookRequest in) {
        String own = owner();
        FormEntity form = ownedForm(formId, own);
        SalesPlaybook pb = playbooks.findByOwnerUserIdAndFormId(own, form.getId())
                .orElseGet(() -> defaults(own, form.getId()));
        apply(pb, in, own, form);
        pb.setUpdatedAt(LocalDateTime.now());
        if (pb.getCreatedAt() == null) pb.setCreatedAt(LocalDateTime.now());
        return toMap(playbooks.save(pb), true);
    }

    /** Pack installer path — same validation, explicit owner (runs inside the apply request). */
    @Transactional
    public SalesPlaybook upsert(String own, Long formId, PlaybookRequest in) {
        FormEntity form = formRepo.findById(formId).filter(f -> own.equals(f.getOwnerUserId()))
                .orElseThrow(() -> new ResourceNotFoundException("Form not found"));
        SalesPlaybook pb = playbooks.findByOwnerUserIdAndFormId(own, form.getId())
                .orElseGet(() -> defaults(own, form.getId()));
        apply(pb, in, own, form);
        pb.setUpdatedAt(LocalDateTime.now());
        if (pb.getCreatedAt() == null) pb.setCreatedAt(LocalDateTime.now());
        return playbooks.save(pb);
    }

    private void apply(SalesPlaybook pb, PlaybookRequest in, String own, FormEntity form) {
        if (in.agentId() != null) {
            agentRepository.findByIdAndOwnerUserId(in.agentId(), own)
                    .orElseThrow(() -> new BadRequestException("That agent does not belong to this workspace"));
        }
        pb.setAgentId(in.agentId());
        if (in.quotationDocumentId() != null) {
            documents.findByIdAndOwnerUserId(in.quotationDocumentId(), own)
                    .orElseThrow(() -> new BadRequestException("That document does not belong to this workspace"));
        }
        pb.setQuotationDocumentId(in.quotationDocumentId());
        pb.setActive(Boolean.TRUE.equals(in.active()));
        pb.setGoal(in.goal() == null ? "" : trim(in.goal().trim(), 1000));
        Set<String> keys = new LinkedHashSet<>();
        for (var f : formMetaCache.getFields(form.getId())) {
            if (in.qualificationKeys() != null && in.qualificationKeys().contains(f.getFieldKey())) keys.add(f.getFieldKey());
        }
        pb.setQualificationKeys(String.join(",", keys));
        pb.setQuietStart(clamp(in.quietStart(), 0, 23, 21));
        pb.setQuietEnd(clamp(in.quietEnd(), 0, 23, 9));
        pb.setTimezone(in.timezone() == null || in.timezone().isBlank() ? "Asia/Kolkata" : trim(in.timezone().trim(), 40));
        pb.setMaxFollowUps(clamp(in.maxFollowUps(), 0, 20, 3));
        pb.setRulesJson(writeRules(validateRules(in.rules(), form, own)));
    }

    private List<PlaybookRule> validateRules(List<PlaybookRule> rules, FormEntity form, String own) {
        List<PlaybookRule> out = new ArrayList<>();
        if (rules == null) return out;
        Set<Long> stageIds = new HashSet<>();
        for (FormStage s : formMetaCache.getStages(form.getId())) stageIds.add(s.getId());
        for (PlaybookRule r : rules) {
            if (r == null) continue;
            if (r.getId() == null || r.getId().isBlank()) r.setId(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
            if (r.getName() == null || r.getName().isBlank()) throw new BadRequestException("Every rule needs a name");
            if (!PlaybookRule.TRIGGERS.contains(r.getTrigger())) throw new BadRequestException("Unknown trigger in rule '" + r.getName() + "'");
            if (!PlaybookRule.ACTIONS.contains(r.getAction())) throw new BadRequestException("Unknown action in rule '" + r.getName() + "'");
            List<Long> stages = new ArrayList<>();
            if (r.getStageIds() != null) for (Long id : r.getStageIds()) if (id != null && stageIds.contains(id)) stages.add(id);
            r.setStageIds(stages);
            if ("MOVE_STAGE".equals(r.getAction()) && (r.getTargetStageId() == null || !stageIds.contains(r.getTargetStageId()))) {
                throw new BadRequestException("Rule '" + r.getName() + "' needs a target stage of this form");
            }
            if (r.getDocumentId() != null) {
                documents.findByIdAndOwnerUserId(r.getDocumentId(), own)
                        .orElseThrow(() -> new BadRequestException("Rule '" + r.getName() + "' points at a document that is not yours"));
            }
            if ("INTEREST".equals(r.getTrigger())) {
                String i = r.getInterest() == null ? "" : r.getInterest().trim().toUpperCase();
                if (!List.of("HOT", "WARM", "COLD").contains(i)) throw new BadRequestException("Rule '" + r.getName() + "': interest must be HOT, WARM or COLD");
                r.setInterest(i);
            }
            if (r.getAfterMinutes() != null && r.getAfterMinutes() < 0) r.setAfterMinutes(0);
            if (r.getMaxRepeats() != null && r.getMaxRepeats() > 50) r.setMaxRepeats(50);
            if (r.getMessage() != null) r.setMessage(trim(r.getMessage(), 2000));
            if (r.getTemplateParams() == null) r.setTemplateParams(new ArrayList<>());
            if (r.getTaskTitle() != null) r.setTaskTitle(trim(r.getTaskTitle(), 160));
            if (r.getConditions() != null) {
                List<PlaybookRule.Condition> conds = new ArrayList<>();
                for (PlaybookRule.Condition c : r.getConditions()) {
                    if (c == null || c.getFieldKey() == null || c.getFieldKey().isBlank()) continue;
                    if (!PlaybookRule.OPS.contains(c.getOp())) c.setOp("EQUALS");
                    conds.add(c);
                }
                r.setConditions(conds);
            }
            out.add(r);
        }
        return out;
    }

    @Transactional
    public void delete(Long id) {
        SalesPlaybook pb = playbooks.findByIdAndOwnerUserId(id, owner())
                .orElseThrow(() -> new ResourceNotFoundException("Playbook not found"));
        runs.deleteByPlaybookId(pb.getId());
        playbooks.delete(pb);
    }

    public List<Map<String, Object>> recentRuns(Long id) {
        SalesPlaybook pb = playbooks.findByIdAndOwnerUserId(id, owner())
                .orElseThrow(() -> new ResourceNotFoundException("Playbook not found"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (PlaybookRun r : runs.findTop50ByPlaybookIdOrderByIdDesc(pb.getId())) out.add(runMap(r));
        return out;
    }

    public List<Map<String, Object>> recordRuns(String recordId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PlaybookRun r : runs.findTop20ByRecordIdAndOwnerUserIdOrderByIdDesc(recordId, owner())) out.add(runMap(r));
        return out;
    }

    /** Dry run: how many records each rule would touch right now. */
    public List<PlaybookEngine.RuleStat> preview(Long id) {
        SalesPlaybook pb = playbooks.findByIdAndOwnerUserId(id, owner())
                .orElseThrow(() -> new ResourceNotFoundException("Playbook not found"));
        return engine.evaluate(pb, true);
    }

    /** Fire eligible rules now (respects quiet hours and caps like the scheduler). */
    public List<PlaybookEngine.RuleStat> runNow(Long id) {
        SalesPlaybook pb = playbooks.findByIdAndOwnerUserId(id, owner())
                .orElseThrow(() -> new ResourceNotFoundException("Playbook not found"));
        if (!pb.isActive()) throw new BadRequestException("Switch the playbook on first");
        return engine.evaluate(pb, false);
    }

    /* ------------------------------------------------------------ helpers */

    private FormEntity ownedForm(Long formId, String own) {
        return formRepo.findById(formId).filter(f -> own.equals(f.getOwnerUserId()))
                .orElseThrow(() -> new ResourceNotFoundException("Form not found"));
    }

    public Map<String, Object> toMap(SalesPlaybook pb, boolean persisted) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", persisted ? pb.getId() : null);
        m.put("formId", pb.getFormId());
        formRepo.findById(pb.getFormId()).ifPresent(f -> { m.put("formName", f.getName()); m.put("formSlug", f.getSlug()); });
        m.put("agentId", pb.getAgentId());
        m.put("active", pb.isActive());
        m.put("goal", pb.getGoal() == null ? "" : pb.getGoal());
        m.put("qualificationKeys", PlaybookEngine.qualificationKeys(pb));
        m.put("quietStart", pb.getQuietStart());
        m.put("quietEnd", pb.getQuietEnd());
        m.put("timezone", pb.getTimezone());
        m.put("maxFollowUps", pb.getMaxFollowUps());
        m.put("quotationDocumentId", pb.getQuotationDocumentId());
        m.put("rules", engine.rulesOf(pb));
        m.put("updatedAt", pb.getUpdatedAt());
        if (persisted && pb.getId() != null) {
            m.put("runsLast7d", runs.countByPlaybookIdAndLastRunAtAfter(pb.getId(), LocalDateTime.now().minusDays(7)));
        }
        return m;
    }

    private static Map<String, Object> runMap(PlaybookRun r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId()); m.put("ruleId", r.getRuleId()); m.put("ruleName", r.getRuleName());
        m.put("recordId", r.getRecordId()); m.put("runCount", r.getRunCount()); m.put("lastRunAt", r.getLastRunAt());
        m.put("nextEligibleAt", r.getNextEligibleAt()); m.put("channel", r.getLastChannel());
        m.put("outcome", r.getLastOutcome()); m.put("status", r.getStatus());
        return m;
    }

    private String writeRules(List<PlaybookRule> rules) {
        try { return mapper.writeValueAsString(rules); }
        catch (Exception e) { throw new BadRequestException("Rules could not be saved: " + e.getMessage()); }
    }

    private static int clamp(Integer v, int min, int max, int def) {
        if (v == null) return def;
        return Math.max(min, Math.min(max, v));
    }

    private static String trim(String s, int max) { return s.length() > max ? s.substring(0, max) : s; }
}
