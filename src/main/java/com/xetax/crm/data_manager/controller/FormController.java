package com.xetax.crm.data_manager.controller;

import com.xetax.crm.team.service.RequiresPermission;

import com.xetax.crm.common.responce.ApiResponse;
import com.xetax.crm.common.responce.ResponseUtil;
import com.xetax.crm.data_manager.dto.FormRequest;
import com.xetax.crm.data_manager.dto.FormResponse;
import com.xetax.crm.data_manager.service.FormService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/forms")
@RequiredArgsConstructor
public class FormController {

    private final FormService formService;
    private final com.xetax.crm.team.service.PermissionService permissionService;

    @PostMapping
    @RequiresPermission("forms.manage")
    public ResponseEntity<ApiResponse<FormResponse>> create(
            @Valid @RequestBody FormRequest request){

        return ResponseEntity.ok(
                ResponseUtil.success(
                        "Form Created Successfully",
                        formService.create(request)
                )
        );
    }

    /**
     * Anyone who may read records may read the forms they belong to: the
     * records picker lists forms, and a record page needs its form's fields to
     * render at all. Without this a sales agent saw an empty Records page.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<FormResponse>>> getAll(){
        permissionService.requireAny("forms.view", "records.view", "records.view.own");

        return ResponseEntity.ok(
                ResponseUtil.success(
                        "Success",
                        formService.getAll()
                )
        );
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<FormResponse>> getById(
            @PathVariable Long id){
        permissionService.requireAny("forms.view", "records.view", "records.view.own");

        return ResponseEntity.ok(
                ResponseUtil.success(
                        "Success",
                        formService.getById(id)
                )
        );
    }

    @PutMapping("/{id}")
    @RequiresPermission("forms.manage")
    public ResponseEntity<ApiResponse<FormResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody FormRequest request){

        return ResponseEntity.ok(
                ResponseUtil.success(
                        "Updated Successfully",
                        formService.update(id,request)
                )
        );
    }

    @DeleteMapping("/{id}")
    @RequiresPermission("forms.manage")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable Long id){

        formService.delete(id);

        return ResponseEntity.ok(
                ResponseUtil.success("Deleted Successfully")
        );
    }

}
