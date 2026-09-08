package com.xetax.crm.template.controller;

import com.xetax.crm.common.responce.ApiResponse;
import com.xetax.crm.common.responce.ResponseUtil;
import com.xetax.crm.team.service.RequiresPermission;
import com.xetax.crm.template.service.FormTemplateCatalog;
import com.xetax.crm.template.service.FormTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/templates")
@RequiredArgsConstructor
public class FormTemplateController {

    private final FormTemplateService templateService;

    /** Full preview data — the gallery shows exactly what will be created. */
    @GetMapping
    @RequiresPermission("forms.view")
    public ApiResponse<List<FormTemplateCatalog.Template>> catalog() {
        return ResponseUtil.success("Templates", templateService.catalog());
    }

    /** body: {"name": optional custom form name}. Creates form+fields+stages+draft automations. */
    @PostMapping("/{key}/apply")
    @RequiresPermission("forms.manage")
    public ApiResponse<Map<String, Object>> apply(@PathVariable String key,
                                                  @RequestBody(required = false) Map<String, String> body) {
        return ResponseUtil.success("Template applied",
                templateService.apply(key, body == null ? null : body.get("name")));
    }
}
