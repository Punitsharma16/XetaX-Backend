package com.xetax.crm.automation.controller;

import com.xetax.crm.team.service.RequiresPermission;


import com.xetax.crm.automation.dto.AutomationRequest;
import com.xetax.crm.automation.dto.AutomationResponse;
import com.xetax.crm.automation.service.AutomationService;
import com.xetax.crm.common.responce.ApiResponse;
import com.xetax.crm.common.responce.ResponseUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/automations")
@RequiredArgsConstructor
public class AutomationController {

    private final AutomationService automationService;

    @PostMapping
    @RequiresPermission("automations.manage")
    public ResponseEntity<ApiResponse<AutomationResponse>> create(
            @RequestBody @Valid AutomationRequest request) {

        return ResponseEntity.ok(
                ResponseUtil.success(
                        "Success",
                        automationService.create(request)
                )
        );
    }

    @PutMapping("/{id}")
    @RequiresPermission("automations.manage")
    public ResponseEntity<ApiResponse<AutomationResponse>> update(
            @PathVariable Long id,
            @RequestBody @Valid AutomationRequest request) {

        return ResponseEntity.ok(
                ResponseUtil.success(
                        "Updated Successfully",
                        automationService.update(id, request)
                )
        );
    }

    @GetMapping("/{id}")
    @RequiresPermission("automations.view")
    public ResponseEntity<ApiResponse<AutomationResponse>> getById(
            @PathVariable Long id) {

        return ResponseEntity.ok(
                ResponseUtil.success(
                        "Success",
                        automationService.getById(id)
                )
        );
    }

    @GetMapping
    @RequiresPermission("automations.view")
    public ResponseEntity<ApiResponse<List<AutomationResponse>>> getAll() {

        return ResponseEntity.ok(
                ResponseUtil.success(
                        "Success",
                        automationService.getAll()
                )
        );
    }

    @DeleteMapping("/{id}")
    @RequiresPermission("automations.manage")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable Long id) {

        automationService.delete(id);

        return ResponseEntity.ok(
                ResponseUtil.success(
                        "Automation deleted successfully"
                )
        );
    }
}
