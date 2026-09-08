package com.xetax.crm.ai.tools;

import com.xetax.crm.team.service.RequiresPermission;

import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.data_manager.dto.FieldRequest;
import com.xetax.crm.data_manager.dto.FieldResponse;
import com.xetax.crm.data_manager.dto.FormResponse;
import com.xetax.crm.data_manager.enums.FieldType;
import com.xetax.crm.data_manager.service.FieldService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

/**
 * Spring AI WRITE tools for form FIELDS. (Field READ access already exists
 * in FormTools.getFormFields — not duplicated here.)
 *
 * <p>Security: every call goes through FormTools' ownership gate
 * (ownedFormOf), which resolves the authenticated user from the
 * SecurityContext — so a formId belonging to another user behaves exactly
 * like a formId that does not exist. userId is never a tool parameter.
 *
 * <p>Business logic stays in FieldService (same path the REST API uses):
 * duplicate-fieldKey rule, save, and field knowledge indexing with the
 * parent form's name.
 */
@Slf4j
@Component
public class FormFieldTools {

    private final FieldService fieldService;

    private final FormTools formTools;

    public FormFieldTools(FieldService fieldService, FormTools formTools) {
        this.fieldService = fieldService;
        this.formTools = formTools;
    }

    @Tool(description = """
            WRITE. Add a NEW field to one form of the currently authenticated
            CRM user (ownership comes from the logged-in session — never pass
            or ask for a user id). Use ONLY when the user explicitly asks to
            add/create a field. Arguments: formId — numeric id of the user's
            form (resolve it first via findMyFormByName/getMyForms); label —
            the field's display label (required); fieldType — one of: TEXT,
            TEXTAREA, NUMBER, DECIMAL, EMAIL, PHONE, PASSWORD, DATE, DATETIME,
            TIME, BOOLEAN, SELECT, MULTI_SELECT, RADIO, CHECKBOX, FILE, IMAGE,
            URL, JSON (required — pick the one matching the user's intent,
            e.g. "priority dropdown" -> SELECT); required — whether the field
            is mandatory (default false); options — for choice types (SELECT,
            MULTI_SELECT, RADIO, CHECKBOX) a JSON array string of choices,
            e.g. ["Low","Medium","High"]; fieldKey — optional unique key,
            omit to derive from the label; defaultValue and placeholder —
            optional. Returns created=true with the field, or created=false
            with a reason (e.g. duplicate field key — then ask the user for
            a different label/key).
            """)
    @RequiresPermission("forms.manage")
    public Map<String, Object> createField(
            @ToolParam(description = "Numeric id of the form the field belongs to") Long formId,
            @ToolParam(description = "Display label of the new field") String label,
            @ToolParam(description = "Field type, e.g. TEXT, NUMBER, EMAIL, SELECT") String fieldType,
            @ToolParam(required = false, description = "true when the field must be filled") Boolean required,
            @ToolParam(required = false, description = "JSON array of choices for SELECT/MULTI_SELECT/RADIO/CHECKBOX, e.g. [\"Low\",\"High\"]") String options,
            @ToolParam(required = false, description = "Optional unique field key; omit to derive from the label") String fieldKey,
            @ToolParam(required = false, description = "Optional default value") String defaultValue,
            @ToolParam(required = false, description = "Optional input placeholder text") String placeholder) {

        Optional<FormResponse> form = formTools.ownedFormOf(formId);
        if (form.isEmpty()) {
            return Map.of("created", false, "reason",
                    "No form with this id exists for the current user.");
        }
        if (label == null || label.isBlank()) {
            return Map.of("created", false, "reason", "A field label is required.");
        }
        FieldType type;
        try {
            type = FieldType.valueOf(fieldType == null ? "" : fieldType.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Map.of("created", false, "reason",
                    "Unknown field type. Valid types: " + Arrays.toString(FieldType.values()));
        }
        String finalKey = (fieldKey == null || fieldKey.isBlank()) ? toFieldKey(label) : toFieldKey(fieldKey);
        if (finalKey.isBlank()) {
            return Map.of("created", false, "reason",
                    "Could not derive a valid field key from this label — ask the user for a key.");
        }
        try {
            FieldRequest request = new FieldRequest();
            request.setLabel(label.trim());
            request.setFieldKey(finalKey);
            request.setFieldType(type);
            request.setRequired(Boolean.TRUE.equals(required));
            request.setOptionsJson(options);
            request.setDefaultValue(defaultValue);
            request.setPlaceholder(placeholder);
            /*
             * FieldService.create() is the same path the REST API uses:
             * form-exists check, duplicate-fieldKey check, save, and field
             * knowledge indexing with the parent form's name (failure-safe).
             * Ownership was already verified above.
             */
            FieldResponse created = fieldService.create(formId, request);
            return Map.of("created", true, "formName", form.get().getName(), "field", created);
        } catch (BadRequestException e) {
            return Map.of("created", false, "reason", e.getMessage());
        } catch (Exception e) {
            log.warn("createField tool failed: {}", e.getMessage());
            return Map.of("created", false, "reason",
                    "The field could not be created right now. Ask the user to try again later.");
        }
    }

    @Tool(description = """
            WRITE. Update an existing field of one form of the currently
            authenticated CRM user. Use ONLY when the user explicitly asks to
            change a field (rename it, change its type, make it required or
            optional, change its options/default/placeholder). Only the
            arguments you pass change — everything else keeps its current
            value; the field's key never changes. Arguments: formId — the
            form's numeric id; fieldId — the field's numeric id (resolve via
            getFormFields); label — new label; fieldType — new type (TEXT,
            NUMBER, EMAIL, SELECT...); required — true/false; options — new
            JSON array of choices for choice types; defaultValue,
            placeholder — new values. Returns updated=true with the field,
            or updated=false with a reason.
            """)
    @RequiresPermission("forms.manage")
    public Map<String, Object> updateField(
            @ToolParam(description = "Numeric id of the form") Long formId,
            @ToolParam(description = "Numeric id of the field to update") Long fieldId,
            @ToolParam(required = false, description = "New label; omit to keep") String label,
            @ToolParam(required = false, description = "New field type; omit to keep") String fieldType,
            @ToolParam(required = false, description = "New required flag; omit to keep") Boolean required,
            @ToolParam(required = false, description = "New JSON array of choices; omit to keep") String options,
            @ToolParam(required = false, description = "New default value; omit to keep") String defaultValue,
            @ToolParam(required = false, description = "New placeholder; omit to keep") String placeholder
    ) {

        Optional<FormResponse> form = formTools.ownedFormOf(formId);
        if (form.isEmpty()) {
            return Map.of("updated", false, "reason",
                    "No form with this id exists for the current user.");
        }
        FieldResponse current = fieldService.getAll(formId).stream()
                .filter(f -> fieldId != null && fieldId.equals(f.getId()))
                .findFirst()
                .orElse(null);
        if (current == null) {
            return Map.of("updated", false, "reason",
                    "No field with this id exists on this form.");
        }
        FieldType type = current.getFieldType();
        if (fieldType != null && !fieldType.isBlank()) {
            try {
                type = FieldType.valueOf(fieldType.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return Map.of("updated", false, "reason",
                        "Unknown field type. Valid types: " + Arrays.toString(FieldType.values()));
            }
        }
        try {
            /* The service's update replaces every column, so unchanged values
               are carried over from the current field. The fieldKey stays as
               it is — records store data under it. */
            FieldRequest request = new FieldRequest();
            request.setLabel(label != null && !label.isBlank() ? label.trim() : current.getLabel());
            request.setFieldKey(current.getFieldKey());
            request.setFieldType(type);
            request.setRequired(required != null ? required : current.getRequired());
            request.setUniqueField(current.getUniqueField());
            request.setOptionsJson(options != null ? options : current.getOptionsJson());
            request.setDefaultValue(defaultValue != null ? defaultValue : current.getDefaultValue());
            request.setPlaceholder(placeholder != null ? placeholder : current.getPlaceholder());
            request.setValidationJson(current.getValidationJson());
            request.setDisplayOrder(current.getDisplayOrder());
            request.setSearchable(current.getSearchable());
            request.setFilterable(current.getFilterable());
            request.setSortable(current.getSortable());
            request.setHidden(current.getHidden());
            FieldResponse updated = fieldService.update(fieldId, request);
            return Map.of("updated", true, "formName", form.get().getName(), "field", updated);
        } catch (BadRequestException e) {
            return Map.of("updated", false, "reason", e.getMessage());
        } catch (Exception e) {
            log.warn("updateField tool failed: {}", e.getMessage());
            return Map.of("updated", false, "reason",
                    "The field could not be updated right now. Ask the user to try again later.");
        }
    }

    /* "Customer Email!" -> "customer_email" */
    private String toFieldKey(String value) {
        return value.trim().toLowerCase()
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("(^_+|_+$)", "");
    }
}
