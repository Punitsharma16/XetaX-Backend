package com.xetax.crm.data_manager.controller;

import com.xetax.crm.common.responce.ApiResponse;
import com.xetax.crm.team.service.RequiresPermission;
import com.xetax.crm.common.responce.ResponseUtil;
import com.xetax.crm.data_manager.dto.RecordRequest;
import com.xetax.crm.data_manager.dto.RecordResponse;
import com.xetax.crm.data_manager.dto.SeacrhDto.RecordSearchRequest;
import com.xetax.crm.data_manager.service.RecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("api/record")
@RequiredArgsConstructor
public class RecordController {

    private final RecordService recordService;

    private final com.xetax.crm.data_manager.service.RecordExportService recordExportService;

    private final com.xetax.crm.data_manager.service.RecordBulkUploadService recordBulkUploadService;



    @PostMapping("/{slug}")
    @RequiresPermission("records.create")
    public ResponseEntity<ApiResponse<RecordResponse>> create(@PathVariable String slug, @RequestBody RecordRequest request) {
        return ResponseEntity.ok(
                ResponseUtil.success("Record Created Successfully", recordService.create(slug, request))
        );
    }


    @GetMapping("/{slug}/all")
    public ResponseEntity<ApiResponse<Page<RecordResponse>>> getAll(@PathVariable String slug, @RequestParam(defaultValue = "0")
                                                                    int page, @RequestParam(defaultValue = "20")
                                                                    int size, @RequestParam(defaultValue = "createdAt") String sort,
                                                                          @RequestParam(defaultValue = "DESC") String direction) {
        return ResponseEntity.ok(
                ResponseUtil.success(
                        "Success",
                        recordService.getAll(slug, page, size, sort, direction)
                )
        );
    }

    /*
     * Kept after "/{slug}/all" so the more specific route wins; the UI's record
     * detail page loads a single record by its Mongo id on refresh.
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<RecordResponse>> getById(@PathVariable String id) {
        return ResponseEntity.ok(
                ResponseUtil.success(
                        "Success",
                        recordService.getById(id)
                )
        );
    }

    @PutMapping("/{id}/update")
    @RequiresPermission("records.edit")
    public ResponseEntity<ApiResponse<RecordResponse>> update(@PathVariable String id, @RequestBody RecordRequest request) {

        System.out.println("The Request from update lead is -> " + id);
        return ResponseEntity.ok(
                ResponseUtil.success(
                        "Record Updated Successfully",
                        recordService.update(id, request)
                )
        );
    }

    /**
     * Moves a record to another stage of its own form and fires any
     * STAGE_CHANGED automations. Kept separate from update() because that
     * endpoint rewrites the data map and leaves the stage untouched.
     */
    @PutMapping("/{id}/stage/{stageId}")
    @RequiresPermission("records.edit")
    public ResponseEntity<ApiResponse<RecordResponse>> changeStage(
            @PathVariable String id,
            @PathVariable Long stageId) {

        return ResponseEntity.ok(
                ResponseUtil.success(
                        "Stage Updated Successfully",
                        recordService.changeStage(id, stageId)
                )
        );
    }

    @DeleteMapping("/{id}/delete")
    @RequiresPermission("records.delete")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable String id) {
        recordService.delete(id);
        return ResponseEntity.ok(
                ResponseUtil.success("Deleted Successfully")
        );
    }

    /**
     * CSV bulk import — the file's header row must use the sample file's
     * column names (field labels). Always returns 200 with per-row results.
     */
    /** CSV of everything the caller can see on this form (capped at 5000 rows). */
    @GetMapping("/{slug}/export")
    public org.springframework.http.ResponseEntity<byte[]> exportCsv(@PathVariable String slug) {
        byte[] bytes = recordExportService.exportCsv(slug)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=" + slug + "-records.csv")
                .header("Content-Type", "text/csv; charset=UTF-8")
                .body(bytes);
    }

    /** Non-blocking duplicate warning for the create editor. */
    @GetMapping("/{slug}/duplicate-check")
    public ResponseEntity<ApiResponse<java.util.List<java.util.Map<String, Object>>>> duplicateCheck(
            @PathVariable String slug,
            @RequestParam String field,
            @RequestParam String value) {
        return ResponseEntity.ok(ResponseUtil.success("Duplicates",
                recordExportService.duplicates(slug, field, value)));
    }

    @PostMapping("/{slug}/bulk-upload")
    @RequiresPermission("records.create")
    public ResponseEntity<ApiResponse<com.xetax.crm.data_manager.dto.BulkUploadResult>> bulkUpload(
            @PathVariable String slug,
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        return ResponseEntity.ok(
                ResponseUtil.success("Upload processed",
                        recordBulkUploadService.upload(slug, file)));
    }

    @PostMapping("/{slug}/search")
    public ResponseEntity<ApiResponse<Page<RecordResponse>>> search(
            @PathVariable String slug,
            @RequestBody RecordSearchRequest request) {

        return ResponseEntity.ok(
                ResponseUtil.success(
                        "Records fetched successfully",
                        recordService.search(slug, request)
                )
        );
    }

    /** Bulk assign: body {"recordIds": [...], "toUserId": "uuid"}. */
    @PostMapping("/transfer")
    @RequiresPermission("records.transfer")
    public ApiResponse<java.util.Map<String, Object>> transfer(
            @RequestBody java.util.Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        java.util.List<String> recordIds = (java.util.List<String>) body.get("recordIds");
        int moved = recordService.transfer(recordIds, (String) body.get("toUserId"));
        return ResponseUtil.success("Records transferred", java.util.Map.of("moved", moved));
    }

    /** Everything one member holds -> another: {"fromUserId": "...", "toUserId": "..."}. */
    @PostMapping("/transfer-all")
    @RequiresPermission("records.transfer")
    public ApiResponse<java.util.Map<String, Object>> transferAll(
            @RequestBody java.util.Map<String, String> body) {
        int moved = recordService.transferAll(body.get("fromUserId"), body.get("toUserId"));
        return ResponseUtil.success("Records transferred", java.util.Map.of("moved", moved));
    }
}
