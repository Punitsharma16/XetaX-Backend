package com.xetax.crm.platform;

import com.xetax.crm.common.responce.ApiResponse;
import com.xetax.crm.common.responce.ResponseUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * XetaX's own back office. Every method is behind PlatformAdminGuard — a
 * customer's workspace admin gets a 401 here, no matter what their role says.
 */
@RestController
@RequestMapping("/api/platform")
@RequiredArgsConstructor
public class PlatformController {

    private final PlatformService service;
    private final PlatformAdminGuard guard;
    private final com.xetax.crm.whatsapp.pricing.WhatsAppRateService rateService;

    /** Cheap check the panel uses to decide whether to show the console at all. */
    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me() {
        return ResponseUtil.success("Platform access", Map.of("platformAdmin", guard.is()));
    }

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview() {
        return ResponseUtil.success("Overview", service.overview());
    }

    @GetMapping("/workspaces")
    public ApiResponse<List<Map<String, Object>>> workspaces(@RequestParam(required = false) String q,
                                                             @RequestParam(required = false) String plan) {
        return ResponseUtil.success("Workspaces", service.workspaces(q, plan));
    }

    @GetMapping("/workspaces/{ownerUserId}")
    public ApiResponse<Map<String, Object>> workspace(@PathVariable String ownerUserId) {
        return ResponseUtil.success("Workspace", service.workspace(ownerUserId));
    }

    @PostMapping("/workspaces/{ownerUserId}/plan")
    public ApiResponse<Map<String, Object>> setPlan(@PathVariable String ownerUserId,
                                                    @RequestBody PlatformService.PlanRequest body) {
        return ResponseUtil.success("Plan updated", service.setPlan(ownerUserId, body));
    }

    /** body: {"messages": 1000} — negative takes credits back. */
    @PostMapping("/workspaces/{ownerUserId}/ai-credit")
    public ApiResponse<Map<String, Object>> addCredit(@PathVariable String ownerUserId,
                                                      @RequestBody Map<String, Integer> body) {
        int messages = body == null || body.get("messages") == null ? 0 : body.get("messages");
        return ResponseUtil.success("AI credits updated", service.addCredit(ownerUserId, messages));
    }

    @PostMapping("/workspaces/{ownerUserId}/status")
    public ApiResponse<Map<String, Object>> setStatus(@PathVariable String ownerUserId,
                                                      @RequestBody Map<String, Boolean> body) {
        boolean enabled = body == null || body.get("enabled") == null || body.get("enabled");
        return ResponseUtil.success(enabled ? "Workspace enabled" : "Workspace disabled",
                service.setEnabled(ownerUserId, enabled));
    }

    @PostMapping("/users/{userId}/platform-admin")
    public ApiResponse<Map<String, Object>> setPlatformAdmin(@PathVariable String userId,
                                                             @RequestBody Map<String, Boolean> body) {
        boolean value = body != null && Boolean.TRUE.equals(body.get("value"));
        return ResponseUtil.success("Updated", service.setPlatformAdmin(userId, value));
    }

    /* -------------------------------------------- WhatsApp rate card (India) */

    @GetMapping("/whatsapp-rates")
    public ApiResponse<List<Map<String, Object>>> whatsappRates() {
        guard.require();
        return ResponseUtil.success("Rates", rateService.list());
    }

    /** body: {"category":"MARKETING","rate":0.8631,"effectiveFrom":"2026-10-01","note":"..."} */
    @PostMapping("/whatsapp-rates")
    public ApiResponse<Map<String, Object>> saveWhatsappRate(
            @RequestBody com.xetax.crm.whatsapp.pricing.WhatsAppRateService.RateInput body) {
        guard.require();
        return ResponseUtil.success("Rate saved", rateService.upsert(body));
    }

    @DeleteMapping("/whatsapp-rates/{id}")
    public ApiResponse<Void> deleteWhatsappRate(@PathVariable Long id) {
        guard.require();
        rateService.delete(id);
        return ResponseUtil.success("Rate deleted");
    }

    @GetMapping("/plans")
    public ApiResponse<List<Map<String, Object>>> plans() {
        return ResponseUtil.success("Plans", service.plansCatalog());
    }
}
