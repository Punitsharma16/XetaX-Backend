package com.xetax.crm.whatsapp.controller;

import com.xetax.crm.team.service.RequiresPermission;
import com.xetax.crm.common.responce.ApiResponse;
import com.xetax.crm.common.responce.ResponseUtil;
import com.xetax.crm.whatsapp.dto.*;
import com.xetax.crm.whatsapp.service.WhatsAppFlowService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** WhatsApp Flows: build them, publish them, send them, read what came back. */
@RestController
@RequestMapping("/api/whatsapp/flows")
@RequiredArgsConstructor
public class WhatsAppFlowController {

    private final WhatsAppFlowService flowService;

    @GetMapping
    @RequiresPermission("whatsapp.view")
    public ApiResponse<List<FlowView>> list() {
        return ResponseUtil.success("Flows", flowService.myFlows());
    }

    @GetMapping("/options")
    @RequiresPermission("whatsapp.view")
    public ApiResponse<Map<String, Object>> options() {
        return ResponseUtil.success("Options", Map.of("categories", flowService.categories()));
    }

    @GetMapping("/{id}")
    @RequiresPermission("whatsapp.view")
    public ApiResponse<FlowView> get(@PathVariable Long id) {
        return ResponseUtil.success("Flow", flowService.get(id));
    }

    @PostMapping
    @RequiresPermission("whatsapp.manage")
    public ApiResponse<FlowView> create(@RequestBody FlowCreateRequest request) {
        return ResponseUtil.success("Flow created", flowService.create(request));
    }

    @PutMapping("/{id}")
    @RequiresPermission("whatsapp.manage")
    public ApiResponse<FlowView> update(@PathVariable Long id, @RequestBody FlowCreateRequest request) {
        return ResponseUtil.success("Flow updated", flowService.updateScreens(id, request));
    }

    @PostMapping("/{id}/publish")
    @RequiresPermission("whatsapp.manage")
    public ApiResponse<FlowView> publish(@PathVariable Long id) {
        return ResponseUtil.success("Flow published", flowService.publish(id));
    }

    @PostMapping("/{id}/deprecate")
    @RequiresPermission("whatsapp.manage")
    public ApiResponse<FlowView> deprecate(@PathVariable Long id) {
        return ResponseUtil.success("Flow retired", flowService.deprecate(id));
    }

    @GetMapping("/{id}/preview")
    @RequiresPermission("whatsapp.view")
    public ApiResponse<Map<String, String>> preview(@PathVariable Long id) {
        String url = flowService.previewUrl(id);
        return ResponseUtil.success("Preview", url == null ? Map.of() : Map.of("url", url));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission("whatsapp.manage")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        flowService.delete(id);
        return ResponseUtil.success("Flow deleted", null);
    }

    @PostMapping("/send")
    @RequiresPermission("whatsapp.inbox")
    public ApiResponse<FlowResponseView> send(@RequestBody FlowSendRequest request) {
        return ResponseUtil.success("Flow sent", flowService.send(request));
    }

    /** Creates the record again for a submission whose record failed. */
    @PostMapping("/responses/{id}/record")
    @RequiresPermission("whatsapp.inbox")
    public ApiResponse<FlowResponseView> retryRecord(@PathVariable Long id) {
        return ResponseUtil.success("Record retried", flowService.retryRecord(id));
    }

    /** Submissions, newest first. Omit flowId for everything the workspace got. */
    @GetMapping("/responses")
    @RequiresPermission("whatsapp.view")
    public ApiResponse<Page<FlowResponseView>> responses(
            @RequestParam(required = false) Long flowId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseUtil.success("Responses", flowService.responses(flowId, page, size));
    }
}
