package com.xetax.crm.emailcampaign.controller;

import com.xetax.crm.common.responce.ApiResponse;
import com.xetax.crm.common.responce.ResponseUtil;
import com.xetax.crm.emailcampaign.dto.EmailTemplateRequest;
import com.xetax.crm.emailcampaign.dto.EmailTemplateResponse;
import com.xetax.crm.emailcampaign.service.EmailTemplateService;
import com.xetax.crm.team.service.RequiresPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Saved subject + body pairs the campaign wizard can start from.
 *
 * <p>Shares the email.campaigns permission: a template is only ever useful to
 * someone who can also send a campaign.
 */
@RestController
@RequestMapping("/api/email/templates")
@RequiredArgsConstructor
public class EmailTemplateController {

    private final EmailTemplateService templateService;

    @PostMapping
    @RequiresPermission("email.campaigns")
    public ApiResponse<EmailTemplateResponse> create(@RequestBody EmailTemplateRequest request) {
        return ResponseUtil.success("Template saved", templateService.create(request));
    }

    @GetMapping
    @RequiresPermission("email.campaigns")
    public ApiResponse<List<EmailTemplateResponse>> list() {
        return ResponseUtil.success("Templates", templateService.list());
    }

    @GetMapping("/{id}")
    @RequiresPermission("email.campaigns")
    public ApiResponse<EmailTemplateResponse> get(@PathVariable Long id) {
        return ResponseUtil.success("Template", templateService.get(id));
    }

    @PutMapping("/{id}")
    @RequiresPermission("email.campaigns")
    public ApiResponse<EmailTemplateResponse> update(@PathVariable Long id,
                                                     @RequestBody EmailTemplateRequest request) {
        return ResponseUtil.success("Template updated", templateService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission("email.campaigns")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        templateService.delete(id);
        return ResponseUtil.success("Template deleted");
    }
}
