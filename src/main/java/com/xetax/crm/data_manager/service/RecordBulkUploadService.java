package com.xetax.crm.data_manager.service;

import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.common.util.CsvParser;
import com.xetax.crm.data_manager.dto.BulkUploadResult;
import com.xetax.crm.data_manager.dto.RecordRequest;
import com.xetax.crm.data_manager.entity.FormField;
import com.xetax.crm.data_manager.enums.FieldType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CSV bulk import of records for one form.
 *
 * <p>The header row is matched against the form's field LABELS or fieldKeys
 * (case-insensitive) — the sample file the UI generates uses labels. Every
 * data row goes through the normal {@link RecordService#create} path, so
 * ownership, the full validation chain, the default stage and automations
 * behave exactly like a record created by hand; a failing row is reported
 * with its row number and never stops the rest.
 *
 * <p>Values arrive as text and are converted to the field's type before
 * validation (numbers, booleans, multi-select lists split on ';').
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecordBulkUploadService {

    private static final int MAX_ROWS = 1000;
    private static final int MAX_REPORTED_ERRORS = 50;

    private final RecordService recordService;

    private final FormMetaCache formMetaCache;

    private final OwnershipGuard ownershipGuard;

    private final com.xetax.crm.data_manager.repository.FormRepo formRepo;

    public BulkUploadResult upload(String slug, MultipartFile file) {
        var form = formRepo.findBySlug(slug)
                .orElseThrow(() -> new com.xetax.crm.common.exception.ResourceNotFoundException("Form Not Found"));
        ownershipGuard.assertOwned(form);

        List<List<String>> rows = parseCsv(file);
        if (rows.size() < 2) {
            throw new BadRequestException("The file must have a header row and at least one data row");
        }
        if (rows.size() - 1 > MAX_ROWS) {
            throw new BadRequestException("Maximum " + MAX_ROWS + " rows per upload");
        }

        List<FormField> fields = formMetaCache.getFields(form.getId());

        // header column -> field (matched by label or fieldKey, case-insensitive)
        List<String> header = rows.get(0);
        FormField[] columns = new FormField[header.size()];
        List<String> unknown = new ArrayList<>();
        for (int c = 0; c < header.size(); c++) {
            String name = header.get(c) == null ? "" : header.get(c).trim();
            if (name.isEmpty()) continue;
            FormField match = null;
            for (FormField f : fields) {
                if (name.equalsIgnoreCase(f.getLabel()) || name.equalsIgnoreCase(f.getFieldKey())) {
                    match = f;
                    break;
                }
            }
            if (match == null) {
                unknown.add(name);
            } else {
                columns[c] = match;
            }
        }
        if (!unknown.isEmpty()) {
            throw new BadRequestException("Unknown columns (use the sample file's headers): " + String.join(", ", unknown));
        }

        BulkUploadResult result = BulkUploadResult.builder().totalRows(rows.size() - 1).build();

        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            if (row.stream().allMatch(v -> v == null || v.isBlank())) {
                result.setTotalRows(result.getTotalRows() - 1);
                continue; // fully empty line
            }
            try {
                Map<String, Object> data = new LinkedHashMap<>();
                for (int c = 0; c < columns.length && c < row.size(); c++) {
                    FormField field = columns[c];
                    String raw = row.get(c);
                    if (field == null || raw == null || raw.isBlank()) continue;
                    data.put(field.getFieldKey(), convert(field, raw.trim()));
                }
                RecordRequest request = new RecordRequest();
                request.setData(data);
                recordService.create(slug, request);
                result.setSuccessCount(result.getSuccessCount() + 1);
            } catch (Exception e) {
                result.setFailedCount(result.getFailedCount() + 1);
                if (result.getErrors().size() < MAX_REPORTED_ERRORS) {
                    result.getErrors().add(new BulkUploadResult.RowError(r,
                            e.getMessage() == null ? "Invalid row" : e.getMessage()));
                }
            }
        }
        return result;
    }

    /** CSV text → the typed value the record validators expect. */
    private Object convert(FormField field, String raw) {
        FieldType type = field.getFieldType();
        try {
            return switch (type) {
                case NUMBER -> raw.contains(".") ? (Object) Double.parseDouble(raw) : (Object) Long.parseLong(raw);
                case DECIMAL -> Double.parseDouble(raw);
                case BOOLEAN -> {
                    String v = raw.toLowerCase();
                    if (List.of("true", "yes", "1", "haan", "y").contains(v)) yield Boolean.TRUE;
                    if (List.of("false", "no", "0", "nahi", "n").contains(v)) yield Boolean.FALSE;
                    throw new BadRequestException(field.getLabel() + " must be yes/no");
                }
                case MULTI_SELECT, CHECKBOX -> {
                    List<String> values = new ArrayList<>();
                    for (String part : raw.split(";")) {
                        if (!part.isBlank()) values.add(part.trim());
                    }
                    yield values;
                }
                default -> raw;
            };
        } catch (NumberFormatException e) {
            throw new BadRequestException(field.getLabel() + " must be a number");
        }
    }

    /** Delegates to the shared parser so bulk upload and campaigns match. */
    private List<List<String>> parseCsv(MultipartFile file) {
        try {
            return CsvParser.parse(file.getInputStream());
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            log.warn("CSV parse failed: {}", e.getMessage());
            throw new BadRequestException("Could not read the file — upload the CSV generated from the sample");
        }
    }
}
