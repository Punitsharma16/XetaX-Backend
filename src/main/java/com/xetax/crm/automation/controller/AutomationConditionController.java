package com.xetax.crm.automation.controller;

import com.xetax.crm.team.service.RequiresPermission;


import com.xetax.crm.automation.entity.AutomationConditionRequest;
import com.xetax.crm.automation.entity.AutomationConditionResponse;
import com.xetax.crm.automation.service.AutomationConditionService;
import com.xetax.crm.common.responce.ApiResponse;
import com.xetax.crm.common.responce.ResponseUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/automations/{automationId}/conditions")
@RequiredArgsConstructor
public class AutomationConditionController {

    private final AutomationConditionService automationConditionService;

    @PostMapping
    @RequiresPermission("automations.manage")
    public ResponseEntity<ApiResponse<List<AutomationConditionResponse>>> saveConditions(
            @PathVariable Long automationId,
            @RequestBody @Valid List<AutomationConditionRequest> requests) {

        return ResponseEntity.ok(
                ResponseUtil.success(
                        "Success",
                        automationConditionService.saveConditions(
                                automationId,
                                requests
                        )
                )
        );
    }

    @GetMapping
    @RequiresPermission("automations.view")
    public ResponseEntity<ApiResponse<List<AutomationConditionResponse>>> getConditions(
            @PathVariable Long automationId) {

        return ResponseEntity.ok(
                ResponseUtil.success(
                        "Success",
                        automationConditionService.getConditions(
                                automationId
                        )
                )
        );
    }
}
