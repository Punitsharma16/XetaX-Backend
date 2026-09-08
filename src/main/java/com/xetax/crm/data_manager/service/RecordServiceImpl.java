package com.xetax.crm.data_manager.service;

import com.xetax.crm.automation.engine.AutomationEngine;
import com.xetax.crm.automation.enums.AutomationTrigger;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.common.exception.ResourceNotFoundException;
import com.xetax.crm.data_manager.documents.RecordDocument;
import com.xetax.crm.data_manager.dto.RecordRequest;
import com.xetax.crm.data_manager.dto.RecordResponse;
import com.xetax.crm.data_manager.dto.SeacrhDto.RecordSearchRequest;
import com.xetax.crm.data_manager.entity.FormEntity;
import com.xetax.crm.data_manager.entity.FormField;
import com.xetax.crm.data_manager.entity.FormStage;
import com.xetax.crm.data_manager.mappers.RecordMapper;
import com.xetax.crm.data_manager.repository.*;
import com.xetax.crm.data_manager.validator.DynamicValidationService;
import lombok.AllArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@AllArgsConstructor
public class RecordServiceImpl implements RecordService{

    private final FormRepo formRepo;

    private final FormFieldRepo formFieldRepo;

    private final DynamicValidationService validationService;

    private final StageRepo stageRepo;

    private final RecordRepo recordRepo;

    private RecordSearchRepo recordSearchRepo;

    private final AutomationEngine automationEngine;

    private final OwnershipGuard ownershipGuard;

    private final FormMetaCache formMetaCache;

    private final RecordMapper mapper;

    private final com.xetax.crm.team.service.TeamService teamService;

    private final com.xetax.crm.notification.NotificationService notificationService;


    private final com.xetax.crm.team.service.PermissionService permissionService;

    private final com.xetax.crm.auth.security.CurrentUserProvider currentUserProvider;

    private final com.xetax.crm.activity.RecordActivityService activityService;


    @Override
    public RecordResponse create(String slug, RecordRequest request) {

        FormEntity form = formRepo.findBySlug(slug).orElseThrow(()-> new ResourceNotFoundException("Form Not Found"));
        ownershipGuard.assertOwned(form);

        List<FormField> fields = formMetaCache.getFields(form.getId());

        Map<String , Object> validatedData =validationService.validate(request , fields);

        FormStage defaultStage = formMetaCache.getStages(form.getId()).stream()
                .filter(s -> Boolean.TRUE.equals(s.getIsDefault()))
                .findFirst()
                .orElseThrow(()-> new ResourceNotFoundException("Default stage not configured"));

        RecordDocument record = RecordDocument.builder()
                .formId(form.getId())
                .stageId(defaultStage.getId())
                .data(validatedData)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        RecordDocument saved = recordRepo.save(record);
        automationEngine.execute(
                AutomationTrigger.RECORD_CREATED,
                form,
                saved
        );
        activityService.log(saved.getId(), form.getOwnerUserId(), "CREATED",
                "Record created in stage '" + defaultStage.getName() + "'");
        // Bell: owner ko batao jab record kisi AUR ne banaya (member/AI) —
        // khud ka create noise hota. Public/webhook creates PublicFormService
        // se aate hain jo apna notification khud push karta hai.
        java.util.UUID actor = currentUserProvider.currentUserIdOrNull();
        if (actor == null || !actor.toString().equals(form.getOwnerUserId())) {
            notificationService.push(form.getOwnerUserId(), form.getOwnerUserId(),
                    "RECORD_CREATED", "New " + form.getName() + " entry",
                    validatedData.values().stream()
                            .filter(v -> v instanceof String str && !str.isBlank())
                            .map(Object::toString).findFirst().orElse(null),
                    "/app/records/" + form.getSlug() + "/" + saved.getId());
        }
        return mapper.toResponse(saved);
    }

    @Override
    public Page<RecordResponse> getAll(String slug,
                                       int page,
                                       int size,
                                       String sort,
                                       String direction) {
        FormEntity form = formRepo.findBySlug(slug).orElseThrow(()-> new ResourceNotFoundException("Form Not Found"));
        ownershipGuard.assertOwned(form);

        Sort sorting = direction.equalsIgnoreCase("DESC") ?
                Sort.by(sort).descending() : Sort.by(sort).ascending();

        Pageable pageable = PageRequest.of(page,size,sorting);

        String ownOnly = ownOnlyFilterOrNull();
        Page<RecordDocument> recordDocuments = ownOnly == null
                ? recordRepo.findByFormId(form.getId(), pageable)
                : recordRepo.findByFormIdAndAssignedTo(form.getId(), ownOnly, pageable);
        return recordDocuments.map(mapper :: toResponse);
    }

