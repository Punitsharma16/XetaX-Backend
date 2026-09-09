package com.xetax.crm.template.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xetax.crm.agent.entity.AiAgent;
import com.xetax.crm.agent.repository.AiAgentRepository;
import com.xetax.crm.agent.service.AgentService;
import com.xetax.crm.auth.security.CurrentUserProvider;
import com.xetax.crm.automation.dto.AutomationRequest;
import com.xetax.crm.automation.dto.AutomationResponse;
import com.xetax.crm.automation.enums.AutomationActionType;
import com.xetax.crm.automation.enums.AutomationTrigger;
import com.xetax.crm.automation.service.AutomationService;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.common.exception.ResourceNotFoundException;
import com.xetax.crm.data_manager.dto.*;
import com.xetax.crm.data_manager.entity.FormEntity;
import com.xetax.crm.data_manager.entity.FormField;
import com.xetax.crm.data_manager.entity.FormStage;
import com.xetax.crm.data_manager.enums.FieldType;
import com.xetax.crm.data_manager.enums.StageStatus;
import com.xetax.crm.data_manager.repository.FormRepo;
import com.xetax.crm.data_manager.service.FieldService;
import com.xetax.crm.data_manager.service.FormMetaCache;
import com.xetax.crm.data_manager.service.FormService;
import com.xetax.crm.data_manager.service.StageService;
import com.xetax.crm.desk.AgentChannelConfig;
import com.xetax.crm.desk.ChannelConfigService;
import com.xetax.crm.playbook.PlaybookEngine;
import com.xetax.crm.playbook.PlaybookRequest;
import com.xetax.crm.playbook.PlaybookRule;
import com.xetax.crm.playbook.SalesPlaybook;
import com.xetax.crm.playbook.SalesPlaybookRepository;
import com.xetax.crm.playbook.PlaybookService;
import com.xetax.crm.template.dto.PackView;
import com.xetax.crm.template.entity.CustomPack;
import com.xetax.crm.template.entity.MessageTemplateDraft;
import com.xetax.crm.template.entity.PackInstall;
import com.xetax.crm.template.model.PackDefinition;
import com.xetax.crm.template.repository.CustomPackRepository;
import com.xetax.crm.template.repository.MessageTemplateDraftRepository;
import com.xetax.crm.template.repository.PackInstallRepository;
import com.xetax.crm.whatsapp.dto.TemplateCreateRequest;
import com.xetax.crm.whatsapp.service.WhatsAppTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Installs a vertical pack through the SAME services the UI uses (ownership,
 * caches, knowledge indexing all behave normally): form → fields → stages →
 * draft automations → WhatsApp template drafts → AI agent (+channel config)
 * → sales playbook. Also exports a workspace's own form back into a pack.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FormTemplateService {

    private final PackCatalogService catalog;
    private final FormService formService;
    private final FieldService fieldService;
    private final StageService stageService;
    private final AutomationService automationService;
    private final FormRepo formRepo;
    private final FormMetaCache formMetaCache;
    private final CustomPackRepository customPacks;
    private final PackInstallRepository installs;
    private final MessageTemplateDraftRepository drafts;
    private final AgentService agentService;
    private final AiAgentRepository agentRepository;
    private final ChannelConfigService channelConfigService;
    private final PlaybookService playbookService;
    private final SalesPlaybookRepository playbooks;
    private final PlaybookEngine playbookEngine;
    private final WhatsAppTemplateService whatsAppTemplateService;
    private final CurrentUserProvider currentUserProvider;
    private final ObjectMapper mapper = new ObjectMapper();

    private String owner() {
        UUID id = currentUserProvider.currentDataOwnerIdOrNull();
        if (id == null) throw new BadRequestException("Not signed in");
        return id.toString();
    }

    /* ============================================================ catalog */

    public List<PackView> catalog() {
        String own = owner();
        Map<String, PackInstall> latest = new HashMap<>();
        for (PackInstall i : installs.findByOwnerUserIdOrderByInstalledAtDesc(own)) latest.putIfAbsent(i.getPackKey(), i);
        List<PackView> out = new ArrayList<>();
        for (PackDefinition d : catalog.builtins()) out.add(view(d, true, null, "BUILTIN", latest.get(d.getKey())));
        for (CustomPack c : customPacks.findByOwnerUserIdOrderByNameAsc(own)) {
            try {
                PackDefinition d = catalog.parse(c.getDefinitionJson());
                out.add(view(d, false, c.getId(), c.getSource(), latest.get(d.getKey())));
            } catch (Exception e) {
                log.warn("Custom pack {} unreadable: {}", c.getId(), e.getMessage());
            }
        }
        return out;
    }

    private static PackView view(PackDefinition d, boolean builtin, Long customId, String source, PackInstall i) {
        return new PackView(d, builtin, customId, source, i != null,
                i == null ? null : i.getFormId(), i == null ? null : i.getInstalledAt(), PackCatalogService.includes(d));
    }

    private PackDefinition resolve(String key, String own) {
        return catalog.builtin(key).orElseGet(() -> customPacks.findByOwnerUserIdAndPackKey(own, key)
                .map(c -> catalog.parse(c.getDefinitionJson()))
                .orElseThrow(() -> new ResourceNotFoundException("Template not found: " + key)));
    }

    /* ============================================================== apply */

    public record ApplyOptions(String name, Boolean includeAgent, Boolean includePlaybook,
                               Boolean includeAutomations, Boolean includeWhatsapp) {}

    @Transactional
    public Map<String, Object> apply(String templateKey, ApplyOptions opt) {
        String own = owner();
        PackDefinition pack = resolve(templateKey, own);
        boolean withAgent = opt == null || opt.includeAgent() == null || opt.includeAgent();
        boolean withPlaybook = opt == null || opt.includePlaybook() == null || opt.includePlaybook();
        boolean withAutomations = opt == null || opt.includeAutomations() == null || opt.includeAutomations();
        boolean withWhatsapp = opt == null || opt.includeWhatsapp() == null || opt.includeWhatsapp();

        String name = opt == null || opt.name() == null || opt.name().isBlank() ? pack.getName() : opt.name().trim();

        FormRequest formRequest = new FormRequest();
        formRequest.setName(name);
        formRequest.setSlug(uniqueSlug(name));
        formRequest.setDescription(pack.getDescription());
        formRequest.setIcon(pack.getIcon());
        formRequest.setColor(pack.getColor());
        FormResponse form = formService.create(formRequest);

        Map<String, Long> fieldIdByKey = new LinkedHashMap<>();
        int order = 0;
        for (PackDefinition.Field f : pack.getFields()) {
            FieldRequest req = new FieldRequest();
            req.setLabel(f.getLabel());
            req.setFieldKey(f.getFieldKey());
            req.setFieldType(FieldType.valueOf(f.getFieldType()));
            req.setRequired(f.isRequired());
            req.setOptionsJson(f.getOptionsJson());
            req.setPlaceholder(f.getPlaceholder());
            req.setDisplayOrder(f.getDisplayOrder() == null ? order : f.getDisplayOrder());
            order++;
            fieldIdByKey.put(f.getFieldKey(), fieldService.create(form.getId(), req).getId());
        }

        Map<String, Long> stageIdByCode = new LinkedHashMap<>();
        for (PackDefinition.Stage st : pack.getStages()) {
            StageRequest req = new StageRequest();
            req.setName(st.getName());
            req.setCode(st.getCode());
            req.setSequence(st.getSequence());
            req.setIsDefault(st.isDefault());
            req.setIsFinal(st.isFinal());
            req.setColor(st.getColor());
            req.setStatus(StageStatus.ACTIVE);
            stageIdByCode.put(st.getCode(), stageService.create(form.getId(), req).getId());
        }

        int automationCount = 0;
        if (withAutomations && pack.getAutomations() != null) {
            for (PackDefinition.Automation a : pack.getAutomations()) {
                try {
                    if ("STATUS_CHANGED".equals(a.getTrigger())) continue; // statuses are gone — stage pipeline only
                    AutomationRequest req = new AutomationRequest();
                    req.setName(a.getName());
                    req.setDescription(a.getNote());
                    req.setFormId(form.getId());
                    req.setTrigger(AutomationTrigger.valueOf(a.getTrigger()));
                    if (a.getTriggerStageCode() != null) req.setTriggerStageId(stageIdByCode.get(a.getTriggerStageCode()));
                    AutomationActionType type = AutomationActionType.valueOf(a.getActionType());
                    req.setActionType(type);
                    if (a.getActionFieldKey() != null) req.setActionFieldId(fieldIdByKey.get(a.getActionFieldKey()));
                    String value = a.getActionValue();
                    if (type == AutomationActionType.CHANGE_STAGE && a.getActionStageCode() != null) {
                        Long id = stageIdByCode.get(a.getActionStageCode());
                        value = id == null ? null : String.valueOf(id);
                    }
                    req.setActionValue(value);
                    req.setEmailSubject(a.getEmailSubject());
                    req.setEmailMessage(a.getEmailMessage());
                    req.setChannel(a.getChannel());
                    req.setActive(false); // user reviews, then switches on
                    automationService.create(req);
                    automationCount++;
                } catch (Exception e) {
                    log.warn("Pack automation '{}' skipped: {}", a.getName(), e.getMessage());
                }
            }
        }

        int draftCount = 0;
        if (withWhatsapp && pack.getWhatsappTemplates() != null) {
            for (PackDefinition.WaTemplate t : pack.getWhatsappTemplates()) {
                try {
                    if (drafts.findFirstByOwnerUserIdAndNameAndLanguage(own, t.getName(), t.getLanguage()).isPresent()) continue;
                    drafts.save(MessageTemplateDraft.builder()
                            .ownerUserId(own).formId(form.getId()).packKey(pack.getKey())
                            .name(t.getName()).category(t.getCategory()).language(t.getLanguage())
                            .headerText(t.getHeaderText()).bodyText(t.getBodyText()).footerText(t.getFooterText())
                            .exampleParamsJson(mapper.writeValueAsString(t.getExampleParams() == null ? List.of() : t.getExampleParams()))
                            .purpose(t.getPurpose()).status(MessageTemplateDraft.DRAFT).createdAt(LocalDateTime.now())
                            .build());
                    draftCount++;
                } catch (Exception e) {
                    log.warn("Pack WhatsApp template '{}' skipped: {}", t.getName(), e.getMessage());
                }
            }
        }

        Long agentId = null;
        if (withAgent && pack.getAgent() != null) {
            try {
                PackDefinition.AgentSpec a = pack.getAgent();
                Map<String, Object> agent = agentService.create(a.getName(), a.getPersona(), a.getWelcomeMessage(),
                        a.getThemeColor() == null ? pack.getColor() : a.getThemeColor());
                agentId = ((Number) agent.get("id")).longValue();
                List<Map<String, Object>> hints = new ArrayList<>();
                if (a.getStageHints() != null) for (PackDefinition.StageHint h : a.getStageHints()) {
                    Long sid = stageIdByCode.get(h.getStageCode());
                    if (sid != null && h.getHint() != null) hints.add(Map.of("stageId", sid, "hint", h.getHint()));
                }
                channelConfigService.save(agentId, new ChannelConfigService.SaveRequest(false, "ALL",
                        a.getPipelineMode(), form.getId(), hints, a.getHandoffKeywords(), null, null, a.isCaptureFields()));
                if (a.getKnowledge() != null) for (PackDefinition.KnowledgeText k : a.getKnowledge()) {
                    try { agentService.addText(agentId, k.getName(), k.getText()); }
                    catch (Exception e) { log.warn("Pack knowledge '{}' skipped: {}", k.getName(), e.getMessage()); }
                }
            } catch (Exception e) {
                log.warn("Pack agent skipped: {}", e.getMessage());
                agentId = null;
            }
        }

        Long playbookId = null;
        if (withPlaybook && pack.getPlaybook() != null) {
            try {
                SalesPlaybook pb = playbookService.upsert(own, form.getId(),
                        toPlaybookRequest(pack.getPlaybook(), agentId, stageIdByCode));
                playbookId = pb.getId();
            } catch (Exception e) {
                log.warn("Pack playbook skipped: {}", e.getMessage());
            }
        }

        installs.save(PackInstall.builder().ownerUserId(own).packKey(pack.getKey()).formId(form.getId())
                .agentId(agentId).playbookId(playbookId).fieldCount(fieldIdByKey.size()).stageCount(stageIdByCode.size())
                .automationCount(automationCount).draftCount(draftCount).installedAt(LocalDateTime.now()).build());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("form", form);
        result.put("fieldCount", fieldIdByKey.size());
        result.put("stageCount", stageIdByCode.size());
        result.put("automationCount", automationCount);
        result.put("whatsappDraftCount", draftCount);
        result.put("agentId", agentId);
        result.put("playbookId", playbookId);
        List<String> notes = new ArrayList<>();
        if (automationCount > 0) notes.add("Automations are drafts — review the messages, then switch them on.");
        if (agentId != null) notes.add("An AI assistant was created for this form — add your FAQ as knowledge and share its chat link.");
        if (playbookId != null) notes.add("The sales playbook is OFF until you review its rules and switch it on.");
        if (draftCount > 0) notes.add(draftCount + " WhatsApp templates are saved as drafts — submit them once WhatsApp is connected.");
        result.put("note", String.join(" ", notes));
        return result;
    }

    private static PlaybookRequest toPlaybookRequest(PackDefinition.PlaybookSpec spec, Long agentId, Map<String, Long> stageIdByCode) {
        List<PlaybookRule> rules = new ArrayList<>();
        if (spec.getRules() != null) for (PackDefinition.Rule r : spec.getRules()) {
            PlaybookRule rule = new PlaybookRule();
            rule.setName(r.getName());
            rule.setTrigger(r.getTrigger());
            rule.setAfterMinutes(r.getAfterMinutes());
            List<Long> ids = new ArrayList<>();
            if (r.getStageCodes() != null) for (String c : r.getStageCodes()) if (stageIdByCode.get(c) != null) ids.add(stageIdByCode.get(c));
            rule.setStageIds(ids);
            rule.setInterest(r.getInterest());
            rule.setMaxRepeats(r.getMaxRepeats());
            rule.setRepeatEveryMinutes(r.getRepeatEveryMinutes());
            rule.setAction(r.getAction());
            rule.setMessage(r.getMessage());
            rule.setAiCompose(r.isAiCompose());
            rule.setTemplateName(r.getTemplateName());
            rule.setTemplateParams(r.getTemplateParams() == null ? new ArrayList<>() : r.getTemplateParams());
            rule.setTargetStageId(r.getTargetStageCode() == null ? null : stageIdByCode.get(r.getTargetStageCode()));
            rule.setTaskTitle(r.getTaskTitle());
            rule.setTaskDueHours(r.getTaskDueHours());
            rule.setActive(r.isActive());
            List<PlaybookRule.Condition> conds = new ArrayList<>();
            if (r.getConditions() != null) for (PackDefinition.Condition c : r.getConditions()) {
                PlaybookRule.Condition pc = new PlaybookRule.Condition();
                pc.setFieldKey(c.getFieldKey()); pc.setOp(c.getOp()); pc.setValue(c.getValue());
                conds.add(pc);
            }
            rule.setConditions(conds);
            rules.add(rule);
        }
        return new PlaybookRequest(agentId, false, spec.getGoal(), spec.getQualificationKeys(),
                spec.getQuietStart(), spec.getQuietEnd(), null, spec.getMaxFollowUps(), null, rules);
    }

    /* ============================================================= export */

    /** Snapshot one of my forms (fields, stages, automations, agent, playbook, drafts) into a private pack. */
    @Transactional
    public PackView exportForm(Long formId, String packName, String packKey) {
        String own = owner();
        FormEntity form = formRepo.findById(formId).filter(f -> own.equals(f.getOwnerUserId()))
                .orElseThrow(() -> new ResourceNotFoundException("Form not found"));
        PackDefinition d = new PackDefinition();
        String key = packKey == null || packKey.isBlank() ? slugKey(packName == null || packName.isBlank() ? form.getName() : packName) : packKey.trim();
        d.setKey(key);
        d.setName(packName == null || packName.isBlank() ? form.getName() : packName.trim());
        d.setIcon(form.getIcon()); d.setColor(form.getColor());
        d.setTagline("Exported from " + form.getName());
        d.setDescription(form.getDescription());

        Map<Long, String> keyById = new HashMap<>();
        for (FormField f : formMetaCache.getFields(form.getId())) {
            PackDefinition.Field pf = new PackDefinition.Field();
            pf.setLabel(f.getLabel()); pf.setFieldKey(f.getFieldKey()); pf.setFieldType(f.getFieldType().name());
            pf.setRequired(Boolean.TRUE.equals(f.getRequired())); pf.setOptionsJson(f.getOptionsJson());
            pf.setPlaceholder(f.getPlaceholder()); pf.setDisplayOrder(f.getDisplayOrder());
            d.getFields().add(pf);
            keyById.put(f.getId(), f.getFieldKey());
        }
        Map<Long, String> codeById = new HashMap<>();
        for (FormStage s : formMetaCache.getStages(form.getId())) {
            PackDefinition.Stage ps = new PackDefinition.Stage();
            ps.setName(s.getName()); ps.setCode(s.getCode()); ps.setSequence(s.getSequence() == null ? 0 : s.getSequence());
            ps.setDefault(Boolean.TRUE.equals(s.getIsDefault())); ps.setFinal(Boolean.TRUE.equals(s.getIsFinal())); ps.setColor(s.getColor());
            d.getStages().add(ps);
            codeById.put(s.getId(), s.getCode());
        }
        for (AutomationResponse a : automationService.getAll()) {
            if (!form.getId().equals(a.getFormId()) || a.getActionType() == null) continue;
            if (a.getActionType() == AutomationActionType.ASSIGN_USER || a.getActionType() == AutomationActionType.SEND_DOCUMENT) continue; // not portable
            PackDefinition.Automation pa = new PackDefinition.Automation();
            pa.setName(a.getName()); pa.setNote(a.getDescription()); pa.setTrigger(a.getTrigger().name());
            pa.setTriggerStageCode(a.getTriggerStageId() == null ? null : codeById.get(a.getTriggerStageId()));
            pa.setActionType(a.getActionType().name());
            pa.setActionFieldKey(a.getActionFieldId() == null ? null : keyById.get(a.getActionFieldId()));
            if (a.getActionType() == AutomationActionType.CHANGE_STAGE) {
                try { pa.setActionStageCode(codeById.get(Long.parseLong(a.getActionValue()))); } catch (Exception ignored) { }
            } else {
                pa.setActionValue(a.getActionValue());
            }
            pa.setEmailSubject(a.getEmailSubject()); pa.setEmailMessage(a.getEmailMessage()); pa.setChannel(a.getChannel());
            d.getAutomations().add(pa);
        }
        for (MessageTemplateDraft t : drafts.findByOwnerUserIdAndFormId(own, form.getId())) {
            PackDefinition.WaTemplate wt = new PackDefinition.WaTemplate();
            wt.setName(t.getName()); wt.setCategory(t.getCategory()); wt.setLanguage(t.getLanguage());
            wt.setHeaderText(t.getHeaderText()); wt.setBodyText(t.getBodyText()); wt.setFooterText(t.getFooterText());
            wt.setPurpose(t.getPurpose());
            try { wt.setExampleParams(mapper.readValue(t.getExampleParamsJson() == null ? "[]" : t.getExampleParamsJson(), mapper.getTypeFactory().constructCollectionType(List.class, String.class))); }
            catch (Exception ignored) { }
            d.getWhatsappTemplates().add(wt);
        }
        List<AgentChannelConfig> cfgs = channelConfigService.configsTargeting(own, form.getId());
        if (!cfgs.isEmpty()) {
            AgentChannelConfig cfg = cfgs.get(0);
            AiAgent agent = agentRepository.findByIdAndOwnerUserId(cfg.getAgentId(), own).orElse(null);
            if (agent != null) {
                PackDefinition.AgentSpec as = new PackDefinition.AgentSpec();
                as.setName(agent.getName()); as.setPersona(agent.getPersona()); as.setWelcomeMessage(agent.getWelcomeMessage());
                as.setThemeColor(agent.getThemeColor()); as.setHandoffKeywords(cfg.getHandoffKeywords());
                as.setPipelineMode(cfg.getPipelineMode()); as.setCaptureFields(cfg.isCaptureFields());
                for (ChannelConfigService.StageHint h : channelConfigService.hintsOf(cfg)) {
                    String code = h.stageId() == null ? null : codeById.get(h.stageId());
                    if (code == null) continue;
                    PackDefinition.StageHint sh = new PackDefinition.StageHint();
                    sh.setStageCode(code); sh.setHint(h.hint());
                    as.getStageHints().add(sh);
                }
                d.setAgent(as);
            }
        }
        playbooks.findByOwnerUserIdAndFormId(own, form.getId()).ifPresent(pb -> {
            PackDefinition.PlaybookSpec ps = new PackDefinition.PlaybookSpec();
            ps.setGoal(pb.getGoal()); ps.setQualificationKeys(PlaybookEngine.qualificationKeys(pb));
            ps.setQuietStart(pb.getQuietStart()); ps.setQuietEnd(pb.getQuietEnd()); ps.setMaxFollowUps(pb.getMaxFollowUps());
            for (PlaybookRule r : playbookEngine.rulesOf(pb)) {
                PackDefinition.Rule pr = new PackDefinition.Rule();
                pr.setName(r.getName()); pr.setTrigger(r.getTrigger()); pr.setAfterMinutes(r.getAfterMinutes());
                List<String> codes = new ArrayList<>();
                if (r.getStageIds() != null) for (Long id : r.getStageIds()) if (codeById.get(id) != null) codes.add(codeById.get(id));
                pr.setStageCodes(codes); pr.setInterest(r.getInterest()); pr.setMaxRepeats(r.getMaxRepeats());
                pr.setRepeatEveryMinutes(r.getRepeatEveryMinutes()); pr.setAction(r.getAction()); pr.setMessage(r.getMessage());
                pr.setAiCompose(r.isAiCompose()); pr.setTemplateName(r.getTemplateName()); pr.setTemplateParams(r.getTemplateParams());
                pr.setTargetStageCode(r.getTargetStageId() == null ? null : codeById.get(r.getTargetStageId()));
                pr.setTaskTitle(r.getTaskTitle()); pr.setTaskDueHours(r.getTaskDueHours()); pr.setActive(r.isActive());
                if (r.getConditions() != null) for (PlaybookRule.Condition c : r.getConditions()) {
                    PackDefinition.Condition pc = new PackDefinition.Condition();
                    pc.setFieldKey(c.getFieldKey()); pc.setOp(c.getOp()); pc.setValue(c.getValue());
                    pr.getConditions().add(pc);
                }
                ps.getRules().add(pr);
            }
            d.setPlaybook(ps);
        });
        catalog.validate(d);
        return saveCustom(own, d, "EXPORT");
    }

    /** Paste a pack JSON (from another workspace, a partner, or a file). */
    @Transactional
    public PackView importPack(String json) {
        PackDefinition d = catalog.parse(json);
        return saveCustom(owner(), d, "IMPORT");
    }

    private PackView saveCustom(String own, PackDefinition d, String source) {
        if (catalog.builtin(d.getKey()).isPresent()) d.setKey(d.getKey() + "_custom");
        CustomPack pack = customPacks.findByOwnerUserIdAndPackKey(own, d.getKey())
                .orElseGet(() -> CustomPack.builder().ownerUserId(own).packKey(d.getKey()).createdAt(LocalDateTime.now()).build());
        pack.setName(d.getName());
        pack.setSource(source);
        pack.setDefinitionJson(catalog.write(d));
        pack.setUpdatedAt(LocalDateTime.now());
        pack = customPacks.save(pack);
        return view(d, false, pack.getId(), source, null);
    }

    public String customJson(Long id) {
        return customPacks.findByIdAndOwnerUserId(id, owner())
                .orElseThrow(() -> new ResourceNotFoundException("Pack not found")).getDefinitionJson();
    }

    @Transactional
    public void deleteCustom(Long id) {
        CustomPack pack = customPacks.findByIdAndOwnerUserId(id, owner())
                .orElseThrow(() -> new ResourceNotFoundException("Pack not found"));
        customPacks.delete(pack);
    }

    /* ============================================================= drafts */

    public List<Map<String, Object>> drafts() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (MessageTemplateDraft d : drafts.findByOwnerUserIdOrderByIdDesc(owner())) out.add(draftMap(d));
        return out;
    }

    /** Push one draft to Meta through the normal template service (needs WhatsApp connected). */
    @Transactional
    public Map<String, Object> submitDraft(Long id) {
        MessageTemplateDraft d = drafts.findByIdAndOwnerUserId(id, owner())
                .orElseThrow(() -> new ResourceNotFoundException("Draft not found"));
        TemplateCreateRequest req = new TemplateCreateRequest();
        req.setName(d.getName()); req.setCategory(d.getCategory()); req.setLanguage(d.getLanguage());
        req.setHeaderText(d.getHeaderText()); req.setBodyText(d.getBodyText()); req.setFooterText(d.getFooterText());
        try {
            req.setExampleParams(mapper.readValue(d.getExampleParamsJson() == null ? "[]" : d.getExampleParamsJson(),
                    mapper.getTypeFactory().constructCollectionType(List.class, String.class)));
        } catch (Exception ignored) { req.setExampleParams(List.of()); }
        try {
            whatsAppTemplateService.createTemplate(req);
            d.setStatus(MessageTemplateDraft.SUBMITTED); d.setError(null); d.setSubmittedAt(LocalDateTime.now());
        } catch (RuntimeException e) {
            d.setStatus(MessageTemplateDraft.FAILED); d.setError(trim(e.getMessage(), 300));
            drafts.save(d);
            throw e;
        }
        return draftMap(drafts.save(d));
    }

    @Transactional
    public void deleteDraft(Long id) {
        MessageTemplateDraft d = drafts.findByIdAndOwnerUserId(id, owner())
                .orElseThrow(() -> new ResourceNotFoundException("Draft not found"));
        drafts.delete(d);
    }

    private static Map<String, Object> draftMap(MessageTemplateDraft d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId()); m.put("formId", d.getFormId()); m.put("packKey", d.getPackKey());
        m.put("name", d.getName()); m.put("category", d.getCategory()); m.put("language", d.getLanguage());
        m.put("headerText", d.getHeaderText()); m.put("bodyText", d.getBodyText()); m.put("footerText", d.getFooterText());
        m.put("purpose", d.getPurpose()); m.put("status", d.getStatus()); m.put("error", d.getError());
        m.put("submittedAt", d.getSubmittedAt()); m.put("createdAt", d.getCreatedAt());
        return m;
    }

    /* ============================================================ helpers */

    private String uniqueSlug(String name) {
        String base = name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (base.isBlank()) base = "form";
        String slug = base;
        int i = 2;
        while (formRepo.findBySlug(slug).isPresent()) slug = base + "-" + i++;
        return slug;
    }

    private static String slugKey(String name) {
        String k = name.toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("(^_|_$)", "");
        if (k.length() < 2) k = "pack_" + k;
        return k.length() > 80 ? k.substring(0, 80) : k;
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }
}
