package com.xetax.crm.data_manager.service;

import com.xetax.crm.common.exception.ResourceNotFoundException;
import com.xetax.crm.data_manager.documents.RecordDocument;
import com.xetax.crm.data_manager.dto.RecordResponse;
import com.xetax.crm.data_manager.entity.FormEntity;
import com.xetax.crm.data_manager.entity.FormField;
import com.xetax.crm.data_manager.entity.FormStage;
import com.xetax.crm.data_manager.repository.FormRepo;
import com.xetax.crm.team.service.PermissionService;
import com.xetax.crm.team.service.TeamService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CSV export of one form's records, and the duplicate-value lookup behind the
 * "this phone already exists" warning. Both lean on RecordService.getAll /
 * the same ownership + view.own scoping, so a member never exports or matches
 * records they cannot see.
 */
@Service
@RequiredArgsConstructor
public class RecordExportService {

    /** Exports are capped, not paged — a launch-size org fits comfortably. */
    private static final int MAX_EXPORT_ROWS = 5000;

    private final RecordService recordService;
    private final FormRepo formRepo;
    private final FormMetaCache formMetaCache;
    private final OwnershipGuard ownershipGuard;
    private final PermissionService permissionService;
    private final TeamService teamService;
    private final MongoTemplate mongoTemplate;
    private final com.xetax.crm.auth.security.CurrentUserProvider currentUserProvider;

    public String exportCsv(String slug) {
        FormEntity form = formRepo.findBySlug(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Form Not Found"));
        ownershipGuard.assertOwned(form);

        List<FormField> fields = formMetaCache.getFields(form.getId());
        Map<Long, String> stageNames = new HashMap<>();
        for (FormStage stage : formMetaCache.getStages(form.getId())) {
            stageNames.put(stage.getId(), stage.getName());
        }
        // Scoping rides on getAll — the same page the records list shows.
        List<RecordResponse> records = recordService
                .getAll(slug, 0, MAX_EXPORT_ROWS, "createdAt", "DESC").getContent();

        StringBuilder sb = new StringBuilder();
        for (FormField field : fields) sb.append(csv(field.getLabel())).append(',');
        sb.append("Stage,Assigned To,Created At\n");

        Map<String, String> assigneeNames = new HashMap<>();
        for (RecordResponse record : records) {
            Map<String, Object> data = record.getData() == null ? Map.of() : record.getData();
            for (FormField field : fields) {
                Object value = data.get(field.getFieldKey());
                sb.append(csv(value == null ? "" : String.valueOf(value))).append(',');
            }
            sb.append(csv(stageNames.getOrDefault(record.getStageId(), ""))).append(',');
            String assignee = record.getAssignedTo() == null ? ""
                    : assigneeNames.computeIfAbsent(record.getAssignedTo(), teamService::memberDisplayName);
            sb.append(csv(assignee)).append(',');
            sb.append(record.getCreatedAt() == null ? "" : record.getCreatedAt().toString()).append('\n');
        }
        return sb.toString();
    }

    /**
     * Records of this form whose given field already holds the given value —
     * feeds the non-blocking duplicate warning on the create editor.
     */
    public List<Map<String, Object>> duplicates(String slug, String fieldKey, String value) {
        FormEntity form = formRepo.findBySlug(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Form Not Found"));
        ownershipGuard.assertOwned(form);
        if (fieldKey == null || fieldKey.isBlank() || value == null || value.isBlank()) return List.of();

        // The field must be real — stops probing arbitrary Mongo paths.
        FormField field = formMetaCache.getFields(form.getId()).stream()
                .filter(f -> f.getFieldKey().equals(fieldKey)).findFirst().orElse(null);
        if (field == null) return List.of();

        List<Criteria> criteria = new ArrayList<>();
        criteria.add(Criteria.where("formId").is(form.getId()));
        // NUMBER fields store numbers — match either representation.
        String trimmed = value.trim();
        try {
            criteria.add(new Criteria().orOperator(
                    Criteria.where("data." + fieldKey).is(trimmed),
                    Criteria.where("data." + fieldKey).is(Double.parseDouble(trimmed)),
                    Criteria.where("data." + fieldKey).is(Long.parseLong(trimmed))));
        } catch (NumberFormatException notNumeric) {
            criteria.add(Criteria.where("data." + fieldKey).is(trimmed));
        }
        // view.own members only ever match their own records.
        permissionService.requireAny("records.view", "records.view.own");
        if (!permissionService.has("records.view")) {
            java.util.UUID me = currentUserProvider.currentUserIdOrNull();
            criteria.add(Criteria.where("assignedTo").is(me == null ? "__none__" : me.toString()));
        }

        Query query = new Query(new Criteria().andOperator(criteria.toArray(new Criteria[0]))).limit(5);
        List<Map<String, Object>> out = new ArrayList<>();
        for (RecordDocument record : mongoTemplate.find(query, RecordDocument.class)) {
            Map<String, Object> data = record.getData() == null ? Map.of() : record.getData();
            String title = data.values().stream()
                    .filter(v -> v instanceof String str && !str.isBlank())
                    .map(Object::toString).findFirst().orElse(record.getId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", record.getId());
            row.put("title", title);
            row.put("createdAt", record.getCreatedAt());
            out.add(row);
        }
        return out;
    }

    private String csv(String v) {
        if (v == null) return "";
        return v.contains(",") || v.contains("\"") || v.contains("\n")
                ? '"' + v.replace("\"", "\"\"") + '"' : v;
    }
}
