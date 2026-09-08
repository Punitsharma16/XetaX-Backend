package com.xetax.crm.data_manager.controller;

import com.xetax.crm.team.service.RequiresPermission;


import com.xetax.crm.common.responce.ApiResponse;
import com.xetax.crm.common.responce.ResponseUtil;
import com.xetax.crm.data_manager.dto.FieldRequest;
import com.xetax.crm.data_manager.dto.FieldResponse;
import com.xetax.crm.data_manager.service.FieldService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/*
 * NOTE: @RestController("...") only names the bean — it does not map a path.
 * Without a class-level @RequestMapping the handlers below were published at
 * the application root (POST /5, GET /5, PUT /3 ...), which collides with other
 * controllers. The mapping below restores the intended /api/fields namespace.
 */
@RestController
@RequestMapping("/api/fields")
public class FormFieldController {

    private final FieldService fieldService;

    public FormFieldController(FieldService fieldService) {
        this.fieldService = fieldService;
    }

    @PostMapping("{formId}")
    @RequiresPermission("forms.manage")
    public ResponseEntity<ApiResponse<FieldResponse>> createField(@PathVariable long formId, @RequestBody FieldRequest request){
        return ResponseEntity.ok(
                ResponseUtil.success("Form Created Successfully" , fieldService.create(formId , request))
        );
    }

    @GetMapping("{formId}")
    @RequiresPermission("forms.view")
    public ResponseEntity<ApiResponse<List<FieldResponse>>> getAll(@PathVariable long formId){

        return ResponseEntity.ok(
                ResponseUtil.success(
                        "Success",
                        fieldService.getAll(formId)
                )
        );
    }

    @PutMapping("/{id}")
    @RequiresPermission("forms.manage")
    public ResponseEntity<ApiResponse<FieldResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody FieldRequest request){

        return ResponseEntity.ok(
                ResponseUtil.success(
                        "Updated Successfully",
                        fieldService.update(id,request)
                )
        );
    }


    @DeleteMapping("/{id}")
    @RequiresPermission("forms.manage")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable Long id){

        fieldService.delete(id);

        return ResponseEntity.ok(
                ResponseUtil.success("Deleted Successfully")
        );
    }
}
