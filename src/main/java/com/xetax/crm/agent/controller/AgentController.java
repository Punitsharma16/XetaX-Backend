package com.xetax.crm.agent.controller;

import com.xetax.crm.agent.entity.AgentSource;
import com.xetax.crm.agent.service.AgentService;
import com.xetax.crm.common.responce.ApiResponse;
import com.xetax.crm.common.responce.ResponseUtil;
import com.xetax.crm.team.service.RequiresPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/agents")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;

    @PostMapping
    @RequiresPermission("agents.manage")
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, String> body) {
        return ResponseUtil.success("Agent created", agentService.create(
                body.get("name"), body.get("persona"),
                body.get("welcomeMessage"), body.get("themeColor")));
    }

    @GetMapping
    @RequiresPermission("agents.manage")
    public ApiResponse<List<Map<String, Object>>> list() {
        return ResponseUtil.success("Agents", agentService.list());
    }

    @GetMapping("/{id}")
    @RequiresPermission("agents.manage")
    public ApiResponse<Map<String, Object>> get(@PathVariable Long id) {
        return ResponseUtil.success("Agent", agentService.get(id));
    }

    @PutMapping("/{id}")
    @RequiresPermission("agents.manage")
    public ApiResponse<Map<String, Object>> update(@PathVariable Long id,
                                                   @RequestBody Map<String, String> body) {
        return ResponseUtil.success("Agent updated", agentService.update(id,
                body.get("name"), body.get("persona"), body.get("welcomeMessage"),
                body.get("themeColor"), body.get("status")));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission("agents.manage")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        agentService.delete(id);
        return ResponseUtil.success("Agent deleted");
    }

    /* ------------------------------------------------------------- sources */

    @GetMapping("/{id}/sources")
    @RequiresPermission("agents.manage")
    public ApiResponse<List<AgentSource>> sources(@PathVariable Long id) {
        return ResponseUtil.success("Sources", agentService.sources(id));
    }

    @PostMapping("/{id}/sources/pdf")
    @RequiresPermission("agents.manage")
    public ApiResponse<AgentSource> addPdf(@PathVariable Long id,
                                           @RequestParam("file") MultipartFile file) {
        return ResponseUtil.success("PDF indexed", agentService.addPdf(id, file));
    }

    @PostMapping("/{id}/sources/url")
    @RequiresPermission("agents.manage")
    public ApiResponse<AgentSource> addUrl(@PathVariable Long id,
                                           @RequestBody Map<String, String> body) {
        return ResponseUtil.success("Page indexed", agentService.addUrl(id, body.get("url")));
    }

    @PostMapping("/{id}/sources/text")
    @RequiresPermission("agents.manage")
    public ApiResponse<AgentSource> addText(@PathVariable Long id,
                                            @RequestBody Map<String, String> body) {
        return ResponseUtil.success("Text indexed",
                agentService.addText(id, body.get("name"), body.get("text")));
    }

    @DeleteMapping("/{id}/sources/{sourceId}")
    @RequiresPermission("agents.manage")
    public ApiResponse<Void> deleteSource(@PathVariable Long id, @PathVariable Long sourceId) {
        agentService.deleteSource(id, sourceId);
        return ResponseUtil.success("Source deleted");
    }
}
