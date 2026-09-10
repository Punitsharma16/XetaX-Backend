package com.xetax.crm.meta;

import com.xetax.crm.common.responce.ApiResponse;
import com.xetax.crm.common.responce.ResponseUtil;
import com.xetax.crm.team.service.RequiresPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Facebook & Instagram lead ads: connect, configure, and the spend report. */
@RestController
@RequestMapping("/api/meta")
@RequiredArgsConstructor
public class MetaController {

    private final MetaConnectService connectService;
    private final MetaReportService reportService;
    private final MetaInsightsService insightsService;
    private final MetaConnectionRepository connections;

    @GetMapping("/signup-meta")
    @RequiresPermission("integrations.view")
    public ApiResponse<Map<String, Object>> signupMeta() {
        return ResponseUtil.success("Facebook signup metadata", connectService.signupMeta());
    }

    @GetMapping("/connections")
    @RequiresPermission("integrations.view")
    public ApiResponse<List<Map<String, Object>>> list() {
        return ResponseUtil.success("Connections", connectService.list());
    }

    /** Step 1 — code from the popup; returns the Pages and ad accounts to choose from. */
    @PostMapping("/connect")
    @RequiresPermission("integrations.manage")
    public ApiResponse<Map<String, Object>> connect(@RequestBody MetaConnectService.ConnectRequest body) {
        return ResponseUtil.success("Choose a Page", connectService.beginConnect(body));
    }

    /** Step 2 — the chosen Page, ad account and CRM form. */
    @PostMapping("/choose")
    @RequiresPermission("integrations.manage")
    public ApiResponse<Map<String, Object>> choose(@RequestBody MetaConnectService.ChooseRequest body) {
        return ResponseUtil.success("Facebook connected", connectService.choose(body));
    }

    @PutMapping("/connections/{id}")
    @RequiresPermission("integrations.manage")
    public ApiResponse<Map<String, Object>> update(@PathVariable Long id,
                                                   @RequestBody MetaConnectService.SettingsRequest body) {
        return ResponseUtil.success("Saved", connectService.updateSettings(id, body));
    }

    @DeleteMapping("/connections/{id}")
    @RequiresPermission("integrations.manage")
    public ApiResponse<Void> disconnect(@PathVariable Long id) {
        connectService.disconnect(id);
        return ResponseUtil.success("Disconnected", null);
    }

    /** Suggested Meta field → CRM field mapping for a form. */
    @GetMapping("/field-map/{formId}")
    @RequiresPermission("integrations.view")
    public ApiResponse<Map<String, String>> fieldMap(@PathVariable Long formId) {
        return ResponseUtil.success("Suggested mapping", connectService.defaultMap(formId));
    }

    @GetMapping("/report")
    @RequiresPermission("integrations.view")
    public ApiResponse<Map<String, Object>> report(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseUtil.success("Ad performance", reportService.report(from, to));
    }

    @GetMapping("/recent-leads")
    @RequiresPermission("integrations.view")
    public ApiResponse<List<Map<String, Object>>> recentLeads() {
        return ResponseUtil.success("Recent ad leads", reportService.recentLeads());
    }

    /** Pull spend now instead of waiting for tonight's sync. */
    @PostMapping("/connections/{id}/sync")
    @RequiresPermission("integrations.manage")
    public ApiResponse<Map<String, Object>> sync(@PathVariable Long id,
                                                 @RequestParam(defaultValue = "30") int days) {
        MetaConnection connection = connections.findById(id)
                .orElseThrow(() -> new com.xetax.crm.common.exception.ResourceNotFoundException("Connection not found"));
        int rows = insightsService.sync(connection, days);
        return ResponseUtil.success("Ad spend updated", Map.of("rows", rows));
    }
}
