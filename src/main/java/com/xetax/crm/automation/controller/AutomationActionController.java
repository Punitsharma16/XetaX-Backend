package com.xetax.crm.automation.controller;

import com.xetax.crm.team.service.RequiresPermission;

import com.xetax.crm.automation.dto.AutomationActionRequest;
import com.xetax.crm.automation.dto.AutomationActionResponse;
import com.xetax.crm.automation.service.AutomationActionService;
import com.xetax.crm.common.responce.ApiResponse;
import com.xetax.crm.common.responce.ResponseUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/automations/{automationId}/actions")
@RequiredArgsConstructor
public class AutomationActionController {

    private final AutomationActionService automationActionService;

    @PostMapping
    @RequiresPermission("automations.manage")
    public ResponseEntity<ApiResponse<List<AutomationActionResponse>>> saveActions(
            @PathVariable Long automationId,
            @RequestBody @Valid List<AutomationActionRequest> requests) {

        return ResponseEntity.ok(
                ResponseUtil.success(
                        "Success",
                        automationActionService.saveActions(
                                automationId,
                                requests
                        )
                )
        );
    }

    @GetMapping
    @RequiresPermission("automations.view")
    public ResponseEntity<ApiResponse<List<AutomationActionResponse>>> getActions(
            @PathVariable Long automationId) {

        return ResponseEntity.ok(
                ResponseUtil.success(
                        "Success",
                        automationActionService.getActions(
                                automationId
                        )
                )
        );
    }
}
