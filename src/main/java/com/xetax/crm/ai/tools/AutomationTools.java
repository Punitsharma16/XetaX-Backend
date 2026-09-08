package com.xetax.crm.ai.tools;

import com.xetax.crm.team.service.RequiresPermission;

import com.xetax.crm.automation.dto.AutomationRequest;
import com.xetax.crm.automation.dto.AutomationResponse;
import com.xetax.crm.automation.enums.AutomationActionType;
import com.xetax.crm.automation.enums.AutomationTrigger;
import com.xetax.crm.automation.service.AutomationService;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.common.exception.ResourceNotFoundException;
import com.xetax.crm.data_manager.dto.FormResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Spring AI READ + WRITE tools for AUTOMATION rules.
 *
 * <p>Security: automations belong to forms, and forms belong to users — so
 * every read is filtered to automations whose form is owned by the
 * authenticated user (via FormTools' ownership gate, which reads the
 * SecurityContext), and creation is only allowed on an owned form. Another
 * user's automation behaves exactly like one that does not exist. userId is
 * never a tool parameter.
 *
 * <p>Business logic stays in AutomationService (same path the REST API
 * uses): trigger-stage/action-field belong-to-form validation, save, and
 * automation knowledge indexing.
 */
@Slf4j
@Component
public class AutomationTools {

    private static final String NOT_ACCESSIBLE =
            "No automation with this id exists for the current user.";

    private final AutomationService automationService;

    private final FormTools formTools;

    public AutomationTools(AutomationService automationService, FormTools formTools) {
        this.automationService = automationService;
        this.formTools = formTools;
    }

    /* Ids of the authenticated user's own forms — the ownership boundary for
       every automation read. */
    private Set<Long> myFormIds() {
        return formTools.getMyForms().stream()
                .map(FormResponse::getId)
                .collect(Collectors.toSet());
    }

    @Tool(description = """
            READ-ONLY. Get all automation rules configured on the currently
            authenticated user's forms (identity comes from the logged-in
            session — never pass or ask for a user id). Use when the user
            asks which automations/rules they have or how many. Each rule
            shows its form, trigger, optional trigger stage, action and
            active flag.
            """)
    @RequiresPermission("automations.view")
    public List<AutomationResponse> getMyAutomations() {
        try {
            Set<Long> mine = myFormIds();
            return automationService.getAll().stream()
                    .filter(a -> a.getFormId() != null && mine.contains(a.getFormId()))
                    .toList();
        } catch (Exception e) {
            log.warn("getMyAutomations tool failed: {}", e.getMessage());
            return List.of();
        }
    }

    @Tool(description = """
            READ-ONLY. Get one automation rule of the currently authenticated
            user by its numeric id (resolve it via getMyAutomations first).
            Returns found=false when the automation does not exist for this
            user.
            """)
    @RequiresPermission("automations.view")
    public Map<String, Object> getAutomationDetails(
            @ToolParam(description = "Numeric id of the automation rule") Long automationId) {
        try {
            return getMyAutomations().stream()
                    .filter(a -> automationId != null && automationId.equals(a.getId()))
                    .findFirst()
                    .<Map<String, Object>>map(a -> Map.of("found", true, "automation", a))
                    .orElseGet(() -> Map.of("found", false, "message", NOT_ACCESSIBLE));
        } catch (Exception e) {
            log.warn("getAutomationDetails tool failed: {}", e.getMessage());
            return Map.of("found", false, "message", NOT_ACCESSIBLE);
        }
    }

