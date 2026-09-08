package com.xetax.crm.integration.controller;

import com.xetax.crm.team.service.RequiresPermission;

import com.xetax.crm.common.responce.ApiResponse;
import com.xetax.crm.common.responce.ResponseUtil;
import com.xetax.crm.integration.dto.IntegrationRequest;
import com.xetax.crm.integration.dto.IntegrationResponse;
import com.xetax.crm.integration.dto.MappingItemResponse;
import com.xetax.crm.integration.dto.SaveMappingRequest;
import com.xetax.crm.integration.service.IntegrationMappingService;
import com.xetax.crm.integration.service.IntegrationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/integrations")
@RequiredArgsConstructor
public class IntegrationController {

    private final IntegrationService integrationService;

    private final IntegrationMappingService mappingService;

    @PostMapping
    @RequiresPermission("integrations.manage")
    public ResponseEntity<ApiResponse<IntegrationResponse>> create(
            @Valid @RequestBody IntegrationRequest request) {

        return ResponseEntity.ok(
                ResponseUtil.success(
                        "Integration created successfully",
                        integrationService.create(request)
                )
        );
    }

    @PutMapping("/{id}")
    @RequiresPermission("integrations.manage")
    public ResponseEntity<ApiResponse<IntegrationResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody IntegrationRequest request) {

        return ResponseEntity.ok(
                ResponseUtil.success(
                        "Integration updated successfully",
                        integrationService.update(id, request)
                )
        );
    }

    @GetMapping("/{id}")
    @RequiresPermission("integrations.view")
    public ResponseEntity<ApiResponse<IntegrationResponse>> getById(
            @PathVariable Long id) {

        return ResponseEntity.ok(
                ResponseUtil.success(
                        "Success",
                        integrationService.getById(id)
                )
        );
    }

    @GetMapping
    @RequiresPermission("integrations.view")
    public ResponseEntity<ApiResponse<List<IntegrationResponse>>> getAll() {

        return ResponseEntity.ok(
                ResponseUtil.success(
                        "Success",
                        integrationService.getAll()
                )
        );
    }

    @DeleteMapping("/{id}")
    @RequiresPermission("integrations.manage")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable Long id) {

        integrationService.delete(id);

        return ResponseEntity.ok(
                ResponseUtil.success(
                        "Integration deleted successfully"
                )
        );
    }

    @PostMapping("/{id}/mapping")
    @RequiresPermission("integrations.manage")
    public ResponseEntity<ApiResponse<Void>> saveMappings(
            @PathVariable Long id,
            @RequestBody @Valid SaveMappingRequest request){

        mappingService.saveMappings(id, request);
        return ResponseEntity.ok(
                ResponseUtil.success("Mapping saved successfully")
        );

    }

    /*
     * The UI's webhook tester builds its sample payload from the stored source
     * keys — without this read-back it could only guess with field keys.
     */
    @GetMapping("/{id}/mapping")
    public ResponseEntity<ApiResponse<List<MappingItemResponse>>> getMappings(
            @PathVariable Long id){

        return ResponseEntity.ok(
                ResponseUtil.success(
                        "Success",
                        mappingService.getMappings(id)
                )
        );
    }

}
