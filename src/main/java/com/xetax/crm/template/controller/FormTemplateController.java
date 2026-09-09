package com.xetax.crm.template.controller;

import com.xetax.crm.common.responce.ApiResponse;
import com.xetax.crm.common.responce.ResponseUtil;
import com.xetax.crm.team.service.RequiresPermission;
import com.xetax.crm.template.dto.PackView;
import com.xetax.crm.template.service.FormTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Vertical packs: built-in catalog + the workspace's own packs, one-click
 * install, export/import, and the WhatsApp template drafts packs ship.
 */
@RestController
@RequestMapping("/api/templates")
@RequiredArgsConstructor
public class FormTemplateController {

    private final FormTemplateService templateService;

    /** Full preview data — the gallery shows exactly what will be created. */
    @GetMapping
    @RequiresPermission("forms.view")
    public ApiResponse<List<PackView>> catalog() {
        return ResponseUtil.success("Templates", templateService.catalog());
    }

    /** body: {name?, includeAgent?, includePlaybook?, includeAutomations?, includeWhatsapp?} */
    @PostMapping("/{key}/apply")
    @RequiresPermission("forms.manage")
    public ApiResponse<Map<String, Object>> apply(@PathVariable String key,
                                                  @RequestBody(required = false) FormTemplateService.ApplyOptions body) {
        return ResponseUtil.success("Template applied", templateService.apply(key, body));
    }

    /** Save one of my forms as a reusable pack. body: {name?, key?} */
    @PostMapping("/export/{formId}")
    @RequiresPermission("forms.manage")
    public ApiResponse<PackView> export(@PathVariable Long formId, @RequestBody(required = false) Map<String, String> body) {
        return ResponseUtil.success("Pack saved", templateService.exportForm(formId,
                body == null ? null : body.get("name"), body == null ? null : body.get("key")));
    }

    /** Paste a pack JSON. body: raw pack JSON. */
    @PostMapping("/import")
    @RequiresPermission("forms.manage")
    public ApiResponse<PackView> importPack(@RequestBody String json) {
        return ResponseUtil.success("Pack imported", templateService.importPack(json));
    }

    @GetMapping(value = "/custom/{id}/json", produces = "application/json")
    @RequiresPermission("forms.view")
    public String customJson(@PathVariable Long id) {
        return templateService.customJson(id);
    }

    @DeleteMapping("/custom/{id}")
    @RequiresPermission("forms.manage")
    public ApiResponse<Void> deleteCustom(@PathVariable Long id) {
        templateService.deleteCustom(id);
        return ResponseUtil.success("Pack deleted", null);
    }

    /* ------------------------------------------------ WhatsApp drafts */

    @GetMapping("/drafts")
    @RequiresPermission("whatsapp.view")
    public ApiResponse<List<Map<String, Object>>> drafts() {
        return ResponseUtil.success("Drafts", templateService.drafts());
    }

    @PostMapping("/drafts/{id}/submit")
    @RequiresPermission("whatsapp.manage")
    public ApiResponse<Map<String, Object>> submitDraft(@PathVariable Long id) {
        return ResponseUtil.success("Template submitted for approval", templateService.submitDraft(id));
    }

    @DeleteMapping("/drafts/{id}")
    @RequiresPermission("whatsapp.manage")
    public ApiResponse<Void> deleteDraft(@PathVariable Long id) {
        templateService.deleteDraft(id);
        return ResponseUtil.success("Draft deleted", null);
    }
}
