package com.xetax.crm.publicform;

import com.xetax.crm.automation.engine.AutomationEngine;
import com.xetax.crm.automation.enums.AutomationTrigger;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.common.exception.ResourceNotFoundException;
import com.xetax.crm.data_manager.documents.RecordDocument;
import com.xetax.crm.data_manager.dto.RecordRequest;
import com.xetax.crm.data_manager.entity.FormEntity;
import com.xetax.crm.data_manager.entity.FormField;
import com.xetax.crm.data_manager.entity.FormStage;
import com.xetax.crm.data_manager.repository.FormRepo;
import com.xetax.crm.data_manager.repository.RecordRepo;
import com.xetax.crm.data_manager.service.FormMetaCache;
import com.xetax.crm.data_manager.validator.DynamicValidationService;
import com.xetax.crm.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Public lead capture: the hosted form page and the incoming webhook both
 * POST here. Runs WITHOUT a security context — the form's publicKey IS the
 * credential, and everything is scoped by the form's owner. Existing record
 * flow is untouched: same validation, same default stage, same RECORD_CREATED
 * automations.
 */
@Service
@RequiredArgsConstructor
public class PublicFormService {

    private final FormRepo formRepo;
    private final FormMetaCache formMetaCache;
    private final DynamicValidationService validationService;
    private final RecordRepo recordRepo;
    private final AutomationEngine automationEngine;
    private final NotificationService notificationService;

    public FormEntity requirePublicForm(String publicKey) {
        FormEntity form = formRepo.findByPublicKey(publicKey)
                .orElseThrow(() -> new ResourceNotFoundException("Form not found"));
        if (!Boolean.TRUE.equals(form.getPublicEnabled())) {
            throw new ResourceNotFoundException("Form not found");
        }
        return form;
    }

    /** Field metadata the hosted page renders — no internal ids beyond field basics. */
    public Map<String, Object> info(String publicKey) {
        FormEntity form = requirePublicForm(publicKey);
        List<Map<String, Object>> fields = formMetaCache.getFields(form.getId()).stream()
                .filter(f -> !Boolean.TRUE.equals(f.getHidden()))
                .map(f -> {
                    Map<String, Object> row = new LinkedHashMap<String, Object>();
                    row.put("label", f.getLabel());
                    row.put("fieldKey", f.getFieldKey());
                    row.put("fieldType", f.getFieldType());
                    row.put("required", f.getRequired());
                    row.put("placeholder", f.getPlaceholder());
                    row.put("optionsJson", f.getOptionsJson());
                    return row;
                }).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", form.getName());
        out.put("description", form.getDescription());
        out.put("icon", form.getIcon());
        out.put("color", form.getColor());
        out.put("fields", fields);
        return out;
    }

    /** Accepts {"data":{...}} or a flat {...} JSON body (webhook-friendly). */
    public Map<String, Object> submit(String publicKey, Map<String, Object> body) {
        FormEntity form = requirePublicForm(publicKey);
        Map<String, Object> data = extractData(body);
        if (data.isEmpty()) throw new BadRequestException("No data submitted");

        List<FormField> fields = formMetaCache.getFields(form.getId());
        RecordRequest request = new RecordRequest();
        request.setData(data);
        Map<String, Object> validated = validationService.validate(request, fields);

        FormStage defaultStage = formMetaCache.getStages(form.getId()).stream()
                .filter(s -> Boolean.TRUE.equals(s.getIsDefault()))
                .findFirst()
                .orElseThrow(() -> new BadRequestException("This form has no default stage yet"));

        RecordDocument saved = recordRepo.save(RecordDocument.builder()
                .formId(form.getId())
                .stageId(defaultStage.getId())
                .data(validated)
                .createdBy("public-form")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());

        automationEngine.execute(AutomationTrigger.RECORD_CREATED, form, saved);

        notificationService.push(form.getOwnerUserId(), form.getOwnerUserId(), "RECORD_CREATED",
                "New " + form.getName() + " entry",
                firstTextValue(validated),
                "/app/records/" + form.getSlug() + "/" + saved.getId());

        return Map.of("ok", true, "message", "Thank you! We received your details.");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractData(Map<String, Object> body) {
        if (body == null) return Map.of();
        Object nested = body.get("data");
        if (nested instanceof Map<?, ?> m) return (Map<String, Object>) m;
        return body;
    }

    private String firstTextValue(Map<String, Object> data) {
        return data.values().stream()
                .filter(v -> v instanceof String s && !s.isBlank())
                .map(Object::toString).findFirst().orElse(null);
    }
}
