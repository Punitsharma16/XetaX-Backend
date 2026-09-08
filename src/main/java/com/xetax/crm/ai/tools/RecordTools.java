package com.xetax.crm.ai.tools;

import com.xetax.crm.team.service.RequiresPermission;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.common.exception.ResourceNotFoundException;
import com.xetax.crm.data_manager.dto.FormResponse;
import com.xetax.crm.data_manager.dto.RecordRequest;
import com.xetax.crm.data_manager.dto.RecordResponse;
import com.xetax.crm.data_manager.dto.SeacrhDto.RecordSearchRequest;
import com.xetax.crm.data_manager.service.RecordService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Spring AI READ + WRITE tools for RECORDS (the actual data rows inside a
 * form — leads, tickets, deals, whatever the form models).
 *
 * <p>Security: records belong to forms, and forms belong to users — every
 * tool first resolves the formId through FormTools' ownership gate (which
 * reads the authenticated user from the SecurityContext), and record-by-id
 * access is only allowed when the record's own formId is owned. Another
 * user's record/form behaves exactly like one that does not exist. userId
 * is never a tool parameter.
 *
 * <p>Business logic stays in RecordService (same path the REST API uses):
 * the full validation chain (unknown/required/default/type), default-stage
 * assignment and automation triggering on create.
 */
@Slf4j
@Component
public class RecordTools {

    private static final String FORM_NOT_ACCESSIBLE =
            "No form with this id exists for the current user.";
    private static final String RECORD_NOT_ACCESSIBLE =
            "No record with this id exists for the current user.";

    private final RecordService recordService;

    private final FormTools formTools;

    private final ObjectMapper objectMapper;

    public RecordTools(RecordService recordService, FormTools formTools, ObjectMapper objectMapper) {
        this.recordService = recordService;
        this.formTools = formTools;
        this.objectMapper = objectMapper;
    }

