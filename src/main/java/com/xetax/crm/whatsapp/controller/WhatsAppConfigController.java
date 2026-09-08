package com.xetax.crm.whatsapp.controller;

import com.xetax.crm.team.service.RequiresPermission;

import com.xetax.crm.common.responce.ApiResponse;
import com.xetax.crm.common.responce.ResponseUtil;
import com.xetax.crm.whatsapp.dto.ManualConnectRequest;
import com.xetax.crm.whatsapp.dto.OnboardingCompleteRequest;
import com.xetax.crm.whatsapp.dto.TemplateCreateRequest;
import com.xetax.crm.whatsapp.dto.WhatsAppConfigResponse;
import com.xetax.crm.whatsapp.dto.WhatsAppTemplateResponse;
import com.xetax.crm.whatsapp.service.WhatsAppConfigService;
import com.xetax.crm.whatsapp.service.WhatsAppOnboardingService;
import com.xetax.crm.whatsapp.service.WhatsAppTemplateService;
import com.xetax.crm.whatsapp.service.WhatsAppUsageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Connection lifecycle + templates. All owner-scoped via the JWT user. */
@RestController
@RequestMapping("/api/whatsapp")
@RequiredArgsConstructor
public class WhatsAppConfigController {

    private final WhatsAppConfigService configService;
    private final WhatsAppOnboardingService onboardingService;
    private final WhatsAppTemplateService templateService;
    private final WhatsAppUsageService usageService;

    @GetMapping("/config")
    @RequiresPermission("whatsapp.view")
    public ApiResponse<WhatsAppConfigResponse> status() {
        return ResponseUtil.success("WhatsApp status", configService.statusResponse());
    }

    /** Non-secret Meta app info the frontend needs for Embedded Signup. */
    @GetMapping("/meta")
    @RequiresPermission("whatsapp.view")
    public ApiResponse<Map<String, Object>> meta() {
        return ResponseUtil.success("Embedded signup metadata", configService.embeddedSignupMeta());
    }

    @PostMapping("/onboarding/complete")
    @RequiresPermission("whatsapp.manage")
    public ApiResponse<WhatsAppConfigResponse> completeOnboarding(
            @RequestBody OnboardingCompleteRequest request) {
        return ResponseUtil.success("WhatsApp connected",
                onboardingService.completeEmbeddedSignup(request));
    }

    @PostMapping("/onboarding/manual")
    @RequiresPermission("whatsapp.manage")
    public ApiResponse<WhatsAppConfigResponse> manualConnect(
            @RequestBody ManualConnectRequest request) {
        return ResponseUtil.success("WhatsApp connected",
                onboardingService.manualConnect(request));
    }

    @PostMapping("/disconnect")
    @RequiresPermission("whatsapp.manage")
    public ApiResponse<WhatsAppConfigResponse> disconnect() {
        return ResponseUtil.success("WhatsApp disconnected", configService.disconnect());
    }

    /** This month's message counts (our DB) + official Meta spend (cached 1h). */
    @GetMapping("/usage")
    @RequiresPermission("whatsapp.view")
    public ApiResponse<Map<String, Object>> usage() {
        return ResponseUtil.success("Usage", usageService.usage());
    }

    @GetMapping("/templates")
    @RequiresPermission("whatsapp.view")
    public ApiResponse<List<WhatsAppTemplateResponse>> templates() {
        return ResponseUtil.success("Templates", templateService.myTemplates());
    }

    /** Submits a new template to Meta for approval (MARKETING/UTILITY/AUTHENTICATION). */
    @PostMapping("/templates")
    @RequiresPermission("whatsapp.manage")
    public ApiResponse<WhatsAppTemplateResponse> createTemplate(
            @RequestBody TemplateCreateRequest request) {
        return ResponseUtil.success("Template submitted for approval",
                templateService.createTemplate(request));
    }

    @DeleteMapping("/templates/{name}")
    @RequiresPermission("whatsapp.manage")
    public ApiResponse<Void> deleteTemplate(@PathVariable String name) {
        templateService.deleteTemplate(name);
        return ResponseUtil.success("Template deleted");
    }

    @PostMapping("/templates/sync")
    @RequiresPermission("whatsapp.manage")
    public ApiResponse<List<WhatsAppTemplateResponse>> syncTemplates() {
        templateService.syncTemplates(configService.requireConnectedConfig());
        return ResponseUtil.success("Templates synced", templateService.myTemplates());
    }
}