    @Override
    public RecordResponse getById(String id) {
        RecordDocument record = recordRepo.findById(id).orElseThrow(
                ()-> new ResourceNotFoundException("Record Not Field"));
        ownershipGuard.requireOwnedForm(record.getFormId());
        assertVisible(record);
        return mapper.toResponse(record);
    }

    @Override
    public RecordResponse update(String id, RecordRequest request) {
        RecordDocument record = recordRepo.findById(id).orElseThrow(
                ()-> new ResourceNotFoundException("Record Not Found"));
        ownershipGuard.requireOwnedForm(record.getFormId());
        assertVisible(record);

        List<FormField> fields = formMetaCache.getFields(record.getFormId());

        // Timeline: remember what the data looked like before the rewrite.
        Map<String, Object> before = record.getData() == null
                ? Map.of() : new java.util.LinkedHashMap<>(record.getData());

        Map<String , Object> validatedData = validationService.validate(request , fields);
        record.setData(validatedData);
        record.setUpdatedAt(LocalDateTime.now());

        RecordDocument saved = recordRepo.save(record);

        FormEntity form = formRepo.findById(record.getFormId())
                .orElseThrow(() ->
                        new ResourceNotFoundException("Form not found"));

        automationEngine.execute(
                AutomationTrigger.RECORD_UPDATED,
                form,
                saved
        );

        activityService.log(saved.getId(), form.getOwnerUserId(), "UPDATED",
                changedFieldsSummary(before, validatedData, fields));

        return mapper.toResponse(saved);
    }

    /**
     * Moves a record to another stage.
     *
     * <p>update() deliberately rewrites only the data map, so until now the
     * stage could be changed only by an automation's CHANGE_STAGE action —
     * there was no way to move a record by hand.
     *
     * <p>The target stage must belong to the record's own form, and
     * re-selecting the current stage is a no-op so STAGE_CHANGED automations
     * never fire on a move that did not happen.
     */
    @Override
    public RecordResponse changeStage(String id, Long stageId) {

        RecordDocument record = recordRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Record Not Found"));
        ownershipGuard.requireOwnedForm(record.getFormId());

        FormStage stage = stageRepo.findById(stageId)
                .orElseThrow(() -> new ResourceNotFoundException("Stage Not Found"));

        if (!stage.getFormId().equals(record.getFormId())) {
            throw new BadRequestException(
                    "Stage does not belong to this record's form"
            );
        }

        if (stageId.equals(record.getStageId())) {
            return mapper.toResponse(record);
        }

        assertVisible(record);

        /*
         * APPROVAL GUARDS.
         *
         * 1) FINAL LOCK — a record sitting in a final stage (Approved/Closed)
         *    is frozen. Escape hatch: the owner/system-ADMIN, or a member
         *    whose role has "records.unlock", may still revert it — so the
         *    lock binds normal members but stays admin-controllable.
         *
         * 2) ASSIGNEE GATE — when the record is assigned to someone, only
         *    that person moves it forward (owner/system-ADMIN bypass). With
         *    ASSIGN_USER automations re-assigning per approval step, this
         *    turns the pipeline into an enforced approval chain: User1 can
         *    move only while it's his, User2 only after it reaches him.
         *
         * Automation-driven CHANGE_STAGE bypasses this method by design
         * (engine writes the record directly) — system moves stay free.
         */
        if (record.getStageId() != null) {
            FormStage currentStage = stageRepo.findById(record.getStageId()).orElse(null);
            if (currentStage != null && Boolean.TRUE.equals(currentStage.getIsFinal())
                    && !permissionService.isAdmin()
                    && !permissionService.has("records.unlock")) {
                throw new BadRequestException(
                        "This record is locked in the final stage '" + currentStage.getName()
                        + "' — only an admin or a member with the 'unlock' "
                        + "permission can move it.");
            }
        }
        if (record.getAssignedTo() != null && !permissionService.isAdmin()) {
            java.util.UUID me = currentUserProvider.currentUserIdOrNull();
            if (me == null || !record.getAssignedTo().equals(me.toString())) {
                throw new BadRequestException(
                        "This record is assigned to someone else — only the assigned "
                        + "member can move its stage.");
            }
        }

        String previousStageName = record.getStageId() == null ? "—"
                : stageRepo.findById(record.getStageId()).map(FormStage::getName).orElse("—");

        record.setStageId(stageId);
        record.setUpdatedAt(LocalDateTime.now());

        RecordDocument saved = recordRepo.save(record);

        FormEntity form = formRepo.findById(record.getFormId())
                .orElseThrow(() ->
                        new ResourceNotFoundException("Form not found"));

        automationEngine.execute(
                AutomationTrigger.STAGE_CHANGED,
                form,
                saved
        );

        activityService.log(saved.getId(), form.getOwnerUserId(), "STAGE_CHANGED",
                "Stage: " + previousStageName + " \u2192 " + stage.getName());

        return mapper.toResponse(saved);
    }


