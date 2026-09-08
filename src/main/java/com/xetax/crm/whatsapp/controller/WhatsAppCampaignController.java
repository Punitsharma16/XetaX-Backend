package com.xetax.crm.whatsapp.controller;

import com.xetax.crm.team.service.RequiresPermission;

import com.xetax.crm.common.responce.ApiResponse;
import com.xetax.crm.common.responce.ResponseUtil;
import com.xetax.crm.whatsapp.dto.CampaignCreateRequest;
import com.xetax.crm.whatsapp.dto.CampaignRecipientResponse;
import com.xetax.crm.whatsapp.dto.CampaignResponse;
import com.xetax.crm.whatsapp.service.WhatsAppCampaignService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/whatsapp/campaigns")
@RequiredArgsConstructor
public class WhatsAppCampaignController {

    private final WhatsAppCampaignService campaignService;

    @PostMapping
    @RequiresPermission("whatsapp.campaigns")
    public ApiResponse<CampaignResponse> create(@RequestBody CampaignCreateRequest request) {
        return ResponseUtil.success("Campaign created", campaignService.create(request));
    }

    @GetMapping
    @RequiresPermission("whatsapp.campaigns")
    public ApiResponse<Page<CampaignResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseUtil.success("Campaigns", campaignService.list(page, size));
    }

    @GetMapping("/{id}")
    @RequiresPermission("whatsapp.campaigns")
    public ApiResponse<CampaignResponse> get(@PathVariable Long id) {
        return ResponseUtil.success("Campaign", campaignService.get(id));
    }

    @PostMapping("/{id}/csv")
    @RequiresPermission("whatsapp.campaigns")
    public ApiResponse<CampaignResponse> uploadCsv(@PathVariable Long id,
                                                   @RequestParam("file") MultipartFile file) {
        return ResponseUtil.success("Recipients added", campaignService.uploadCsv(id, file));
    }

    /** Optional body {"scheduledAt": "2026-08-22T10:00:00Z"} schedules instead of starting. */
    @PostMapping("/{id}/start")
    @RequiresPermission("whatsapp.campaigns")
    public ApiResponse<CampaignResponse> start(@PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body) {
        Instant scheduledAt = null;
        if (body != null && body.get("scheduledAt") != null && !body.get("scheduledAt").isBlank()) {
            scheduledAt = Instant.parse(body.get("scheduledAt"));
        }
        CampaignResponse response = campaignService.start(id, scheduledAt);
        return ResponseUtil.success(scheduledAt == null ? "Campaign started" : "Campaign scheduled",
                response);
    }

    @PostMapping("/{id}/pause")
    @RequiresPermission("whatsapp.campaigns")
    public ApiResponse<CampaignResponse> pause(@PathVariable Long id) {
        return ResponseUtil.success("Campaign paused", campaignService.pause(id));
    }

    @PostMapping("/{id}/resume")
    @RequiresPermission("whatsapp.campaigns")
    public ApiResponse<CampaignResponse> resume(@PathVariable Long id) {
        return ResponseUtil.success("Campaign resumed", campaignService.resume(id));
    }

    @PostMapping("/{id}/cancel")
    @RequiresPermission("whatsapp.campaigns")
    public ApiResponse<CampaignResponse> cancel(@PathVariable Long id) {
        return ResponseUtil.success("Campaign cancelled", campaignService.cancel(id));
    }

    @GetMapping("/{id}/recipients")
    @RequiresPermission("whatsapp.campaigns")
    public ApiResponse<Page<CampaignRecipientResponse>> recipients(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String status) {
        return ResponseUtil.success("Recipients", campaignService.recipients(id, page, size, status));
    }
}
