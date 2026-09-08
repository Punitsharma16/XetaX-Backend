package com.xetax.crm.data_manager.controller;

import com.xetax.crm.team.service.RequiresPermission;

import com.xetax.crm.common.responce.ApiResponse;
import com.xetax.crm.common.responce.ResponseUtil;
import com.xetax.crm.data_manager.dto.StageRequest;
import com.xetax.crm.data_manager.dto.StageResponse;
import com.xetax.crm.data_manager.service.StageService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/stage")
public class StageController {

    private final StageService stageService;

    public StageController(StageService stageService) {
        this.stageService = stageService;
    }

    @PostMapping("/forms/{formId}/stages")
    @RequiresPermission("forms.manage")
    public ResponseEntity<ApiResponse<StageResponse>> create(
            @PathVariable Long formId,
            @Valid @RequestBody StageRequest request){

        return ResponseEntity.ok(
                ResponseUtil.success(
                        "Stage Created Successfully",
                        stageService.create(formId, request)
                )
        );
    }

    @GetMapping("/forms/{formId}/stages")
    @RequiresPermission("forms.view")
    public ResponseEntity<ApiResponse<List<StageResponse>>> getAll(
            @PathVariable Long formId){

        return ResponseEntity.ok(
                ResponseUtil.success(
                        "Success",
                       stageService.getAll(formId)
                )
        );
    }

    @PutMapping("/stages/{id}")
    @RequiresPermission("forms.manage")
    public ResponseEntity<ApiResponse<StageResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody StageRequest request){

        return ResponseEntity.ok(
                ResponseUtil.success(
                        "Updated Successfully",
                        stageService.update(id, request)
                )
        );
    }

    @DeleteMapping("/stages/{id}")
    @RequiresPermission("forms.manage")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable Long id){

        stageService.delete(id);

        return ResponseEntity.ok(
                ResponseUtil.success("Deleted Successfully")
        );
    }
}
