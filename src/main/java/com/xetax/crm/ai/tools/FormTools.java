package com.xetax.crm.ai.tools;

import com.xetax.crm.team.service.RequiresPermission;

import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.data_manager.dto.FieldResponse;
import com.xetax.crm.data_manager.dto.FormRequest;
import com.xetax.crm.data_manager.dto.FormResponse;
import com.xetax.crm.data_manager.dto.StageResponse;
import com.xetax.crm.data_manager.service.FieldService;
import com.xetax.crm.data_manager.service.FormService;
import com.xetax.crm.data_manager.service.StageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Spring AI tools for the Form module — read tools plus one write tool
 * (createForm).
 *
 * <p>Every tool is scoped to the CURRENTLY AUTHENTICATED user: tools run on
 * the request thread, so FormService.getAll() (which reads the verified JWT
 * identity from the SecurityContext) already returns only the caller's own
 * forms — ownership of any formId is checked against that list. userId is
 * NEVER a tool parameter, and a form belonging to another user is
 * indistinguishable from a form that does not exist. createForm likewise
 * stamps ownership inside FormService from the SecurityContext.
 *
 * <p>Tools call the existing service layer directly (FormService,
 * FieldService, StageService) — no business logic lives here.
 */
@Slf4j
@Component
public class FormTools {

    private static final String NOT_ACCESSIBLE =
            "No form with this id exists for the current user.";
    private static final String UNAVAILABLE =
            "Form data is temporarily unavailable. Ask the user to try again later.";

    private final FormService formService;

    private final FieldService fieldService;

    private final StageService stageService;

    public FormTools(FormService formService, FieldService fieldService, StageService stageService) {
        this.formService = formService;
        this.fieldService = fieldService;
        this.stageService = stageService;
    }

    /* The one ownership gate every formId-taking tool goes through: the id
       must appear in the authenticated user's own form list. */
    private Optional<FormResponse> ownedForm(Long formId) {
        if (formId == null) {
            return Optional.empty();
        }
        return formService.getAll().stream()
                .filter(f -> formId.equals(f.getId()))
                .findFirst();
    }

    private Map<String, Object> notAccessible() {
        return Map.of("found", false, "message", NOT_ACCESSIBLE);
    }

    @Tool(description = """
            READ-ONLY. Get all forms belonging to the currently authenticated
            CRM user (identity is taken from the logged-in session — never
            pass or ask for a user id). Use this when the user asks which
            forms they have, how many forms they have, or to resolve a form
            name mentioned by the user into its formId for other tools.
            """)
    @RequiresPermission("forms.view")
    public List<FormResponse> getMyForms() {
        try {
            return formService.getAll();
        } catch (Exception e) {
            log.warn("getMyForms tool failed: {}", e.getMessage());
            return List.of();
        }
    }

    @Tool(description = """
            WRITE. Create a NEW form for the currently authenticated CRM user
            (ownership is taken from the logged-in session — never pass or
            ask for a user id). Use ONLY when the user explicitly asks to
            create a form. Arguments: name — the form's display name
            (required); description — short description: when the user's
            request states the form's purpose (e.g. "for tracking support
            tickets"), pass that purpose here instead of leaving it empty;
            slug —
            optional URL-friendly unique identifier, leave it out to derive
            one from the name automatically. Returns created=true with the
            new form, or created=false with a reason (for example when the
            slug is already taken — then ask the user for a different
            name/slug).
            """)
    @RequiresPermission("forms.manage")
    public Map<String, Object> createForm(
            @ToolParam(description = "Display name of the new form") String name,
            @ToolParam(required = false, description = "Optional short description of the form") String description,
            @ToolParam(required = false, description = "Optional unique URL-friendly slug; omit to derive from the name") String slug) {
        if (name == null || name.isBlank()) {
            return Map.of("created", false, "reason", "A form name is required.");
        }
        String finalSlug = (slug == null || slug.isBlank()) ? toSlug(name) : toSlug(slug);
        if (finalSlug.isBlank()) {
            return Map.of("created", false, "reason", "Could not derive a valid slug from this name — ask the user for a slug.");
        }
        try {
            FormRequest request = new FormRequest();
            request.setName(name.trim());
            request.setSlug(finalSlug);
            request.setDescription(description);
            /*
             * FormService.create() does everything else exactly as the REST
             * API does: slug-duplicate check, owner stamping from the
             * SecurityContext, save, and knowledge indexing (which never
             * breaks the transaction on Qdrant failure).
             */
            FormResponse created = formService.create(request);
            return Map.of("created", true, "form", created);
        } catch (BadRequestException e) {
            return Map.of("created", false, "reason", e.getMessage());
        } catch (Exception e) {
            log.warn("createForm tool failed: {}", e.getMessage());
            return Map.of("created", false, "reason",
                    "The form could not be created right now. Ask the user to try again later.");
        }
    }

    /* "Support Tickets!" -> "support-tickets" */
    private String toSlug(String value) {
        return value.trim().toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
    }