    @Override
    public void delete(String id) {
        RecordDocument record = recordRepo.findById(id)
                        .orElseThrow(() -> new ResourceNotFoundException("Record not found"));
        ownershipGuard.requireOwnedForm(record.getFormId());
        assertVisible(record);

        recordRepo.delete(record);
        activityService.deleteFor(id);
    }

    @Override
    public Page<RecordResponse> search(String slug,
                                       RecordSearchRequest request) {

        FormEntity form = formRepo.findBySlug(slug)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Form not found"));
        ownershipGuard.assertOwned(form);

        List<FormField> fields = formMetaCache.getFields(form.getId());

        Page<RecordDocument> records =
                recordSearchRepo.search(
                        form.getId(),
                        request,
                        fields,
                        ownOnlyFilterOrNull()
                );

        return records.map(mapper::toResponse);
    }

    /* --------------------------------------------------- view/approval scope */

    /**
     * records.view => whole org's records; records.view.own => only records
     * assigned to me. Neither => 403. Returns my userId when scope is
     * own-only, else null (no filter).
     */
    private String ownOnlyFilterOrNull() {
        permissionService.requireAny("records.view", "records.view.own");
        if (permissionService.has("records.view")) {
            return null;
        }
        java.util.UUID me = currentUserProvider.currentUserIdOrNull();
        return me == null ? "__none__" : me.toString();
    }

    /** Own-only scope walo ke liye dusre ka record == exist hi nahi karta. */
    private void assertVisible(RecordDocument record) {
        String ownOnly = ownOnlyFilterOrNull();
        if (ownOnly != null && !ownOnly.equals(record.getAssignedTo())) {
            throw new ResourceNotFoundException("Record Not Found");
        }
    }

    /* ------------------------------------------------------ record transfer */

    @Override
    public int transfer(java.util.List<String> recordIds, String toUserId) {
        requireOrgMember(toUserId);
        int moved = 0;
        for (String recordId : recordIds == null ? java.util.List.<String>of() : recordIds) {
            RecordDocument record = recordRepo.findById(recordId).orElse(null);
            if (record == null) continue;
            // Same wall as every other record operation: form must be ours.
            ownershipGuard.requireOwnedForm(record.getFormId());
            record.setAssignedTo(toUserId);
            record.setUpdatedAt(java.time.LocalDateTime.now());
            recordRepo.save(record);
            formRepo.findById(record.getFormId()).ifPresent(f ->
                    activityService.log(record.getId(), f.getOwnerUserId(), "ASSIGNED",
                            "Assigned to " + teamService.memberDisplayName(toUserId)));
            moved++;
        }
        return moved;
    }

    @Override
    public int transferAll(String fromUserId, String toUserId) {
        requireOrgMember(toUserId);
        int moved = 0;
        for (RecordDocument record : recordRepo.findByAssignedTo(fromUserId)) {
            try {
                ownershipGuard.requireOwnedForm(record.getFormId());
            } catch (Exception notOurs) {
                continue; // some other org's record with same assignee id — skip
            }
            record.setAssignedTo(toUserId);
            record.setUpdatedAt(java.time.LocalDateTime.now());
            recordRepo.save(record);
            moved++;
        }
        return moved;
    }

    /** "Updated: Name, Phone (+2 more)" — labels of the keys whose value changed. */
    private String changedFieldsSummary(Map<String, Object> before,
                                        Map<String, Object> after,
                                        List<FormField> fields) {
        java.util.Map<String, String> labels = new java.util.HashMap<>();
        for (FormField f : fields) labels.put(f.getFieldKey(), f.getLabel());

        java.util.List<String> changed = new java.util.ArrayList<>();
        java.util.Set<String> keys = new java.util.LinkedHashSet<>(before.keySet());
        keys.addAll(after.keySet());
        for (String key : keys) {
            if (!java.util.Objects.equals(before.get(key), after.get(key))) {
                changed.add(labels.getOrDefault(key, key));
            }
        }
        if (changed.isEmpty()) return "Record saved (no field changed)";
        String head = String.join(", ", changed.subList(0, Math.min(4, changed.size())));
        int more = changed.size() - 4;
        return "Updated: " + head + (more > 0 ? " (+" + more + " more)" : "");
    }

    private void requireOrgMember(String userId) {
        if (userId == null || !teamService.isInMyOrg(userId)) {
            throw new com.xetax.crm.common.exception.BadRequestException(
                    "Target user aapki team ka member nahi hai");
        }
    }
}
