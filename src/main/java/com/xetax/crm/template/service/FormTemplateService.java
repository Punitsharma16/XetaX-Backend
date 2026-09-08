package com.xetax.crm.template.service;

import com.xetax.crm.automation.dto.AutomationRequest;
import com.xetax.crm.automation.enums.AutomationActionType;
import com.xetax.crm.automation.enums.AutomationTrigger;
import com.xetax.crm.automation.service.AutomationService;
import com.xetax.crm.common.exception.ResourceNotFoundException;
import com.xetax.crm.data_manager.dto.*;
import com.xetax.crm.data_manager.enums.FieldType;
import com.xetax.crm.data_manager.enums.StageStatus;
import com.xetax.crm.data_manager.repository.FormRepo;
import com.xetax.crm.data_manager.service.FieldService;
import com.xetax.crm.data_manager.service.FormService;
import com.xetax.crm.data_manager.service.StageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies a catalog template through the SAME services the UI uses (so
 * ownership stamping, caches and knowledge indexing all behave normally).
 * Automations are created INACTIVE — the user reviews field mapping in the
 * Automations page and flips them on.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FormTemplateService {

    private final FormService formService;
    private final FieldService fieldService;
    private final StageService stageService;
    private final AutomationService automationService;
    private final FormRepo formRepo;

    public List<FormTemplateCatalog.Template> catalog() {
        return FormTemplateCatalog.ALL.values().stream()
                .sorted((a, b) -> a.name().compareTo(b.name()))
                .toList();
    }

    @Transactional
    public Map<String, Object> apply(String templateKey, String customName) {
        FormTemplateCatalog.Template template = FormTemplateCatalog.ALL.get(templateKey);
        if (template == null) {
            throw new ResourceNotFoundException("Template not found: " + templateKey);
        }

        String name = customName == null || customName.isBlank()
                ? template.name() : customName.trim();

        // form (unique slug — same template dobara apply ho sake)
        FormRequest formRequest = new FormRequest();
        formRequest.setName(name);
        formRequest.setSlug(uniqueSlug(name));
        formRequest.setDescription(template.description());
        formRequest.setIcon(template.icon());
        formRequest.setColor(template.color());
        FormResponse form = formService.create(formRequest);

        // fields (fieldKey -> id, automations ke liye)
        Map<String, Long> fieldIdByKey = new HashMap<>();
        for (FormTemplateCatalog.TField f : template.fields()) {
            FieldRequest fieldRequest = new FieldRequest();
            fieldRequest.setLabel(f.label());
            fieldRequest.setFieldKey(f.fieldKey());
            fieldRequest.setFieldType(FieldType.valueOf(f.fieldType()));
            fieldRequest.setRequired(f.required());
            fieldRequest.setOptionsJson(f.optionsJson());
            FieldResponse created = fieldService.create(form.getId(), fieldRequest);
            fieldIdByKey.put(f.fieldKey(), created.getId());
        }

        // stages (code -> id)
        Map<String, Long> stageIdByCode = new HashMap<>();
        for (FormTemplateCatalog.TStage st : template.stages()) {
            StageRequest stageRequest = new StageRequest();
            stageRequest.setName(st.name());
            stageRequest.setCode(st.code());
            stageRequest.setSequence(st.sequence());
            stageRequest.setIsDefault(st.isDefault());
            stageRequest.setIsFinal(st.isFinal());
            stageRequest.setColor(st.color());
            stageRequest.setStatus(StageStatus.ACTIVE);
            StageResponse created = stageService.create(form.getId(), stageRequest);
            stageIdByCode.put(st.code(), created.getId());
        }

        // automations — INACTIVE drafts
        int automationCount = 0;
        for (FormTemplateCatalog.TAutomation a : template.automations()) {
            try {
                AutomationRequest request = new AutomationRequest();
                request.setName(a.name());
                request.setDescription(a.note());
                request.setFormId(form.getId());
                if ("STATUS_CHANGED".equals(a.trigger())) continue; // statuses removed \u2014 stage pipeline only
                request.setTrigger(AutomationTrigger.valueOf(a.trigger()));
                if (a.triggerStageCode() != null) {
                    request.setTriggerStageId(stageIdByCode.get(a.triggerStageCode()));
                }
                request.setActionType(AutomationActionType.valueOf(a.actionType()));
                if (a.actionFieldKey() != null) {
                    request.setActionFieldId(fieldIdByKey.get(a.actionFieldKey()));
                }
                request.setActionValue(a.actionValue());
                request.setEmailSubject(a.emailSubject());
                request.setEmailMessage(a.emailMessage());
                request.setActive(false); // user reviews, then switches on
                automationService.create(request);
                automationCount++;
            } catch (Exception e) {
                log.warn("Template automation '{}' skip: {}", a.name(), e.getMessage());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("form", form);
        result.put("fieldCount", fieldIdByKey.size());
        result.put("stageCount", stageIdByCode.size());
        result.put("automationCount", automationCount);
        result.put("note", "Automations INACTIVE bane hain — Automations page par khol kar "
                + "message/field confirm karo aur active kar do.");
        return result;
    }

    private String uniqueSlug(String name) {
        String base = name.toLowerCase().replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (base.isBlank()) base = "form";
        String slug = base;
        int i = 2;
        while (formRepo.findBySlug(slug).isPresent()) {
            slug = base + "-" + i++;
        }
        return slug;
    }
}