    @Tool(description = """
            WRITE. Update an existing automation rule of the currently
            authenticated CRM user. Use ONLY when the user explicitly asks to
            change a rule. Only the arguments you pass change — everything
            else keeps its current value (the rule stays on its current
            form). Arguments: automationId — the rule's numeric id (resolve
            via getMyAutomations); name, description — new values; trigger —
            RECORD_CREATED / RECORD_UPDATED / STAGE_CHANGED; triggerStageId —
            for STAGE_CHANGED, the stage that fires the rule; actionType —
            UPDATE_FIELD / CHANGE_STAGE / ASSIGN_USER / SEND_EMAIL /
            ADJUST_FIELD; actionFieldId — the action's target field;
            actionValue — new value / destination stage id / signed amount;
            emailSubject, emailMessage — for SEND_EMAIL. Returns updated=true
            with the rule, or updated=false with a reason.
            """)
    @RequiresPermission("automations.manage")
    public Map<String, Object> updateAutomation(
            @ToolParam(description = "Numeric id of the automation rule to update") Long automationId,
            @ToolParam(required = false, description = "New name; omit to keep") String name,
            @ToolParam(required = false, description = "New description; omit to keep") String description,
            @ToolParam(required = false, description = "New trigger; omit to keep") String trigger,
            @ToolParam(required = false, description = "New trigger stage id (STAGE_CHANGED); omit to keep") Long triggerStageId,
            @ToolParam(required = false, description = "New action type; omit to keep") String actionType,
            @ToolParam(required = false, description = "New action field id; omit to keep") Long actionFieldId,
            @ToolParam(required = false, description = "New action value; omit to keep") String actionValue,
            @ToolParam(required = false, description = "New email subject; omit to keep") String emailSubject,
            @ToolParam(required = false, description = "New email message; omit to keep") String emailMessage) {

        AutomationResponse current = getMyAutomations().stream()
                .filter(a -> automationId != null && automationId.equals(a.getId()))
                .findFirst()
                .orElse(null);
        if (current == null) {
            return Map.of("updated", false, "reason", NOT_ACCESSIBLE);
        }

        AutomationTrigger parsedTrigger = current.getTrigger();
        if (trigger != null && !trigger.isBlank()) {
            try {
                parsedTrigger = AutomationTrigger.valueOf(trigger.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return Map.of("updated", false, "reason",
                        "Unknown trigger. Valid triggers: " + Arrays.toString(AutomationTrigger.values()));
            }
        }
        AutomationActionType parsedAction = current.getActionType();
        if (actionType != null && !actionType.isBlank()) {
            try {
                parsedAction = AutomationActionType.valueOf(actionType.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return Map.of("updated", false, "reason",
                        "Unknown action type. Valid actions: " + Arrays.toString(AutomationActionType.values()));
            }
        }
        try {
            /* The service's update replaces every rule column, so unchanged
               values are carried over; its stage/field belong-to-form checks
               and knowledge reindexing still apply. */
            AutomationRequest request = new AutomationRequest();
            request.setName(name != null && !name.isBlank() ? name.trim() : current.getName());
            request.setDescription(description != null ? description : current.getDescription());
            request.setFormId(current.getFormId());
            request.setTrigger(parsedTrigger);
            request.setTriggerStageId(parsedTrigger != AutomationTrigger.STAGE_CHANGED ? null
                    : (triggerStageId != null ? triggerStageId : current.getTriggerStageId()));
            request.setActionType(parsedAction);
            request.setActionFieldId(actionFieldId != null ? actionFieldId : current.getActionFieldId());
            request.setActionValue(actionValue != null ? actionValue : current.getActionValue());
            request.setEmailSubject(emailSubject != null ? emailSubject : current.getEmailSubject());
            request.setEmailMessage(emailMessage != null ? emailMessage : current.getEmailMessage());
            AutomationResponse updated = automationService.update(automationId, request);
            return Map.of("updated", true, "automation", updated);
        } catch (BadRequestException | ResourceNotFoundException e) {
            return Map.of("updated", false, "reason", e.getMessage());
        } catch (Exception e) {
            log.warn("updateAutomation tool failed: {}", e.getMessage());
            return Map.of("updated", false, "reason",
                    "The automation could not be updated right now. Ask the user to try again later.");
        }
    }

    @Tool(description = """
            WRITE. Create a NEW automation rule on one form of the currently
            authenticated CRM user (ownership comes from the logged-in
            session — never pass or ask for a user id). Use ONLY when the
            user explicitly asks to create an automation/rule. Arguments:
            formId — numeric id of the user's form (resolve first via
            findMyFormByName/getMyForms); name — the rule's name (required);
            trigger — when the rule fires: RECORD_CREATED, RECORD_UPDATED or
            STAGE_CHANGED (required); triggerStageId — only for
            STAGE_CHANGED: fire only when a record lands on this stage
            (resolve the stage id via getFormStages; omit to fire on any
            stage change); actionType — what the rule does: UPDATE_FIELD,
            CHANGE_STAGE, ASSIGN_USER, SEND_EMAIL or ADJUST_FIELD;
            actionFieldId — the target field's id (resolve via
            getFormFields): for UPDATE_FIELD/ADJUST_FIELD the field to
            change, for SEND_EMAIL the field holding the recipient's email
            address; actionValue — UPDATE_FIELD: the new value ·
            CHANGE_STAGE: the destination stage id · ADJUST_FIELD: a signed
            amount like "10" or "-5"; emailSubject and emailMessage — for
            SEND_EMAIL (the message may contain {fieldKey} placeholders
            that are filled from the record); description — optional.
            Returns created=true with the rule, or created=false with a
            reason to relay to the user.
            """)
    @RequiresPermission("automations.manage")
    public Map<String, Object> createAutomation(
            @ToolParam(description = "Numeric id of the form the rule belongs to") Long formId,
            @ToolParam(description = "Name of the automation rule") String name,
            @ToolParam(description = "Trigger: RECORD_CREATED, RECORD_UPDATED or STAGE_CHANGED") String trigger,
            @ToolParam(required = false, description = "STAGE_CHANGED only: numeric stage id that fires the rule; omit for any stage") Long triggerStageId,
            @ToolParam(required = false, description = "Action: UPDATE_FIELD, CHANGE_STAGE, ASSIGN_USER, SEND_EMAIL or ADJUST_FIELD") String actionType,
            @ToolParam(required = false, description = "Numeric id of the action's target field (recipient-email field for SEND_EMAIL)") Long actionFieldId,
            @ToolParam(required = false, description = "Action value: new field value / destination stage id / signed adjust amount") String actionValue,
            @ToolParam(required = false, description = "SEND_EMAIL: subject line") String emailSubject,
            @ToolParam(required = false, description = "SEND_EMAIL: message body, {fieldKey} placeholders allowed") String emailMessage,
            @ToolParam(required = false, description = "Optional description of the rule") String description) {

        Optional<FormResponse> form = formTools.ownedFormOf(formId);
        if (form.isEmpty()) {
            return Map.of("created", false, "reason",
                    "No form with this id exists for the current user.");
        }
        if (name == null || name.isBlank()) {
            return Map.of("created", false, "reason", "An automation name is required.");
        }
        AutomationTrigger parsedTrigger;
        try {
            parsedTrigger = AutomationTrigger.valueOf(trigger == null ? "" : trigger.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Map.of("created", false, "reason",
                    "Unknown trigger. Valid triggers: " + Arrays.toString(AutomationTrigger.values()));
        }
        AutomationActionType parsedAction = null;
        if (actionType != null && !actionType.isBlank()) {
            try {
                parsedAction = AutomationActionType.valueOf(actionType.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return Map.of("created", false, "reason",
                        "Unknown action type. Valid actions: " + Arrays.toString(AutomationActionType.values()));
            }
        }
        if (parsedAction == AutomationActionType.SEND_EMAIL && actionFieldId == null) {
            return Map.of("created", false, "reason",
                    "SEND_EMAIL needs actionFieldId — the field that holds the recipient's email address (see getFormFields).");
        }
        try {
            AutomationRequest request = new AutomationRequest();
            request.setName(name.trim());
            request.setDescription(description);
            request.setFormId(formId);
            request.setTrigger(parsedTrigger);
            request.setTriggerStageId(parsedTrigger == AutomationTrigger.STAGE_CHANGED ? triggerStageId : null);
            request.setActionType(parsedAction);
            request.setActionFieldId(actionFieldId);
            request.setActionValue(actionValue);
            request.setEmailSubject(emailSubject);
            request.setEmailMessage(emailMessage);
            /*
             * AutomationService.create() is the same path the REST API uses:
             * it validates that the trigger stage and action field belong to
             * this form, saves, and indexes the rule's knowledge
             * (failure-safe). Ownership was already verified above.
             */
            AutomationResponse created = automationService.create(request);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("created", true);
            result.put("formName", form.get().getName());
            result.put("automation", created);
            return result;
        } catch (BadRequestException | ResourceNotFoundException e) {
            return Map.of("created", false, "reason", e.getMessage());
        } catch (Exception e) {
            log.warn("createAutomation tool failed: {}", e.getMessage());
            return Map.of("created", false, "reason",
                    "The automation could not be created right now. Ask the user to try again later.");
        }
    }
}