    private Map<String, Object> pageResult(String formName, Page<RecordResponse> page) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("found", true);
        result.put("formName", formName);
        result.put("totalRecords", page.getTotalElements());
        result.put("page", page.getNumber());
        result.put("pageSize", page.getSize());
        result.put("records", page.getContent());
        return result;
    }

    @Tool(description = """
            READ-ONLY. List the records (data rows) of one form of the
            currently authenticated user, newest first. Arguments: formId —
            numeric id of the user's form (resolve via findMyFormByName /
            getMyForms first); page — 0-based page number (default 0); size —
            records per page (default 10, max 50). The result includes
            totalRecords, so use it to answer "how many" questions. Returns
            found=false when the form does not exist for this user.
            """)
    public Map<String, Object> getRecords(
            @ToolParam(description = "Numeric id of the form") Long formId,
            @ToolParam(required = false, description = "0-based page number, default 0") Integer page,
            @ToolParam(required = false, description = "Records per page, default 10, max 50") Integer size) {
        Optional<FormResponse> form = formTools.ownedFormOf(formId);
        if (form.isEmpty()) {
            return Map.of("found", false, "message", FORM_NOT_ACCESSIBLE);
        }
        try {
            int p = page == null || page < 0 ? 0 : page;
            int s = size == null || size < 1 ? 10 : Math.min(size, 50);
            Page<RecordResponse> records =
                    recordService.getAll(form.get().getSlug(), p, s, "createdAt", "DESC");
            return pageResult(form.get().getName(), records);
        } catch (Exception e) {
            log.warn("getRecords tool failed: {}", e.getMessage());
            return Map.of("found", false, "message",
                    "Records are temporarily unavailable. Ask the user to try again later.");
        }
    }

    @Tool(description = """
            READ-ONLY. Search the records of one form of the currently
            authenticated user with a free-text query (matches text-like
            fields, case-insensitive). Arguments: formId — numeric id of the
            user's form; searchText — what to look for, e.g. a name, phone
            fragment or company. Returns found=false when the form does not
            exist for this user.
            """)
    public Map<String, Object> searchRecords(
            @ToolParam(description = "Numeric id of the form") Long formId,
            @ToolParam(description = "Free-text search query") String searchText) {
        Optional<FormResponse> form = formTools.ownedFormOf(formId);
        if (form.isEmpty()) {
            return Map.of("found", false, "message", FORM_NOT_ACCESSIBLE);
        }
        if (searchText == null || searchText.isBlank()) {
            return Map.of("found", false, "message", "A search text is required.");
        }
        try {
            RecordSearchRequest request = new RecordSearchRequest();
            request.setSearch(searchText.trim());
            request.setPage(0);
            request.setSize(20);
            Page<RecordResponse> records = recordService.search(form.get().getSlug(), request);
            return pageResult(form.get().getName(), records);
        } catch (Exception e) {
            log.warn("searchRecords tool failed: {}", e.getMessage());
            return Map.of("found", false, "message",
                    "Record search is temporarily unavailable. Ask the user to try again later.");
        }
    }

    @Tool(description = """
            READ-ONLY. Get ONE record of the currently authenticated user by
            its record id (the id string shown in getRecords/searchRecords
            results). Returns found=false when the record does not exist for
            this user.
            """)
    public Map<String, Object> getRecordDetails(
            @ToolParam(description = "Id of the record") String recordId) {
        if (recordId == null || recordId.isBlank()) {
            return Map.of("found", false, "message", RECORD_NOT_ACCESSIBLE);
        }
        try {
            RecordResponse record = recordService.getById(recordId.trim());
            // Ownership: the record's own form must belong to the caller.
            Optional<FormResponse> form = formTools.ownedFormOf(record.getFormId());
            if (form.isEmpty()) {
                return Map.of("found", false, "message", RECORD_NOT_ACCESSIBLE);
            }
            return Map.of("found", true, "formName", form.get().getName(), "record", record);
        } catch (ResourceNotFoundException e) {
            return Map.of("found", false, "message", RECORD_NOT_ACCESSIBLE);
        } catch (Exception e) {
            log.warn("getRecordDetails tool failed: {}", e.getMessage());
            return Map.of("found", false, "message",
                    "Record data is temporarily unavailable. Ask the user to try again later.");
        }
    }

    @Tool(description = """
            WRITE. Update an existing record of the currently authenticated
            CRM user. Use ONLY when the user explicitly asks to change a
            record's values. Arguments: recordId — the record's id (resolve
            via getRecords/searchRecords); dataJson — a JSON OBJECT string
            with ONLY the fieldKey -> value pairs to change (keys from
            getFormFields); all other values stay as they are. The CRM
            validates the merged data — relay any validation message.
            Returns updated=true with the record, or updated=false with a
            reason.
            """)
    @RequiresPermission("records.edit")
    public Map<String, Object> updateRecord(
            @ToolParam(description = "Id of the record to update") String recordId,
            @ToolParam(description = "JSON object string of the fieldKey -> value pairs to change") String dataJson) {
        if (recordId == null || recordId.isBlank()) {
            return Map.of("updated", false, "reason", RECORD_NOT_ACCESSIBLE);
        }
        try {
            RecordResponse record = recordService.getById(recordId.trim());
            Optional<FormResponse> form = formTools.ownedFormOf(record.getFormId());
            if (form.isEmpty()) {
                return Map.of("updated", false, "reason", RECORD_NOT_ACCESSIBLE);
            }
            Map<String, Object> changes;
            try {
                changes = objectMapper.readValue(dataJson == null ? "" : dataJson,
                        new TypeReference<Map<String, Object>>() {
                        });
            } catch (Exception e) {
                return Map.of("updated", false, "reason",
                        "dataJson must be a valid JSON object of fieldKey -> value pairs.");
            }
            if (changes.isEmpty()) {
                return Map.of("updated", false, "reason", "Nothing to change was provided.");
            }
            /* The service's update replaces the whole data map, so the
               changes are merged over the record's current values. */
            Map<String, Object> merged =
                    new LinkedHashMap<>(record.getData() != null ? record.getData() : Map.of());
            merged.putAll(changes);
            RecordRequest request = new RecordRequest();
            request.setData(merged);
            RecordResponse updated = recordService.update(record.getId(), request);
            return Map.of("updated", true, "formName", form.get().getName(), "record", updated);
        } catch (ResourceNotFoundException e) {
            return Map.of("updated", false, "reason", RECORD_NOT_ACCESSIBLE);
        } catch (BadRequestException e) {
            return Map.of("updated", false, "reason", e.getMessage());
        } catch (Exception e) {
            log.warn("updateRecord tool failed: {}", e.getMessage());
            return Map.of("updated", false, "reason",
                    "The record could not be updated right now. Ask the user to try again later.");
        }
    }

    @Tool(description = """
            WRITE. Move a record of the currently authenticated CRM user to
            another pipeline stage of ITS OWN form. Use ONLY when the user
            explicitly asks to move/change a record's stage (e.g. "mark this
            deal as won"). Arguments: recordId — the record's id (from
            getRecords/searchRecords); stageId — the destination stage's
            numeric id (resolve via getFormStages of the record's form).
            Stage-change automations fire as usual. Returns moved=true with
            the record, or moved=false with a reason.
            """)
    @RequiresPermission("records.edit")
    public Map<String, Object> moveRecordStage(
            @ToolParam(description = "Id of the record to move") String recordId,
            @ToolParam(description = "Numeric id of the destination stage") Long stageId) {
        if (recordId == null || recordId.isBlank() || stageId == null) {
            return Map.of("moved", false, "reason", RECORD_NOT_ACCESSIBLE);
        }
        try {
            RecordResponse record = recordService.getById(recordId.trim());
            Optional<FormResponse> form = formTools.ownedFormOf(record.getFormId());
            if (form.isEmpty()) {
                return Map.of("moved", false, "reason", RECORD_NOT_ACCESSIBLE);
            }
            /* changeStage validates that the stage belongs to the record's
               form and fires STAGE_CHANGED automations. */
            RecordResponse moved = recordService.changeStage(record.getId(), stageId);
            return Map.of("moved", true, "formName", form.get().getName(), "record", moved);
        } catch (ResourceNotFoundException e) {
            return Map.of("moved", false, "reason", RECORD_NOT_ACCESSIBLE);
        } catch (BadRequestException e) {
            return Map.of("moved", false, "reason", e.getMessage());
        } catch (Exception e) {
            log.warn("moveRecordStage tool failed: {}", e.getMessage());
            return Map.of("moved", false, "reason",
                    "The record could not be moved right now. Ask the user to try again later.");
        }
    }

    @Tool(description = """
            WRITE. Create a NEW record (data row) in one form of the
            currently authenticated CRM user (ownership comes from the
            logged-in session — never pass or ask for a user id). Use ONLY
            when the user explicitly asks to add/create a record (a lead, a
            ticket, an entry...). Arguments: formId — numeric id of the
            user's form; dataJson — a JSON OBJECT string whose keys are the
            form's fieldKeys (get them from getFormFields first) and values
            are the record's values, e.g.
            {"customer_name":"Ravi","priority":"High"}. The CRM validates
            required fields and types and rejects unknown keys — relay any
            validation message to the user. The new record lands on the
            form's default stage and normal automations fire. Returns
            created=true with the record, or created=false with a reason.
            """)
    @RequiresPermission("records.create")
    public Map<String, Object> createRecord(
            @ToolParam(description = "Numeric id of the form") Long formId,
            @ToolParam(description = "JSON object string: fieldKey -> value, keys from getFormFields") String dataJson) {
        Optional<FormResponse> form = formTools.ownedFormOf(formId);
        if (form.isEmpty()) {
            return Map.of("created", false, "reason", FORM_NOT_ACCESSIBLE);
        }
        Map<String, Object> data;
        try {
            data = objectMapper.readValue(dataJson == null ? "" : dataJson,
                    new TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception e) {
            return Map.of("created", false, "reason",
                    "dataJson must be a valid JSON object of fieldKey -> value pairs.");
        }
        if (data.isEmpty()) {
            return Map.of("created", false, "reason", "The record data cannot be empty.");
        }
        try {
            RecordRequest request = new RecordRequest();
            request.setData(data);
            /*
             * RecordService.create() is the same path the REST API and the
             * public webhook use: full validation chain (unknown key,
             * required, default value, type), default-stage assignment and
             * RECORD_CREATED automations. Ownership was already verified
             * above.
             */
            RecordResponse created = recordService.create(form.get().getSlug(), request);
            return Map.of("created", true, "formName", form.get().getName(), "record", created);
        } catch (BadRequestException | ResourceNotFoundException e) {
            return Map.of("created", false, "reason", e.getMessage());
        } catch (Exception e) {
            log.warn("createRecord tool failed: {}", e.getMessage());
            return Map.of("created", false, "reason",
                    "The record could not be created right now. Ask the user to try again later.");
        }
    }
}
