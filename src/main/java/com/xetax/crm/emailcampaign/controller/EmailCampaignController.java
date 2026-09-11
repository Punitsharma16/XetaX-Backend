package com.xetax.crm.emailcampaign.controller;

import com.xetax.crm.common.responce.ApiResponse;
import com.xetax.crm.common.responce.ResponseUtil;
import com.xetax.crm.emailcampaign.dto.EmailCampaignCreateRequest;
import com.xetax.crm.emailcampaign.dto.EmailCampaignRecipientResponse;
import com.xetax.crm.emailcampaign.dto.EmailCampaignResponse;
import com.xetax.crm.emailcampaign.service.EmailCampaignService;
import com.xetax.crm.team.service.RequiresPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.Map;

/** Mirrors /api/whatsapp/campaigns one-to-one so the panel can share its wizard. */
@RestController
@RequestMapping("/api/email/campaigns")
@RequiredArgsConstructor
public class EmailCampaignController {

    private final EmailCampaignService campaignService;

    /** Is the org's own SMTP set up (the only sender campaigns use), plus the pacing limits. */
    @GetMapping("/status")
    @RequiresPermission("email.campaigns")
    public ApiResponse<Map<String, Object>> status() {
        return ResponseUtil.success("Email campaign status", campaignService.status());
    }

    @PostMapping
    @RequiresPermission("email.campaigns")
    public ApiResponse<EmailCampaignResponse> create(@RequestBody EmailCampaignCreateRequest request) {
        return ResponseUtil.success("Campaign created", campaignService.create(request));
    }

    @GetMapping
    @RequiresPermission("email.campaigns")
    public ApiResponse<Page<EmailCampaignResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseUtil.success("Campaigns", campaignService.list(page, size));
    }

    @GetMapping("/{id}")
    @RequiresPermission("email.campaigns")
    public ApiResponse<EmailCampaignResponse> get(@PathVariable Long id) {
        return ResponseUtil.success("Campaign", campaignService.get(id));
    }

    @PostMapping("/{id}/csv")
    @RequiresPermission("email.campaigns")
    public ApiResponse<EmailCampaignResponse> uploadCsv(@PathVariable Long id,
                                                        @RequestParam("file") MultipartFile file) {
        return ResponseUtil.success("Recipients added", campaignService.uploadCsv(id, file));
    }

    /** Optional body {"scheduledAt": "2026-09-12T10:00:00Z"} schedules instead of starting. */
    @PostMapping("/{id}/start")
    @RequiresPermission("email.campaigns")
    public ApiResponse<EmailCampaignResponse> start(@PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body) {
        Instant scheduledAt = null;
        if (body != null && body.get("scheduledAt") != null && !body.get("scheduledAt").isBlank()) {
            scheduledAt = Instant.parse(body.get("scheduledAt"));
        }
        EmailCampaignResponse response = campaignService.start(id, scheduledAt);
        return ResponseUtil.success(scheduledAt == null ? "Campaign started" : "Campaign scheduled",
                response);
    }

    @PostMapping("/{id}/pause")
    @RequiresPermission("email.campaigns")
    public ApiResponse<EmailCampaignResponse> pause(@PathVariable Long id) {
        return ResponseUtil.success("Campaign paused", campaignService.pause(id));
    }

    @PostMapping("/{id}/resume")
    @RequiresPermission("email.campaigns")
    public ApiResponse<EmailCampaignResponse> resume(@PathVariable Long id) {
        return ResponseUtil.success("Campaign resumed", campaignService.resume(id));
    }

    @PostMapping("/{id}/cancel")
    @RequiresPermission("email.campaigns")
    public ApiResponse<EmailCampaignResponse> cancel(@PathVariable Long id) {
        return ResponseUtil.success("Campaign cancelled", campaignService.cancel(id));
    }

    @GetMapping("/{id}/recipients")
    @RequiresPermission("email.campaigns")
    public ApiResponse<Page<EmailCampaignRecipientResponse>> recipients(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String status) {
        return ResponseUtil.success("Recipients", campaignService.recipients(id, page, size, status));
    }
}
