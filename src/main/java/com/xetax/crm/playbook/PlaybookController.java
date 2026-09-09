package com.xetax.crm.playbook;

import com.xetax.crm.common.responce.ApiResponse;
import com.xetax.crm.common.responce.ResponseUtil;
import com.xetax.crm.team.service.RequiresPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/playbooks")
@RequiredArgsConstructor
public class PlaybookController {

    private final PlaybookService service;

    @GetMapping
    @RequiresPermission("automations.view")
    public ApiResponse<List<Map<String, Object>>> list() {
        return ResponseUtil.success("Playbooks", service.list());
    }

    @GetMapping("/form/{formId}")
    @RequiresPermission("automations.view")
    public ApiResponse<Map<String, Object>> forForm(@PathVariable Long formId) {
        return ResponseUtil.success("Playbook", service.forForm(formId));
    }

    @PutMapping("/form/{formId}")
    @RequiresPermission("automations.manage")
    public ApiResponse<Map<String, Object>> save(@PathVariable Long formId, @RequestBody PlaybookRequest body) {
        return ResponseUtil.success("Playbook saved", service.save(formId, body));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission("automations.manage")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseUtil.success("Playbook deleted", null);
    }

    @GetMapping("/{id}/runs")
    @RequiresPermission("automations.view")
    public ApiResponse<List<Map<String, Object>>> runs(@PathVariable Long id) {
        return ResponseUtil.success("Runs", service.recentRuns(id));
    }

    @GetMapping("/record/{recordId}/runs")
    @RequiresPermission("records.view")
    public ApiResponse<List<Map<String, Object>>> recordRuns(@PathVariable String recordId) {
        return ResponseUtil.success("Runs", service.recordRuns(recordId));
    }

    @PostMapping("/{id}/preview")
    @RequiresPermission("automations.view")
    public ApiResponse<List<PlaybookEngine.RuleStat>> preview(@PathVariable Long id) {
        return ResponseUtil.success("Preview", service.preview(id));
    }

    @PostMapping("/{id}/run-now")
    @RequiresPermission("automations.manage")
    public ApiResponse<List<PlaybookEngine.RuleStat>> runNow(@PathVariable Long id) {
        return ResponseUtil.success("Playbook ran", service.runNow(id));
    }

    /** Static option lists for the editor — no hardcoding in the UI. */
    @GetMapping("/options")
    @RequiresPermission("automations.view")
    public ApiResponse<Map<String, Object>> options() {
        return ResponseUtil.success("Options", Map.of(
                "triggers", PlaybookRule.TRIGGERS, "actions", PlaybookRule.ACTIONS, "ops", PlaybookRule.OPS,
                "interests", List.of("HOT", "WARM", "COLD")));
    }
}
