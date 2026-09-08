package com.xetax.crm.ai.tools;

import com.xetax.crm.team.service.RequiresPermission;

import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.data_manager.dto.FormResponse;
import com.xetax.crm.data_manager.dto.StageRequest;
import com.xetax.crm.data_manager.dto.StageResponse;
import com.xetax.crm.data_manager.enums.StageStatus;
import com.xetax.crm.data_manager.service.StageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Spring AI WRITE tools for form STAGES. (Stage READ access already exists in
 * FormTools — getFormStages, getDefaultStage, getFinalStages — not duplicated
 * here.)
 *
 * <p>Security: every call goes through FormTools' ownership gate
 * (ownedFormOf) which resolves the authenticated user from the
 * SecurityContext, so another user's formId behaves exactly like a formId
 * that does not exist. userId is never a tool parameter.
 *
 * <p>Business logic stays in StageService (same path the REST API uses):
 * duplicate code/sequence rules, single-default rule, save, and stage
 * knowledge indexing with the parent form's name.
 */
@Slf4j
@Component
public class StageTools {

    private final StageService stageService;

    private final FormTools formTools;

    public StageTools(StageService stageService, FormTools formTools) {
        this.stageService = stageService;
        this.formTools = formTools;
    }

    @Tool(description = """
            WRITE. Add a NEW pipeline stage to one form of the currently
            authenticated CRM user (ownership comes from the logged-in
            session — never pass or ask for a user id). Use ONLY when the
            user explicitly asks to add/create a stage. Arguments: formId —
            numeric id of the user's form (resolve via findMyFormByName /
            getMyForms first); name — the stage's display name (required);
            sequence — the stage's position/order in the pipeline (required;
            check getFormStages first and pick the next free number unless
            the user says otherwise); code — optional short unique code,
            omit to derive from the name; isDefault — true when new records
            should land on this stage (only one default per form is allowed);
            isFinal — true when this stage ends the pipeline (e.g. Won/Lost/
            Closed). Returns created=true with the stage, or created=false
            with a reason (duplicate code/sequence or default already exists
            — relay it and ask the user how to proceed).
            """)
    @RequiresPermission("forms.manage")
    public Map<String, Object> createStage(
            @ToolParam(description = "Numeric id of the form the stage belongs to") Long formId,
            @ToolParam(description = "Display name of the new stage") String name,
            @ToolParam(description = "Position of the stage in the pipeline (1 = first)") Integer sequence,
            @ToolParam(required = false, description = "Optional short unique code; omit to derive from the name") String code,
            @ToolParam(required = false, description = "true when new records should land here (default false)") Boolean isDefault,
            @ToolParam(required = false, description = "true when this stage ends the pipeline (default false)") Boolean isFinal) {

        Optional<FormResponse> form = formTools.ownedFormOf(formId);
        if (form.isEmpty()) {
            return Map.of("created", false, "reason",
                    "No form with this id exists for the current user.");
        }
        if (name == null || name.isBlank()) {
            return Map.of("created", false, "reason", "A stage name is required.");
        }
        if (sequence == null || sequence < 1) {
            return Map.of("created", false, "reason",
                    "A positive sequence (pipeline position) is required.");
        }
        String finalCode = (code == null || code.isBlank()) ? toCode(name) : toCode(code);
        if (finalCode.isBlank()) {
            return Map.of("created", false, "reason",
                    "Could not derive a valid code from this name — ask the user for a code.");
        }
        try {
            StageRequest request = new StageRequest();
            request.setName(name.trim());
            request.setCode(finalCode);
            request.setSequence(sequence);
            request.setIsDefault(Boolean.TRUE.equals(isDefault));
            request.setIsFinal(Boolean.TRUE.equals(isFinal));
            request.setStatus(StageStatus.ACTIVE);
            /*
             * StageService.create() is the same path the REST API uses:
             * form-exists check, duplicate code/sequence checks, the
             * one-default-per-form rule, save, and stage knowledge indexing
             * with the parent form's name (failure-safe). Ownership was
             * already verified above.
             */
            StageResponse created = stageService.create(formId, request);
            return Map.of("created", true, "formName", form.get().getName(), "stage", created);
        } catch (BadRequestException e) {
            return Map.of("created", false, "reason", e.getMessage());
        } catch (Exception e) {
            log.warn("createStage tool failed: {}", e.getMessage());
            return Map.of("created", false, "reason",
                    "The stage could not be created right now. Ask the user to try again later.");
        }
    }

    @Tool(description = """
            WRITE. Update an existing pipeline stage of one form of the
            currently authenticated CRM user. Use ONLY when the user
            explicitly asks to change a stage (rename it, reorder it, make it
            the default, mark/unmark it final). Only the arguments you pass
            change — everything else keeps its current value; the stage's
            code never changes. Arguments: formId — the form's numeric id;
            stageId — the stage's numeric id (resolve via getFormStages);
            name — new display name; sequence — new pipeline position;
            isDefault — true to make it the default stage (only one default
            per form is allowed); isFinal — true/false. Returns updated=true
            with the stage, or updated=false with a reason.
            """)
    @RequiresPermission("forms.manage")
    public Map<String, Object> updateStage(
            @ToolParam(description = "Numeric id of the form") Long formId,
            @ToolParam(description = "Numeric id of the stage to update") Long stageId,
            @ToolParam(required = false, description = "New display name; omit to keep") String name,
            @ToolParam(required = false, description = "New pipeline position; omit to keep") Integer sequence,
            @ToolParam(required = false, description = "New default flag; omit to keep") Boolean isDefault,
            @ToolParam(required = false, description = "New final flag; omit to keep") Boolean isFinal) {

        Optional<FormResponse> form = formTools.ownedFormOf(formId);
        if (form.isEmpty()) {
            return Map.of("updated", false, "reason",
                    "No form with this id exists for the current user.");
        }
        StageResponse current = stageService.getAll(formId).stream()
                .filter(s -> stageId != null && stageId.equals(s.getId()))
                .findFirst()
                .orElse(null);
        if (current == null) {
            return Map.of("updated", false, "reason",
                    "No stage with this id exists on this form.");
        }
        try {
            /* The service's update replaces every column, so unchanged values
               are carried over; its duplicate-sequence and one-default rules
               still apply. */
            StageRequest request = new StageRequest();
            request.setName(name != null && !name.isBlank() ? name.trim() : current.getName());
            request.setCode(current.getCode());
            request.setColor(current.getColor());
            request.setSequence(sequence != null ? sequence : current.getSequence());
            request.setIsDefault(isDefault != null ? isDefault : current.getIsDefault());
            request.setIsFinal(isFinal != null ? isFinal : current.getIsFinal());
            request.setStatus(current.getStatus());
            StageResponse updated = stageService.update(stageId, request);
            return Map.of("updated", true, "formName", form.get().getName(), "stage", updated);
        } catch (BadRequestException e) {
            return Map.of("updated", false, "reason", e.getMessage());
        } catch (Exception e) {
            log.warn("updateStage tool failed: {}", e.getMessage());
            return Map.of("updated", false, "reason",
                    "The stage could not be updated right now. Ask the user to try again later.");
        }
    }

    /* "Deal Won!" -> "DEAL_WON" */
    private String toCode(String value) {
        return value.trim().toUpperCase()
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("(^_+|_+$)", "");
    }
}