    @Tool(description = """
            WRITE. Update an existing form of the currently authenticated CRM
            user. Use ONLY when the user explicitly asks to rename a form or
            change its description/slug. Only the arguments you pass change —
            everything else keeps its current value. Arguments: formId —
            numeric id of the user's form (resolve via findMyFormByName /
            getMyForms first); name — new display name; description — new
            description; slug — new unique URL slug (changing it changes the
            form's record URLs — only pass it when the user asked for it).
            Returns updated=true with the form, or updated=false with a
            reason.
            """)
    @RequiresPermission("forms.manage")
    public Map<String, Object> updateForm(
            @ToolParam(description = "Numeric id of the form to update") Long formId,
            @ToolParam(required = false, description = "New display name; omit to keep the current one") String name,
            @ToolParam(required = false, description = "New description; omit to keep the current one") String description,
            @ToolParam(required = false, description = "New unique slug; omit to keep the current one") String slug) {

        Optional<FormResponse> form = ownedForm(formId);
        if (form.isEmpty()) {
            return Map.of("updated", false, "reason", NOT_ACCESSIBLE);
        }
        FormResponse current = form.get();
        try {
            /* The service's update replaces every column, so unchanged values
               are carried over from the current form. */
            FormRequest request = new FormRequest();
            request.setName(name != null && !name.isBlank() ? name.trim() : current.getName());
            request.setSlug(slug != null && !slug.isBlank() ? toSlug(slug) : current.getSlug());
            request.setDescription(description != null ? description : current.getDescription());
            request.setIcon(current.getIcon());
            request.setColor(current.getColor());
            FormResponse updated = formService.update(formId, request);
            return Map.of("updated", true, "form", updated);
        } catch (BadRequestException e) {
            return Map.of("updated", false, "reason", e.getMessage());
        } catch (Exception e) {
            log.warn("updateForm tool failed: {}", e.getMessage());
            return Map.of("updated", false, "reason",
                    "The form could not be updated right now. Ask the user to try again later.");
        }
    }

    /* Package-private so FormFieldTools reuses the same ownership gate
       instead of duplicating it. */
    Optional<FormResponse> ownedFormOf(Long formId) {
        return ownedForm(formId);
    }

    @Tool(description = """
            READ-ONLY. Get detailed information about ONE form of the
            currently authenticated user. Use after the formId is known
            (from getMyForms or findMyFormByName). Argument formId: the
            numeric id of the form. Returns found=false when the form does
            not exist for this user.
            """)
    @RequiresPermission("forms.view")
    public Map<String, Object> getFormDetails(
            @ToolParam(description = "Numeric id of the form") Long formId) {
        try {
            return ownedForm(formId)
                    .<Map<String, Object>>map(f -> Map.of("found", true, "form", f))
                    .orElseGet(this::notAccessible);
        } catch (Exception e) {
            log.warn("getFormDetails tool failed: {}", e.getMessage());
            return Map.of("found", false, "message", UNAVAILABLE);
        }
    }

    @Tool(description = """
            READ-ONLY. Find the authenticated user's form(s) by name. The
            match is case-insensitive and partial, so a natural-language
            name like "support tickets" works. Argument name: the form name
            (or part of it) the user mentioned. If matchCount is more than 1,
            DO NOT pick one yourself — ask the user which of the returned
            forms they mean.
            """)
    @RequiresPermission("forms.view")
    public Map<String, Object> findMyFormByName(
            @ToolParam(description = "Form name or part of it, as the user said it") String name) {
        try {
            if (name == null || name.isBlank()) {
                return Map.of("matchCount", 0, "matches", List.of());
            }
            String needle = name.trim().toLowerCase();
            List<FormResponse> matches = formService.getAll().stream()
                    .filter(f -> f.getName() != null && f.getName().toLowerCase().contains(needle))
                    .toList();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("matchCount", matches.size());
            result.put("matches", matches);
            if (matches.isEmpty()) {
                result.put("message", "The current user has no form matching this name.");
            } else if (matches.size() > 1) {
                result.put("message", "Multiple forms match — ask the user which one they mean.");
            }
            return result;
        } catch (Exception e) {
            log.warn("findMyFormByName tool failed: {}", e.getMessage());
            return Map.of("matchCount", 0, "matches", List.of(), "message", UNAVAILABLE);
        }
    }

    @Tool(description = """
            READ-ONLY. Get the fields of one form of the currently
            authenticated user (label, key, type, required flag, options,
            default value, display order). Argument formId: the numeric id
            of the form. Returns found=false when the form does not exist
            for this user.
            """)
    @RequiresPermission("forms.view")
    public Map<String, Object> getFormFields(
            @ToolParam(description = "Numeric id of the form") Long formId) {
        try {
            Optional<FormResponse> form = ownedForm(formId);
            if (form.isEmpty()) {
                return notAccessible();
            }
            List<FieldResponse> fields = fieldService.getAll(formId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("found", true);
            result.put("formName", form.get().getName());
            result.put("fieldCount", fields.size());
            result.put("fields", fields);
            return result;
        } catch (Exception e) {
            log.warn("getFormFields tool failed: {}", e.getMessage());
            return Map.of("found", false, "message", UNAVAILABLE);
        }
    }

    @Tool(description = """
            READ-ONLY. Get the pipeline stages configured for one form of the
            currently authenticated user (name, code, sequence/order, whether
            it is the default stage, whether it is a final stage, and its
            status). Argument formId: the numeric id of the form. Returns
            found=false when the form does not exist for this user.
            """)
    @RequiresPermission("forms.view")
    public Map<String, Object> getFormStages(
            @ToolParam(description = "Numeric id of the form") Long formId) {
        try {
            Optional<FormResponse> form = ownedForm(formId);
            if (form.isEmpty()) {
                return notAccessible();
            }
            List<StageResponse> stages = stageService.getAll(formId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("found", true);
            result.put("formName", form.get().getName());
            result.put("stageCount", stages.size());
            result.put("stages", stages);
            return result;
        } catch (Exception e) {
            log.warn("getFormStages tool failed: {}", e.getMessage());
            return Map.of("found", false, "message", UNAVAILABLE);
        }
    }

    @Tool(description = """
            READ-ONLY. Get the DEFAULT stage (where new records land) of one
            form of the currently authenticated user. Argument formId: the
            numeric id of the form. If no default stage is configured, the
            result says so — report that to the user instead of guessing.
            """)
    @RequiresPermission("forms.view")
    public Map<String, Object> getDefaultStage(
            @ToolParam(description = "Numeric id of the form") Long formId) {
        try {
            Optional<FormResponse> form = ownedForm(formId);
            if (form.isEmpty()) {
                return notAccessible();
            }
            return stageService.getAll(formId).stream()
                    .filter(s -> Boolean.TRUE.equals(s.getIsDefault()))
                    .findFirst()
                    .<Map<String, Object>>map(s -> Map.of("found", true, "defaultStage", s))
                    .orElseGet(() -> Map.of("found", true, "defaultStage", "none",
                            "message", "No default stage is configured for this form."));
        } catch (Exception e) {
            log.warn("getDefaultStage tool failed: {}", e.getMessage());
            return Map.of("found", false, "message", UNAVAILABLE);
        }
    }

    @Tool(description = """
            READ-ONLY. Get the FINAL stage(s) (end of the pipeline) of one
            form of the currently authenticated user. Argument formId: the
            numeric id of the form. If no final stage is configured, the
            result says so — report that to the user instead of guessing.
            """)
    @RequiresPermission("forms.view")
    public Map<String, Object> getFinalStages(
            @ToolParam(description = "Numeric id of the form") Long formId) {
        try {
            Optional<FormResponse> form = ownedForm(formId);
            if (form.isEmpty()) {
                return notAccessible();
            }
            List<StageResponse> finals = stageService.getAll(formId).stream()
                    .filter(s -> Boolean.TRUE.equals(s.getIsFinal()))
                    .toList();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("found", true);
            result.put("finalStageCount", finals.size());
            result.put("finalStages", finals);
            if (finals.isEmpty()) {
                result.put("message", "No final stage is configured for this form.");
            }
            return result;
        } catch (Exception e) {
            log.warn("getFinalStages tool failed: {}", e.getMessage());
            return Map.of("found", false, "message", UNAVAILABLE);
        }
    }

    @Tool(description = """
            READ-ONLY. Get a complete overview of one form of the currently
            authenticated user in a single call: the form's details, all its
            fields, all its stages, the default stage and the final stages.
            Prefer this when the user asks to know "everything" about a form.
            Argument formId: the numeric id of the form.
            """)
    @RequiresPermission("forms.view")
    public Map<String, Object> getFormOverview(
            @ToolParam(description = "Numeric id of the form") Long formId) {
        try {
            Optional<FormResponse> form = ownedForm(formId);
            if (form.isEmpty()) {
                return notAccessible();
            }
            List<FieldResponse> fields = fieldService.getAll(formId);
            List<StageResponse> stages = stageService.getAll(formId);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("found", true);
            result.put("form", form.get());
            result.put("fieldCount", fields.size());
            result.put("fields", fields);
            result.put("stageCount", stages.size());
            result.put("stages", stages);
            result.put("defaultStage", stages.stream()
                    .filter(s -> Boolean.TRUE.equals(s.getIsDefault()))
                    .findFirst().map(StageResponse::getName).orElse("none configured"));
            result.put("finalStages", stages.stream()
                    .filter(s -> Boolean.TRUE.equals(s.getIsFinal()))
                    .map(StageResponse::getName)
                    .toList());
            return result;
        } catch (Exception e) {
            log.warn("getFormOverview tool failed: {}", e.getMessage());
            return Map.of("found", false, "message", UNAVAILABLE);
        }
    }
}
